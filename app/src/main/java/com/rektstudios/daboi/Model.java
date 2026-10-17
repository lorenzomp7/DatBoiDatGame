package com.rektstudios.daboi;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/** Loads model.bin produced by tools/bake_assets.py (CPU side only). */
final class Model {
    static final class Part {
        String name;
        boolean skinned;
        int accessory;           // 0 = always visible, otherwise accessory id
        float[] color = new float[4];
        int vertexCount;
        FloatBuffer vertices;    // pos.xyz normal.xyz [boneIdx.xyzw boneWeight.xyzw]
        ShortBuffer indices;
        int indexCount;
        float[][] track;         // rigid parts: per frame model matrix (Dat Boi local)
        int vbo, ibo;            // filled in by the renderer
    }

    static final class Accessory {
        int id;
        float[] start = new float[3];
        float[] target = new float[3];
    }

    final float[] poleToDatBoi = new float[16];
    final float[] poleWorld0 = new float[16];
    final float[] wheelWorld0 = new float[16];
    int frames;
    float clipLength;
    final List<Part> parts = new ArrayList<>();
    int boneCount;
    float[][][] bones;           // [frame][bone] -> mat4
    final List<Accessory> accessories = new ArrayList<>();
    final float[] smokeOrigin = new float[3];
    final float[] progressMarker = new float[3];

    static Model load(AssetManager am) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(readAll(am.open("model.bin"))).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'D' || b.get() != 'B' || b.get() != 'O' || b.get() != 'I') {
            throw new IOException("bad model.bin");
        }
        int version = b.getInt();
        if (version != 2) throw new IOException("unsupported model.bin version " + version);
        Model m = new Model();
        readFloats(b, m.poleToDatBoi);
        readFloats(b, m.poleWorld0);
        readFloats(b, m.wheelWorld0);
        m.frames = b.getInt();
        m.clipLength = b.getFloat();
        int partCount = b.getInt();
        for (int p = 0; p < partCount; p++) {
            Part part = new Part();
            byte[] name = new byte[b.getInt()];
            b.get(name);
            part.name = new String(name, "UTF-8");
            part.skinned = b.getInt() != 0;
            part.accessory = b.getInt();
            readFloats(b, part.color);
            part.vertexCount = b.getInt();
            int stride = part.skinned ? 14 : 6;
            float[] verts = new float[part.vertexCount * stride];
            for (int v = 0; v < part.vertexCount; v++) {
                for (int k = 0; k < 6; k++) verts[v * stride + k] = b.getFloat();
            }
            if (part.skinned) {
                for (int v = 0; v < part.vertexCount; v++) {
                    for (int k = 0; k < 8; k++) verts[v * stride + 6 + k] = b.getFloat();
                }
            }
            part.vertices = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            part.vertices.put(verts).position(0);
            part.indexCount = b.getInt();
            short[] idx = new short[part.indexCount];
            for (int i = 0; i < idx.length; i++) idx[i] = b.getShort();
            part.indices = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            part.indices.put(idx).position(0);
            if (!part.skinned) {
                part.track = new float[m.frames][16];
                for (int f = 0; f < m.frames; f++) readFloats(b, part.track[f]);
            }
            m.parts.add(part);
        }
        m.boneCount = b.getInt();
        m.bones = new float[m.frames][m.boneCount][16];
        for (int f = 0; f < m.frames; f++) {
            for (int i = 0; i < m.boneCount; i++) readFloats(b, m.bones[f][i]);
        }
        int accCount = b.getInt();
        for (int i = 0; i < accCount; i++) {
            Accessory a = new Accessory();
            a.id = b.getInt();
            readFloats(b, a.start);
            readFloats(b, a.target);
            m.accessories.add(a);
        }
        readFloats(b, m.smokeOrigin);
        readFloats(b, m.progressMarker);
        return m;
    }

    private static void readFloats(ByteBuffer b, float[] dst) {
        for (int i = 0; i < dst.length; i++) dst[i] = b.getFloat();
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
