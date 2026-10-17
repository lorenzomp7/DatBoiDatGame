#!/usr/bin/env python3
"""Extract the Dat Boi assets from the original Unity APK and bake them for the
native Android port.

Usage:  python3 tools/bake_assets.py <unzipped-apk-dir> <output-assets-dir>

Requires: pip install UnityPy

What it produces (all consumed by app/src/main/java/.../Assets.java):
  model.bin      frog + unicycle meshes, skin weights, baked animation tracks
  *.png          hitmarker, smoke particle
  *.ogg          audio clips (needs ffmpeg in PATH, falls back to .wav)
  coolvetica.ttf font used by the in-world 3D text

Coordinates are converted from Unity's left-handed space to OpenGL's
right-handed space by mirroring Z (M' = S*M*S with S = diag(1,1,-1)).
"""
import math
import os
import shutil
import struct
import subprocess
import sys
import zlib

import UnityPy
from UnityPy.helpers.MeshHelper import MeshHandler

SRC = sys.argv[1]
DST = sys.argv[2]
os.makedirs(DST, exist_ok=True)

env = UnityPy.load(os.path.join(SRC, "assets/bin/Data"))
scene = [o for o in env.objects if o.assets_file.name == "level0"][0].assets_file


def deref(pptr, af=scene):
    if pptr["m_PathID"] == 0:
        return None
    if pptr["m_FileID"] == 0:
        return af.objects.get(pptr["m_PathID"])
    ext = af.externals[pptr["m_FileID"] - 1].path.split("/")[-1].lower()
    for o in env.objects:
        if o.assets_file.name.lower() == ext and o.path_id == pptr["m_PathID"]:
            return o
    return None


# ---------------------------------------------------------------- math helpers
def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def mat_vec(m, v, w=1.0):
    return [m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] + m[i][3] * w for i in range(3)]


def trs(t, q, s):
    x, y, z, w = q
    r = [
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ]
    return [
        [r[0][0] * s[0], r[0][1] * s[1], r[0][2] * s[2], t[0]],
        [r[1][0] * s[0], r[1][1] * s[1], r[1][2] * s[2], t[1]],
        [r[2][0] * s[0], r[2][1] * s[1], r[2][2] * s[2], t[2]],
        [0, 0, 0, 1],
    ]


def mat_inv(m):
    # general 4x4 inverse (Gauss-Jordan)
    a = [row[:] + [1.0 if i == j else 0.0 for j in range(4)] for i, row in enumerate(m)]
    for c in range(4):
        p = max(range(c, 4), key=lambda r: abs(a[r][c]))
        a[c], a[p] = a[p], a[c]
        pv = a[c][c]
        a[c] = [v / pv for v in a[c]]
        for r in range(4):
            if r != c:
                f = a[r][c]
                a[r] = [a[r][k] - f * a[c][k] for k in range(8)]
    return [row[4:] for row in a]


def to_gl(m):
    """Unity (left-handed) matrix -> GL (right-handed) matrix: S*M*S."""
    s = [1, 1, -1, 1]
    return [[m[i][j] * s[i] * s[j] for j in range(4)] for i in range(4)]


def col_major(m):
    return [m[r][c] for c in range(4) for r in range(4)]


def v3(d):
    return (d["x"], d["y"], d["z"])


def q4(d):
    return (d["x"], d["y"], d["z"], d["w"])


def slerp(a, b, t):
    d = sum(x * y for x, y in zip(a, b))
    if d < 0:
        b = [-x for x in b]
        d = -d
    if d > 0.9995:
        r = [x + (y - x) * t for x, y in zip(a, b)]
    else:
        th = math.acos(d)
        sa, sb = math.sin((1 - t) * th), math.sin(t * th)
        r = [(x * sa + y * sb) / math.sin(th) for x, y in zip(a, b)]
    n = math.sqrt(sum(x * x for x in r))
    return [x / n for x in r]


# ------------------------------------------------------------- scene hierarchy
class Node:
    def __init__(self, tid, data):
        self.tid = tid
        self.parent = data["m_Father"]["m_PathID"]
        self.children = [c["m_PathID"] for c in data["m_Children"]]
        self.t = list(v3(data["m_LocalPosition"]))
        self.q = list(q4(data["m_LocalRotation"]))
        self.s = list(v3(data["m_LocalScale"]))
        go = scene.objects[data["m_GameObject"]["m_PathID"]].read_typetree()
        self.name = go["m_Name"]
        self.active = go["m_IsActive"]
        self.comps = {}
        for c in go["m_Component"]:
            p = c[1] if not isinstance(c, dict) else c["component"]
            co = scene.objects[p["m_PathID"]]
            self.comps.setdefault(co.type.name, co)

    def local(self):
        return trs(self.t, self.q, self.s)


nodes = {}
for pid, o in scene.objects.items():
    if o.type.name in ("Transform", "RectTransform"):
        nodes[pid] = Node(pid, o.read_typetree())


def find(name, root=None):
    for n in nodes.values():
        if n.name == name and (root is None or is_under(n, root)):
            return n
    raise KeyError(name)


def is_under(n, root):
    while n is not None:
        if n is root:
            return True
        n = nodes.get(n.parent)
    return False


def world(n, stop=None):
    m = n.local()
    p = nodes.get(n.parent)
    while p is not None and p is not stop:
        m = mat_mul(p.local(), m)
        p = nodes.get(p.parent)
    return m


def rel_path(n, root):
    parts = []
    while n is not root:
        parts.append(n.name)
        n = nodes[n.parent]
    return "/".join(reversed(parts))


datboi = find("Dat Boi")
balance = find("balanceThing")
wheel = find("PushWheel")

# ------------------------------------------------------------------ animation
clip = [o for o in env.objects if o.type.name == "AnimationClip" and o.read().m_Name == "Basis"][0]
cd = clip.read_typetree()
mc = cd["m_MuscleClip"]["m_Clip"]["data"]
stop_time = cd["m_MuscleClip"]["m_StopTime"]
bindings = cd["m_ClipBindingConstant"]["genericBindings"]

# Decode the streamed clip (hermite segments per curve).
words = mc["m_StreamedClip"]["data"]
buf = struct.pack("<%dI" % len(words), *words)
off = 0
segments = {}  # curve -> list of (time, coeff[4])
while off < len(buf):
    time, nkeys = struct.unpack_from("<fi", buf, off)
    off += 8
    for _ in range(nkeys):
        idx = struct.unpack_from("<i", buf, off)[0]
        coeff = struct.unpack_from("<4f", buf, off + 4)
        off += 20
        segments.setdefault(idx, []).append((time, coeff))
assert mc["m_DenseClip"]["m_CurveCount"] == 0 and len(mc["m_ConstantClip"]["data"]) == 0


def curve_value(idx, t):
    segs = segments[idx]
    best = segs[0]
    for s in segs:
        if s[0] <= t:
            best = s
    dt = t - best[0]
    a, b, c, d = best[1]
    return ((a * dt + b) * dt + c) * dt + d


path_hash = {}
for n in nodes.values():
    if is_under(n, datboi) and n is not datboi:
        path_hash[zlib.crc32(rel_path(n, datboi).encode()) & 0xFFFFFFFF] = n

anim = []  # (node, attribute, first curve index)
ci = 0
for b in bindings:
    n = path_hash[b["path"]]
    anim.append((n, b["attribute"], ci))
    ci += {1: 3, 2: 4, 3: 3, 4: 3}.get(b["attribute"], 1)
print("animated:", [(n.name, a) for n, a, _ in anim])

FRAMES = 100  # 4 s @ 25 fps, frame 100 == frame 0 (loop)


def pose(frame):
    t = stop_time * frame / FRAMES
    for n, attr, c in anim:
        vals = [curve_value(c + k, t) for k in range(3 if attr != 2 else 4)]
        if attr == 1:
            n.t = vals
        elif attr == 2:
            l = math.sqrt(sum(v * v for v in vals))
            n.q = [v / l for v in vals]


rest = {n.tid: (n.t[:], n.q[:]) for n in nodes.values()}


def restore():
    for n in nodes.values():
        n.t, n.q = list(rest[n.tid][0]), list(rest[n.tid][1])


# ------------------------------------------------------------------ materials
def material(renderer):
    t = renderer.read_typetree()
    mo = deref(t["m_Materials"][0])
    mt = mo.read_typetree()
    col = (1, 1, 1, 1)
    for k, v in mt["m_SavedProperties"]["m_Colors"]:
        if k["name"] == "_Color":
            col = (v["r"], v["g"], v["b"], v["a"])
    return col


def mesh_data(mesh_obj):
    m = mesh_obj.read()
    h = MeshHandler(m)
    h.process()
    tris = [i for sub in h.get_triangles() for tri in sub for i in tri]
    return m, h, tris


# ------------------------------------------------------------------ parts
ACCESSORIES = {"mlg": ("ShadesLocation", 1), "fedora": ("FedoraLocation", 2), "DankBlunt": ("BluntPosition", 3)}

parts = []
for n in nodes.values():
    if not is_under(n, datboi) or n is datboi:
        continue
    # skip disabled sprite planes that the game never shows
    chain, p, acc = [], n, 0
    hidden = False
    while p is not datboi:
        if not p.active:
            if p.name in ACCESSORIES:
                acc = ACCESSORIES[p.name][1]
            else:
                hidden = True
        p = nodes[p.parent]
    if hidden:
        continue
    if "SkinnedMeshRenderer" in n.comps:
        smr = n.comps["SkinnedMeshRenderer"]
        parts.append(("skin", n, deref(smr.read_typetree()["m_Mesh"]), material(smr), acc))
    elif "MeshRenderer" in n.comps and "MeshFilter" in n.comps:
        r = n.comps["MeshRenderer"].read_typetree()
        if not r["m_Enabled"]:
            continue
        mesh = deref(n.comps["MeshFilter"].read_typetree()["m_Mesh"])
        parts.append(("rigid", n, mesh, material(n.comps["MeshRenderer"]), acc))

out = bytearray()


def w_i(v):
    out.extend(struct.pack("<i", v))


def w_f(*v):
    out.extend(struct.pack("<%df" % len(v), *v))


def w_s(s):
    b = s.encode()
    w_i(len(b))
    out.extend(b)


out.extend(b"DBOI")
w_i(2)  # version

# Dat Boi attaches to the balance pole through a FixedJoint; store its pose
# relative to the pole so the game can glue the model to the physics body.
rel = mat_mul(mat_inv(world(balance)), world(datboi))
w_f(*col_major(to_gl(rel)))
pole_world = world(balance)
w_f(*col_major(to_gl(pole_world)))
w_f(*col_major(to_gl(world(wheel))))

# Rigid-part tracks: each part gets FRAMES matrices in Dat-Boi-local space.
skinned = [p for p in parts if p[0] == "skin"]
rigid = [p for p in parts if p[0] == "rigid"]
print("rigid parts:", len(rigid), "skinned:", len(skinned))

track = {p[1].tid: [] for p in rigid}
bone_frames = []
smr_bones = []
if skinned:
    smr = skinned[0][1].comps["SkinnedMeshRenderer"].read_typetree()
    smr_bones = [nodes[b["m_PathID"]] for b in smr["m_Bones"]]
    bindposes = skinned[0][2].read().m_BindPose
    bind = [[[bp.M[r][c] if hasattr(bp, "M") else getattr(bp, "e%d%d" % (r, c)) for c in range(4)] for r in range(4)] for bp in bindposes]

for f in range(FRAMES):
    restore()
    pose(f)
    for p in rigid:
        track[p[1].tid].append(world(p[1], stop=datboi))
    if skinned:
        bf = []
        for b, bp in zip(smr_bones, bind):
            bf.append(mat_mul(world(b, stop=datboi), bp))
        bone_frames.append(bf)
restore()

w_i(FRAMES)
w_f(stop_time)

w_i(len(parts))
for kind, n, mesh, col, acc in parts:
    m, h, tris = mesh_data(mesh)
    verts = h.m_Vertices
    norms = h.m_Normals
    w_s(n.name)
    w_i(1 if kind == "skin" else 0)
    w_i(acc)
    w_f(*col)
    w_i(len(verts))
    for v, nn in zip(verts, norms):
        w_f(v[0], v[1], -v[2], nn[0], nn[1], -nn[2])
    if kind == "skin":
        for bi, bw in zip(h.m_BoneIndices, h.m_BoneWeights):
            w_f(*[float(x) for x in bi])
            w_f(*bw)
    # flip winding because of the Z mirror
    w_i(len(tris))
    for i in range(0, len(tris), 3):
        out.extend(struct.pack("<3H", tris[i], tris[i + 2], tris[i + 1]))
    if kind == "rigid":
        for fm in track[n.tid]:
            w_f(*col_major(to_gl(fm)))

w_i(len(smr_bones))
for bf in bone_frames:
    for bm in bf:
        w_f(*col_major(to_gl(bm)))

# Accessory fly-in: start and target positions in Dat-Boi-local space.
w_i(len(ACCESSORIES))
for name, (target, aid) in ACCESSORIES.items():
    a, tnode = find(name, datboi), find(target, datboi)
    w_i(aid)
    w_f(a.t[0], a.t[1], -a.t[2])
    w_f(tnode.t[0], tnode.t[1], -tnode.t[2])
# smoke emitter position (blunt tip), in Dat-Boi-local space at its target
smoke = find("WhiteSmoke", datboi)
blunt = find("DankBlunt", datboi)
bp_ = find("BluntPosition", datboi)
saved = blunt.t[:]
blunt.t = bp_.t[:]
sm = world(smoke, stop=datboi)
blunt.t = saved
w_f(sm[0][3], sm[1][3], -sm[2][3])
# progress marker ("loc") in Dat-Boi-local space
loc = find("loc", datboi)
w_f(loc.t[0], loc.t[1], -loc.t[2])

with open(os.path.join(DST, "model.bin"), "wb") as fh:
    fh.write(out)
print("model.bin", len(out), "bytes")

# ------------------------------------------------------------------ textures
TEX = {
    "0a74feb686b3100a82295cfe835b3798": "hitmarker.png",
    "Smoke0391_L": "smoke.png",
}
for o in env.objects:
    if o.type.name == "Texture2D":
        try:
            d = o.read()
        except Exception:
            continue
        if d.m_Name in TEX:
            img = d.image
            if d.m_Name == "Smoke0391_L":
                img = img.resize((128, 256))
            img.save(os.path.join(DST, TEX[d.m_Name]))

# ------------------------------------------------------------------ audio
AUD = {"datboiloop": "music", "hitmarker": "hitmarker", "oh shit waddup": "ohshit", "k": "k"}
for o in env.objects:
    if o.type.name == "AudioClip":
        d = o.read()
        for _, data in d.samples.items():
            base = os.path.join(DST, AUD[d.m_Name])
            with open(base + ".wav", "wb") as fh:
                fh.write(data)
            if shutil.which("ffmpeg"):
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", base + ".wav", "-c:a", "libvorbis", "-q:a", "5", base + ".ogg"], check=True)
                os.remove(base + ".wav")

# ------------------------------------------------------------------ font
for o in env.objects:
    if o.type.name == "Font":
        d = o.read()
        if d.m_Name == "coolvetica rg" and d.m_FontData:
            with open(os.path.join(DST, "coolvetica.ttf"), "wb") as fh:
                fh.write(bytes(d.m_FontData))
print("done")
