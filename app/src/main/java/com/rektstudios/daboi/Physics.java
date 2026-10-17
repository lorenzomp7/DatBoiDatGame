package com.rektstudios.daboi;

/**
 * 2D re-implementation of the original Unity/PhysX setup:
 *
 *  PushWheel    Rigidbody mass 1, sphere-ish capsule r=0.5, Y/Z position frozen,
 *               friction material "MaxFriction" (static 0.5, dynamic 1.7) averaged
 *               with the default ground material (0.6/0.6).
 *  balanceThing Rigidbody mass 0.5, box 0.234 x 0.8, angular drag 1, joined to the
 *               wheel centre with a HingeJoint around Z (anchor 1.136 below its centre).
 *  controlWheel AddTorque(forward * axis * 500 * deltaTime) in Update(), which with a
 *               0.02 s fixed step works out to a steady 10 Nm torque on the wheel.
 *
 * Coordinates: x = forward (score direction), y = up. theta = pole tilt, positive
 * towards +x. wheelSpin is positive when the wheel rolls towards +x.
 */
final class Physics {
    static final double G = 9.81;
    static final double R = 0.5;            // wheel radius
    static final double AXLE_Y = 0.5;       // wheel centre height (frozen)
    static final double MW = 1.0;           // wheel mass
    static final double IW = 0.4 * MW * R * R;
    static final double MP = 0.5;           // pole mass
    static final double L = 1.42 * 0.8002857565879822;   // hinge -> pole centre
    static final double POLE_W = 0.23385930061340332;
    static final double POLE_H = 0.8002857565879822;
    static final double IP = MP * (POLE_W * POLE_W + POLE_H * POLE_H) / 12.0;
    static final double MU_STATIC = (0.5 + 0.6) / 2;
    static final double MU_DYNAMIC = (1.7 + 0.6) / 2;
    static final double WHEEL_ANGULAR_DRAG = 0.05;
    static final double POLE_ANGULAR_DRAG = 1.0;
    static final double TORQUE = 500.0 * 0.02;
    static final double FIXED_DT = 0.02;
    private static final int SUBSTEPS = 8;

    /** Pole tilt at which its lowest corner touches the ground. */
    static final double REST_ANGLE = restAngle();

    double x = -0.5130000114440918;   // wheel centre
    double v;
    double wheelSpin;                 // rad/s, + = rolling forward
    double wheelAngle;
    double theta = Math.atan2(0.03934, 0.99923);
    double omega;
    private double accum;

    /** Advances the simulation by dt seconds of game time. */
    void advance(double dt, double input) {
        accum += dt;
        while (accum >= FIXED_DT) {
            accum -= FIXED_DT;
            fixedStep(input);
        }
    }

    double poleCenterX() { return x + L * Math.sin(theta); }

    double poleCenterY() { return AXLE_Y + L * Math.cos(theta); }

    private void fixedStep(double input) {
        // Unity: positive Horizontal spins the wheel around +Z, i.e. it rolls towards -X.
        double tau = -input * TORQUE;
        double h = FIXED_DT / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            substep(h, tau);
        }
        // Rigidbody angular drag (PhysX applies it once per simulation step)
        wheelSpin *= Math.max(0, 1 - WHEEL_ANGULAR_DRAG * FIXED_DT);
        omega *= Math.max(0, 1 - POLE_ANGULAR_DRAG * FIXED_DT);
    }

    private void substep(double h, double tau) {
        double s = Math.sin(theta), c = Math.cos(theta);
        double mpl = MP * L;
        // generalized coordinates (x, theta); the wheel spin is its own degree of freedom
        double m11 = MW + MP, m12 = mpl * c, m22 = MP * L * L + IP;
        double det = m11 * m22 - m12 * m12;
        double i11 = m22 / det, i12 = -m12 / det, i22 = m11 / det;
        double qx = mpl * s * omega * omega;
        double qt = MP * G * L * s;
        double xdd = i11 * qx + i12 * qt;
        double thdd = i12 * qx + i22 * qt;
        v += xdd * h;
        omega += thdd * h;
        wheelSpin += tau / IW * h;

        // Contact friction as a velocity-level impulse (like the PhysX solver): remove the
        // slip at the contact point, limited by mu * N. PhysX clamps an impulse that breaks
        // static friction to the dynamic limit, and this material has dynamic > static.
        double normal = Math.max(0, (MW + MP) * G - mpl * (s * thdd + c * omega * omega));
        double slip = v - R * wheelSpin;
        double impulse = -slip / (i11 + R * R / IW);
        double needed = Math.abs(impulse);
        if (needed > MU_STATIC * normal * h) {
            impulse = Math.signum(impulse) * Math.min(needed, MU_DYNAMIC * normal * h);
        }
        v += i11 * impulse;
        omega += i12 * impulse;
        wheelSpin -= R * impulse / IW;

        x += v * h;
        wheelAngle += wheelSpin * h;
        theta += omega * h;

        // the pole box lands on the ground plane
        if (theta > REST_ANGLE) {
            theta = REST_ANGLE;
            if (omega > 0) omega = 0;
        } else if (theta < -REST_ANGLE) {
            theta = -REST_ANGLE;
            if (omega < 0) omega = 0;
        }
    }

    private static double lowestCorner(double th) {
        double s = Math.sin(th), c = Math.cos(th);
        double lo = Double.MAX_VALUE;
        double[] axial = {L - POLE_H / 2, L + POLE_H / 2};
        double[] lateral = {-POLE_W / 2, POLE_W / 2};
        for (double a : axial) {
            for (double b : lateral) {
                lo = Math.min(lo, AXLE_Y + a * c - b * s);
            }
        }
        return lo;
    }

    private static double restAngle() {
        double lo = Math.PI / 2, hi = Math.PI;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (lowestCorner(mid) > 0) lo = mid; else hi = mid;
        }
        return lo;
    }
}
