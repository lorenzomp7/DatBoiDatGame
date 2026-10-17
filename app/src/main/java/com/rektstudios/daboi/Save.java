package com.rektstudios.daboi;

import android.content.Context;
import android.content.SharedPreferences;

/** Replaces the BinaryFormatter save.xml / save_1.xml files. */
final class Save {
    private final SharedPreferences prefs;

    Save(Context context) {
        prefs = context.getSharedPreferences("datboi", Context.MODE_PRIVATE);
    }

    /** SavingScript.DatBoiRange */
    float record() {
        return prefs.getFloat("DatBoiRange", 0f);
    }

    /** SavingScriptUpdate.Distance (the original deserialised it as an int) */
    float distance() {
        return (int) prefs.getFloat("Distance", 0f);
    }

    void store(float record, float distance) {
        prefs.edit().putFloat("DatBoiRange", record).putFloat("Distance", distance).apply();
    }
}
