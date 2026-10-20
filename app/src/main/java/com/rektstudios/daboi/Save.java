package com.rektstudios.daboi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

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

    /** Not in the original: the MLG record, unlocked by going back to -10 m. */
    boolean mlgUnlocked() {
        return prefs.getBoolean("MlgUnlocked", false);
    }

    /** Best forward distance among the runs that went back to -10 m. */
    float mlgRecord() {
        return prefs.getFloat("MlgRange", 0f);
    }

    void storeMlg(float mlgRecord) {
        prefs.edit().putBoolean("MlgUnlocked", true).putFloat("MlgRange", mlgRecord).apply();
    }

    /** Progress file for "export progress" (survives reinstalls and phone changes). */
    String exportJson() {
        try {
            return new JSONObject()
                    .put("game", "datboi")
                    .put("version", 1)
                    .put("record", (double) record())
                    .put("distance", (double) prefs.getFloat("Distance", 0f))
                    .put("mlg_unlocked", mlgUnlocked())
                    .put("mlg_record", (double) mlgRecord())
                    .toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * "import progress": keeps the best of the current and the imported values,
     * so importing an older file never loses progress. Returns the new record.
     */
    float importJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!"datboi".equals(o.optString("game"))) throw new JSONException("not a Dat Boi progress file");
        float record = Math.max(record(), (float) o.getDouble("record"));
        float distance = Math.max(prefs.getFloat("Distance", 0f), (float) o.optDouble("distance", 0));
        store(record, distance);
        if (o.optBoolean("mlg_unlocked", false)) {
            float mlg = (float) o.optDouble("mlg_record", 0);
            storeMlg(mlgUnlocked() ? Math.max(mlgRecord(), mlg) : mlg);
        }
        return record;
    }
}
