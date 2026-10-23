package com.rektstudios.daboi;

/**
 * Build flavour. The private admin build (tools/build_apk.sh with ADMIN=1, installed
 * next to the game as com.rektstudios.daboi.admin) turns this on: it adds review tools
 * and lets everything go on after death.
 */
final class Flavor {
    static final boolean ADMIN = false;

    private Flavor() {
    }
}
