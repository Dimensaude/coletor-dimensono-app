package br.dimensono.bandsono.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import br.dimensono.bandsono.Hrv;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

/**
 * Pure-Java description of what is uploaded: tables, conflict keys, batch sizes and the JSON of each
 * row. Shared by the Android uploader (SupabaseUploader) and by the Raspberry Pi gateway
 * (gateway-pi), so both produce exactly the same rows. No Android classes here.
 */
public final class UploadRows {
    private UploadRows() {
    }

    /** The server takes 20 MB per request; raw files above ~15 MB of base64 are never sent. */
    public static final long MAX_RAW_BASE64 = 15L * 1024 * 1024;
    public static final long MAX_RAW_BYTES = MAX_RAW_BASE64 / 4 * 3;

    /** Local table → remote table, conflict target and rows per request. */
    public static final class Spec {
        public final String local, remote, onConflict;
        public final int batch;
        /**
         * Tables added after the first schema: a server error on them (e.g. the table was not
         * created yet because schema.sql was not re-run) skips the table instead of stopping the
         * whole upload; its rows stay pending for the next run.
         */
        public final boolean optional;

        Spec(String local, String remote, String onConflict, int batch) {
            this(local, remote, onConflict, batch, false);
        }

        Spec(String local, String remote, String onConflict, int batch, boolean optional) {
            this.local = local;
            this.remote = remote;
            this.onConflict = onConflict;
            this.batch = batch;
            this.optional = optional;
        }
    }

    public static final Spec[] SPECS = {
            new Spec("night", "band_nights", "user_id,fall_asleep", 200),
            new Spec("sleep", "band_sleep_segments", "user_id,start_ts,source", 500),
            new Spec("minute", "band_minutes", "user_id,ts", 1000),
            new Spec("stress", "band_stress", "user_id,start_ts", 500),
            new Spec("dict", "band_dict", "user_id,kind,ts_ms", 1000),
            new Spec("rr_test", "band_rr_tests", "user_id,ts_ms", 50),
            new Spec("sync_log", "band_sync_log", "user_id,ts", 200),
            new Spec("workout", "band_workouts", "user_id,start_ts", 200, true),
            new Spec("workout_sample", "band_workout_samples", "user_id,workout_start,ts", 1000, true),
            new Spec("sleep_stage", "band_sleep_stages", "user_id,start_ts", 1000, true),
            // Detail tables of the "captura máxima" (server tables created with the server release that has them)
            new Spec("device_status", "band_device_status", "user_id,device_address,ts", 200, true),
            new Spec("night_detail", "band_night_details", "user_id,fall_asleep", 200, true),
            new Spec("stress_detail", "band_stress_details", "user_id,start_ts,source", 500, true),
            new Spec("workout_detail", "band_workout_details", "user_id,start_ts", 200, true),
            new Spec("workout_sample_detail", "band_workout_sample_details", "user_id,workout_start,ts", 1000, true),
            new Spec("step_tag", "band_unknown_step_tags", "user_id,bitmap,tag", 200, true),
            // Always last: raw files are big and the least important; a bad one must not hold the data tables back
            new Spec("raw_file", "band_raw_files", "user_id,sha256", 1),
    };

    /**
     * True when the server rejected the raw file's payload itself, so retrying cannot help: 413
     * "payload_too_large", 422, or 400 with a data error code (not-null, bad text format, bad JSON).
     * Config errors (PGRST204/205, 42P10, 42703...) are not here: they stop the table instead.
     */
    /** 410 participant_deleted: the server erased this participant (LGPD); the app must stop uploading. */
    public static boolean isParticipantDeleted(int status, String code) {
        return status == 410 && "participant_deleted".equals(code);
    }

    public static boolean isPermanentRejection(int status, String code) {
        if (status == 413)
            return "payload_too_large".equals(code);
        if (status == 422)
            return true;
        return status == 400 && ("23502".equals(code) || "22P02".equals(code) || "PGRST102".equals(code));
    }

    public static JSONObject deviceJson(String userId, String address, String name, String model, String firmware,
                                 String participant, String appVersion, String platform, long nowMs) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("user_id", userId);
        o.put("device_address", nz(address));
        o.put("device_name", nz(name));
        o.put("model", nz(model));
        o.put("firmware", nz(firmware));
        o.put("participant", nz(participant).trim());
        o.put("app_version", nz(appVersion));
        o.put("platform", nz(platform));
        o.put("last_seen_at", iso(nowMs));
        return o;
    }

    public static JSONObject toJson(String table, Map<String, Object> r, String userId) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("user_id", userId);
        switch (table) {
            case "minute":
                o.put("ts", v(r.get("ts")));
                o.put("steps", v(r.get("steps")));
                o.put("calories", v(r.get("calories")));
                o.put("distance", v(r.get("distance")));
                o.put("heart_rate", v(r.get("hr")));
                o.put("spo2", v(r.get("spo2")));
                o.put("resting_hr", v(r.get("resting_hr")));
                break;
            case "sleep": {
                long source = num(r.get("source"));
                long type = num(r.get("type"));
                o.put("start_ts", v(r.get("start_ts")));
                o.put("end_ts", v(r.get("end_ts")));
                o.put("type", v(r.get("type")));
                o.put("source", source);
                o.put("origin", sleepOrigin((int) source, (int) type));
                break;
            }
            case "dict":
                o.put("kind", v(r.get("kind")));
                o.put("ts_ms", v(r.get("ts")));
                o.put("value", v(r.get("value")));
                break;
            case "rr_test":
                o.put("ts_ms", v(r.get("ts")));
                o.put("duration_s", v(r.get("duration_s")));
                o.put("rri", intArray(r.get("rri")));
                o.put("sqi", intArray(r.get("sqi")));
                o.put("n", v(r.get("n")));
                o.put("rmssd", v(r.get("rmssd")));
                o.put("sdnn", v(r.get("sdnn")));
                o.put("pnn50", v(r.get("pnn50")));
                o.put("mean_hr", v(r.get("mean_hr")));
                o.put("stress", v(r.get("stress")));
                break;
            case "sync_log":
                o.put("ts", v(r.get("ts")));
                o.put("ok", num(r.get("ok")) != 0);
                o.put("summary", v(r.get("summary")));
                break;
            case "raw_file": {
                Object data = r.get("data");
                o.put("sha256", v(r.get("sha256")));
                o.put("kind", v(r.get("kind")));
                o.put("received_at_ms", v(r.get("received_at")));
                o.put("start_ts", v(r.get("start_ts")));
                o.put("end_ts", v(r.get("end_ts")));
                o.put("size", v(r.get("size")));
                o.put("data_base64", data instanceof byte[] ? Base64.getEncoder().encodeToString((byte[]) data) : JSONObject.NULL);
                break;
            }
            case DetailRows.DEVICE_STATUS:
            case DetailRows.NIGHT_DETAIL:
            case DetailRows.STRESS_DETAIL:
            case DetailRows.WORKOUT_SAMPLE_DETAIL:
            case DetailRows.WORKOUT_DETAIL:
            case DetailRows.STEP_TAG: {
                // only the table's own columns (a local-only column never leaves the device); JSON text goes as JSON
                java.util.List<String> json = DetailRows.JSON_COLUMNS.get(table);
                for (String c : DetailRows.COLUMNS.get(table))
                    o.put(c, json.contains(c) ? DetailRows.jsonValue(r.get(c)) : v(r.get(c)));
                break;
            }
            default:
                // night, stress, workout, workout_sample, sleep_stage: same column names locally and remotely
                for (Map.Entry<String, Object> e : r.entrySet())
                    o.put(e.getKey(), v(e.getValue()));
                break;
        }
        return o;
    }

    private static Object v(Object x) {
        if (x == null)
            return JSONObject.NULL;
        if (x instanceof Double) {
            double d = (Double) x;
            if (Double.isNaN(d) || Double.isInfinite(d))
                return JSONObject.NULL;
        }
        if (x instanceof Float) {
            float f = (Float) x;
            if (Float.isNaN(f) || Float.isInfinite(f))
                return JSONObject.NULL;
        }
        if (x instanceof byte[])
            return Base64.getEncoder().encodeToString((byte[]) x);
        return x;
    }

    private static long num(Object x) {
        return x instanceof Number ? ((Number) x).longValue() : 0;
    }

    private static Object intArray(Object x) {
        if (!(x instanceof String))
            return JSONObject.NULL;
        JSONArray a = new JSONArray();
        for (int i : Hrv.split((String) x))
            a.put(i);
        return a;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }


    /** Label of a stored sleep segment. Only the "trusleep*" ones are shown in the sonogram. */
    public static String sleepOrigin(int source, int type) {
        if (source == HuaweiSupportProvider.SOURCE_STAGES)
            return type == HuaweiSupportProvider.SLEEP_TYPE_NAP ? "trusleep_cochilo" : "trusleep_estagios";
        if (source == HuaweiSupportProvider.SOURCE_TRUSLEEP) return "trusleep";
        if (source == HuaweiSupportProvider.SOURCE_FITNESS) return "repouso_historico";
        return "outro_" + source;
    }
}
