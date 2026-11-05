package br.dimensono.bandsono.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import br.dimensono.bandsono.huawei.HuaweiStressParser;
import br.dimensono.bandsono.huawei.HuaweiTrueSleepSequenceDataParser;
import br.dimensono.bandsono.huawei.packets.Workout;

/**
 * Builds the rows of the "detail" tables (what the band sends and the first tables did not keep) from the protocol
 * objects. Pure Java and shared: BandDatabase (app) and BandSink (gateway) both call it, so the app and the Pi store
 * exactly the same rows (there is a parity test), and the history reprocessor of the gateway uses it too.
 * <p>
 * A row is a {@code Map} in the local shape: column name to Long, Double, String or null. JSON columns are kept as
 * JSON text and parsed again by {@link UploadRows#toJson}. Conventions: -1 = the band did not report the field (a real
 * 0 stays 0); non-finite floats become null; times in unix seconds. No health value is ever logged here.
 */
public final class DetailRows {
    private DetailRows() {
    }

    // local table names (the remote names are in UploadRows.SPECS)
    public static final String DEVICE_STATUS = "device_status";
    public static final String NIGHT_DETAIL = "night_detail";
    public static final String STRESS_DETAIL = "stress_detail";
    public static final String WORKOUT_SAMPLE_DETAIL = "workout_sample_detail";
    public static final String WORKOUT_DETAIL = "workout_detail";
    public static final String STEP_TAG = "step_tag";

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_MANUAL = "manual";

    /** Local tables of this class, in upload order. */
    public static final String[] TABLES = {DEVICE_STATUS, NIGHT_DETAIL, STRESS_DETAIL, WORKOUT_SAMPLE_DETAIL, WORKOUT_DETAIL, STEP_TAG};

    /** Columns of each local table, in storage order (the primary key columns come first). */
    public static final Map<String, String[]> COLUMNS = new LinkedHashMap<>();
    /** Primary key columns of each local table. */
    public static final Map<String, String[]> KEYS = new LinkedHashMap<>();
    /** Columns that hold JSON text (the rest are INTEGER, REAL or plain TEXT). */
    public static final Map<String, List<String>> JSON_COLUMNS = new LinkedHashMap<>();
    /** Columns stored as REAL. */
    public static final Map<String, List<String>> REAL_COLUMNS = new LinkedHashMap<>();
    /** Columns stored as TEXT (names, hex and JSON text). */
    public static final Map<String, List<String>> TEXT_COLUMNS = new LinkedHashMap<>();

    private static final String[] NONE = {};

    private static void def(String table, String[] key, String[] other, String[] json, String[] real, String[] text) {
        String[] all = new String[key.length + other.length];
        System.arraycopy(key, 0, all, 0, key.length);
        System.arraycopy(other, 0, all, key.length, other.length);
        COLUMNS.put(table, all);
        KEYS.put(table, key);
        JSON_COLUMNS.put(table, Arrays.asList(json));
        REAL_COLUMNS.put(table, Arrays.asList(real));
        TEXT_COLUMNS.put(table, Arrays.asList(text));
    }

    static {
        def(DEVICE_STATUS, new String[]{"device_address", "ts"}, new String[]{"battery", "hardware_version", "firmware", "model"},
                NONE, NONE, new String[]{"device_address", "hardware_version", "firmware", "model"});
        def(NIGHT_DETAIL, new String[]{"fall_asleep"},
                new String[]{"valid_data", "data_quality", "snore_freq", "turn_over_count", "prepare_sleep_time", "wakeup_feeling",
                        "sleep_version", "hrv_day_to_baseline", "breath_day_to_baseline", "spo2_day_to_baseline",
                        "hr_day_to_baseline", "min_hr_base", "max_hr_base", "min_spo2_base", "max_spo2_base",
                        "min_breath_base", "max_breath_base"},
                NONE, NONE, NONE);
        def(STRESS_DETAIL, new String[]{"start_ts", "source"},
                new String[]{"end_ts", "algorithm", "score_factor", "c_flag", "type", "acc_flag", "ppg_flag", "features"},
                new String[]{"features"}, new String[]{"score_factor"}, new String[]{"source", "features"});
        def(WORKOUT_SAMPLE_DETAIL, new String[]{"workout_start", "ts"},
                new String[]{"swing_angle", "fore_foot_landing", "mid_foot_landing", "back_foot_landing", "eversion_angle",
                        "hang_time", "impact_hang_rate", "impact", "ceiling", "cns", "frequency", "ap", "vo", "gtb", "vr",
                        "unknown_data"},
                NONE, new String[]{"ap", "vo", "gtb", "vr"}, new String[]{"unknown_data"});
        def(WORKOUT_DETAIL, new String[]{"start_ts"},
                new String[]{"end_ts", "hr_zone_type", "alg_type", "longest_streak", "tripped", "recovery_heart_rates",
                        "run_pace_zones", "run_pace_times", "additional_values"},
                new String[]{"recovery_heart_rates", "run_pace_zones", "run_pace_times", "additional_values"}, NONE,
                new String[]{"recovery_heart_rates", "run_pace_zones", "run_pace_times", "additional_values"});
        def(STEP_TAG, new String[]{"bitmap", "tag"}, new String[]{"first_seen", "last_seen", "count", "sample_value"},
                NONE, NONE, NONE);
    }

    /** Key of a row as one string (for the in-memory merge of the gateway). */
    public static String keyOf(String table, Map<String, Object> row) {
        StringBuilder b = new StringBuilder();
        for (String k : KEYS.get(table)) {
            if (b.length() > 0) b.append('/');
            b.append(row.get(k));
        }
        return b.toString();
    }

    private static Map<String, Object> blank(String table) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (String c : COLUMNS.get(table)) r.put(c, null);
        return r;
    }

    private static Long l(long x) {
        return x;
    }

    /** Shortest decimal of a float, as a double (0.1f stays 0.1, not 0.10000000149011612); null when not finite. */
    private static Double f(float x) {
        return Float.isNaN(x) || Float.isInfinite(x) ? null : Double.valueOf(Float.toString(x));
    }

    // ------------------------------------------------------------------ device status

    /**
     * One observation of the band's battery, hardware version, firmware and model. {@code battery} below 0 = unknown.
     * The serial number is not part of it, on purpose.
     */
    public static Map<String, Object> deviceStatus(String address, int battery, String hardwareVersion, String firmware,
                                                   String model, long nowSec) {
        Map<String, Object> r = blank(DEVICE_STATUS);
        r.put("device_address", address == null ? "" : address);
        r.put("ts", l(nowSec));
        r.put("battery", battery >= 0 && battery <= 100 ? l(battery) : null);
        r.put("hardware_version", emptyToNull(hardwareVersion));
        r.put("firmware", emptyToNull(firmware));
        r.put("model", emptyToNull(model));
        return r;
    }

    /** True when {@code next} differs from {@code prev} in battery, hardware, firmware or model (prev may be null). */
    public static boolean deviceStatusChanged(Map<String, Object> prev, Map<String, Object> next) {
        if (prev == null) return true;
        for (String c : new String[]{"battery", "hardware_version", "firmware", "model"})
            if (!Objects.equals(prev.get(c), next.get(c))) return true;
        return false;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ night

    /**
     * Details of a night from the sleep sequence file, after {@code correctSummary}. Same filter as band_nights
     * (fall asleep time known). Null when the night is not stored.
     */
    public static Map<String, Object> nightDetails(HuaweiTrueSleepSequenceDataParser.SleepSummary s) {
        long fall = Math.max(0, s.fallAsleepTime);
        if (fall <= 0) return null;
        Map<String, Object> r = blank(NIGHT_DETAIL);
        r.put("fall_asleep", l(fall));
        r.put("valid_data", l(s.validData));
        r.put("data_quality", l(s.sleepDataQuality));
        r.put("snore_freq", l(s.snoreFreq));
        r.put("turn_over_count", l(s.turnOverCount));
        r.put("prepare_sleep_time", l(s.prepareSleepTime));
        r.put("wakeup_feeling", l(s.wakeUpFeeling));
        r.put("sleep_version", l(s.sleepVersion));
        r.put("hrv_day_to_baseline", l(s.hrvDayToBaseline));
        r.put("breath_day_to_baseline", l(s.breathRateDayToBaseline));
        r.put("spo2_day_to_baseline", l(s.oxygenSaturationDayToBaseline));
        r.put("hr_day_to_baseline", l(s.heartRateDayToBaseline));
        r.put("min_hr_base", l(s.minHeartRateBaseline));
        r.put("max_hr_base", l(s.maxHeartRateBaseline));
        r.put("min_spo2_base", l(s.minOxygenSaturationBaseline));
        r.put("max_spo2_base", l(s.maxOxygenSaturationBaseline));
        r.put("min_breath_base", l(s.minBreathRateBaseline));
        r.put("max_breath_base", l(s.maxBreathRateBaseline));
        return r;
    }

    // ------------------------------------------------------------------ stress

    /** Details of a stress record; same filter as band_stress (start known, score above 0). Null when not stored. */
    public static Map<String, Object> stressDetails(HuaweiStressParser.StressData s, String source) {
        long start = s.startTime / 1000;
        if (start <= 0 || (s.score & 0xff) <= 0) return null;
        Map<String, Object> r = blank(STRESS_DETAIL);
        r.put("start_ts", l(start));
        r.put("source", source);
        r.put("end_ts", l(s.endTime / 1000));
        r.put("algorithm", l(s.algorithm));
        r.put("score_factor", f(s.scoreFactor));
        r.put("c_flag", l(s.cFlag & 0xff));
        r.put("type", l(s.type & 0xff));
        r.put("acc_flag", l(s.accFlag & 0xff));
        r.put("ppg_flag", l(s.ppgFlag & 0xff));
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < s.features.size(); i++) {
            if (i > 0) b.append(',');
            Float x = s.features.get(i);
            Double v = x == null ? null : f(x);
            b.append(v == null ? "null" : v.toString());
        }
        r.put("features", b.append(']').toString());
        return r;
    }

    // ------------------------------------------------------------------ workouts

    /** unknown_data keeps at most this many bytes (the raw workout file has the rest). */
    private static final int MAX_UNKNOWN_BYTES = 256;

    /**
     * Running dynamics of one workout sample. The inner fields exist only when announced by the inner bitmap (ap, vo, gtb
     * and vr start as 0.0 in the protocol object, so their value alone says nothing). Null when the sample has none of
     * these fields.
     */
    public static Map<String, Object> workoutSampleDetails(int workoutStart, Workout.WorkoutData.Response.Data x) {
        if (workoutStart <= 0 || x.timestamp <= 0) return null;
        int b = x.innerBitmap;
        Map<String, Object> r = blank(WORKOUT_SAMPLE_DETAIL);
        r.put("workout_start", l(workoutStart));
        r.put("ts", l(x.timestamp));
        boolean any = false;
        // bits of the inner bitmap: 3 impact, 4 swing angle, 5/6/7 fore/mid/back foot landing, 8 eversion angle,
        // 9 hang time, 10 impact hang rate, 12 ap, 13 vo, 14 gtb, 15 vr, 16 ceiling, 19 cns
        any |= inner(r, "impact", b, 3, x.impact & 0xff);
        any |= inner(r, "swing_angle", b, 4, x.swingAngle);
        any |= inner(r, "fore_foot_landing", b, 5, x.foreFootLanding & 0xff);
        any |= inner(r, "mid_foot_landing", b, 6, x.midFootLanding & 0xff);
        any |= inner(r, "back_foot_landing", b, 7, x.backFootLanding & 0xff);
        any |= inner(r, "eversion_angle", b, 8, x.eversionAngle); // signed: an angle
        any |= inner(r, "hang_time", b, 9, x.hangTime);
        any |= inner(r, "impact_hang_rate", b, 10, x.impactHangRate);
        any |= inner(r, "ceiling", b, 16, x.ceiling & 0xff);
        any |= inner(r, "cns", b, 19, x.cns);
        for (String c : new String[]{"swing_angle", "fore_foot_landing", "mid_foot_landing", "back_foot_landing", "eversion_angle",
                "hang_time", "impact_hang_rate", "impact", "ceiling", "cns"})
            if (r.get(c) == null) r.put(c, l(-1));
        any |= innerReal(r, "ap", b, 12, x.ap);
        any |= innerReal(r, "vo", b, 13, x.vo);
        any |= innerReal(r, "gtb", b, 14, x.gtb);
        any |= innerReal(r, "vr", b, 15, x.vr);
        // frequency comes from the outer bitmap, not the inner one: -1 = not sent
        r.put("frequency", l(x.frequency));
        if (x.frequency != -1) any = true;
        if (x.unknownData != null && x.unknownData.length > 0) {
            r.put("unknown_data", hex(x.unknownData, MAX_UNKNOWN_BYTES));
            any = true;
        }
        return any ? r : null;
    }

    private static boolean inner(Map<String, Object> r, String col, int bitmap, int bit, int value) {
        if ((bitmap & (1 << bit)) == 0) return false;
        r.put(col, l(value));
        return true;
    }

    private static boolean innerReal(Map<String, Object> r, String col, int bitmap, int bit, float value) {
        if ((bitmap & (1 << bit)) == 0) return false;
        r.put(col, f(value));
        return true;
    }

    /** What the workout totals carry besides band_workouts. Null when the workout has no start time. */
    public static Map<String, Object> workoutDetails(Workout.WorkoutTotals.Response t) {
        if (t.startTime <= 0) return null;
        Map<String, Object> r = blank(WORKOUT_DETAIL);
        r.put("start_ts", l(t.startTime));
        r.put("end_ts", l(t.endTime));
        r.put("hr_zone_type", l(t.hrZoneType == -1 ? -1 : t.hrZoneType & 0xff));
        r.put("alg_type", l(t.algType & 0xff));
        r.put("longest_streak", l(t.longestStreak));
        r.put("tripped", l(t.tripped));
        if (t.recoveryHeartRates != null) {
            JSONArray a = new JSONArray();
            for (byte x : t.recoveryHeartRates) a.put(x & 0xff);
            r.put("recovery_heart_rates", a.toString());
        }
        r.put("run_pace_zones", shorts(t.runPaceZone1Min, t.runPaceZone2Min, t.runPaceZone3Min, t.runPaceZone4Min,
                t.runPaceZone5Min, t.runPaceZone5Max));
        r.put("run_pace_times", shorts(t.runPaceZone1Time, t.runPaceZone2Time, t.runPaceZone3Time, t.runPaceZone4Time,
                t.runPaceZone5Time));
        if (t.additionalValues != null && !t.additionalValues.isEmpty()) {
            try {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, String> e : new TreeMap<>(t.additionalValues).entrySet())
                    o.put(e.getKey(), e.getValue());
                r.put("additional_values", o.toString());
            } catch (JSONException ignored) {
                // a value that cannot be written as JSON is not kept here; the raw totals file still has it
            }
        }
        return r;
    }

    /** JSON array of shorts with -1 (not reported) as null; null when none was reported. */
    private static String shorts(short... v) {
        boolean any = false;
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(',');
            if (v[i] == -1) {
                b.append("null");
            } else {
                b.append(v[i]);
                any = true;
            }
        }
        return any ? b.append(']').toString() : null;
    }

    // ------------------------------------------------------------------ step tags

    /**
     * Catalog entry of a minute-record field this code does not decode. {@code existing} is the row already stored (or
     * null): the count adds up, first_seen keeps the earliest, last_seen the latest, and the sample value is the first
     * one ever seen.
     */
    public static Map<String, Object> stepTag(Map<String, Object> existing, int bitmap, int tag, long count, int sampleValue,
                                              long firstSeenSec, long lastSeenSec) {
        Map<String, Object> r = blank(STEP_TAG);
        r.put("bitmap", l(bitmap));
        r.put("tag", l(tag & 0xff));
        if (existing == null) {
            r.put("first_seen", l(firstSeenSec));
            r.put("last_seen", l(lastSeenSec));
            r.put("count", l(count));
            r.put("sample_value", l(sampleValue));
        } else {
            r.put("first_seen", l(Math.min(num(existing.get("first_seen"), firstSeenSec), firstSeenSec)));
            r.put("last_seen", l(Math.max(num(existing.get("last_seen"), lastSeenSec), lastSeenSec)));
            r.put("count", l(num(existing.get("count"), 0) + count));
            r.put("sample_value", existing.get("sample_value") != null ? existing.get("sample_value") : l(sampleValue));
        }
        return r;
    }

    private static long num(Object x, long def) {
        return x instanceof Number ? ((Number) x).longValue() : def;
    }

    // ------------------------------------------------------------------ helpers

    static String hex(byte[] data, int max) {
        int n = Math.min(data.length, max);
        StringBuilder b = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) b.append(String.format("%02x", data[i] & 0xff));
        return b.toString();
    }

    /** Parsed JSON value of a JSON-text column, or JSONObject.NULL. */
    static Object jsonValue(Object text) {
        if (!(text instanceof String)) return JSONObject.NULL;
        String t = ((String) text).trim();
        try {
            if (t.startsWith("[")) return new JSONArray(t);
            if (t.startsWith("{")) return new JSONObject(t);
            return JSONObject.NULL;
        } catch (JSONException e) {
            return JSONObject.NULL;
        }
    }
}
