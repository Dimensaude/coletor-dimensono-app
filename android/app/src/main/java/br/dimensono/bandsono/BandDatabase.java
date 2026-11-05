package br.dimensono.bandsono;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.dimensono.bandsono.huawei.HuaweiStressParser;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.HuaweiTrueSleepSequenceDataParser;
import br.dimensono.bandsono.huawei.packets.Workout;
import br.dimensono.bandsono.sync.DetailRows;

/**
 * Local storage of everything downloaded from the band.
 * - minute: one row per minute sample (steps, calories, distance, heart rate, SpO2, resting HR)
 * - sleep: sleep segments as reported by the band (start/end in unix seconds)
 * - night: the band's own summary of each night (score, latency, efficiency, HRV, breathing...)
 * - stress: automatic stress scores
 * - dict: values from the band's data dictionary (kind "hrv" = RMSSD ms, "temp" = skin °C), ts in ms
 * - rr_test: on-demand RR interval recordings with the HRV metrics computed by the app
 * - raw_file: bytes of the files downloaded from the band (sleep, RRI, workouts), kept for the raw upload
 * - workout: one row per workout recorded on the band (totals: type, duration, calories, HR peaks...)
 * - workout_sample: per-sample workout data (heart rate, speed, cadence...), keyed by workout start
 * - sleep_stage: hypnogram runs (1 light, 2 REM, 3 deep, 4 awake, 5 nap) from the band's sleep file
 * <p>
 * Every table has an {@code uploaded} column for the Supabase upload: 0 = pending,
 * 2 = being sent, 1 = sent, 3 = permanently refused by the server (not retried until
 * {@link #markAllPending()} or until the row is replaced). Re-inserting a row (CONFLICT_REPLACE) sets it back to 0, so
 * changed rows are sent again. Queries repeat "uploaded <> 1" so SQLite uses the partial index.
 */
public class BandDatabase extends SQLiteOpenHelper {
    private static final String NAME = "bandsono.db";
    private static final int VERSION = 6;

    /** {@code uploaded} value of a row the server refused for good (e.g. 413 payload too large). */
    public static final int UPLOAD_REFUSED = 3;
    /** Raw file BLOBs are read in pieces this size: a CursorWindow holds about 2 MB per cursor. */
    private static final int BLOB_CHUNK = 512 * 1024;

    /** Tables uploaded to Supabase, in upload order. */
    public static final String[] UPLOAD_TABLES = {"night", "sleep", "minute", "stress", "dict", "rr_test", "workout", "workout_sample",
            "sleep_stage", "sync_log", DetailRows.DEVICE_STATUS, DetailRows.NIGHT_DETAIL, DetailRows.STRESS_DETAIL,
            DetailRows.WORKOUT_DETAIL, DetailRows.WORKOUT_SAMPLE_DETAIL, DetailRows.STEP_TAG, "raw_file"};
    /** Detail tables whose sent rows are dropped after {@link #PRUNE_SENT_AFTER_MS}, with the (unix seconds) column that dates them. */
    private static final String[][] PRUNABLE = {{DetailRows.DEVICE_STATUS, "ts"}, {DetailRows.NIGHT_DETAIL, "fall_asleep"},
            {DetailRows.STRESS_DETAIL, "start_ts"}, {DetailRows.WORKOUT_DETAIL, "start_ts"}, {DetailRows.WORKOUT_SAMPLE_DETAIL, "ts"}};
    private static final long PRUNE_SENT_AFTER_MS = 3 * 86400_000L;

    /** Thrown to the uploader while a sync is writing (it retries later instead of waiting). */
    public static class BusyException extends RuntimeException {
        BusyException() {
            super("banco ocupado com a sincronização");
        }
    }
    private static BandDatabase instance;

    public static synchronized BandDatabase get(Context context) {
        if (instance == null)
            instance = new BandDatabase(context.getApplicationContext());
        return instance;
    }

    private BandDatabase(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        run(db, LocalSchema.fresh());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(LocalSchema.upgradeToV2Column());
            run(db, LocalSchema.v2());
        }
        if (oldVersion < 3) run(db, LocalSchema.v3());
        if (oldVersion < 4) run(db, LocalSchema.v4());
        if (oldVersion < 5) run(db, LocalSchema.v5());
        if (oldVersion < 6) run(db, LocalSchema.v6());
    }

    private static void run(SQLiteDatabase db, java.util.List<String> statements) {
        for (String sql : statements)
            db.execSQL(sql);
    }

    private SQLiteDatabase pendingTx;

    /** Group many inserts of one sync in a single transaction. */
    public synchronized void beginBatch() {
        if (pendingTx == null) {
            pendingTx = getWritableDatabase();
            pendingTx.beginTransactionNonExclusive();
        }
    }

    public synchronized boolean batchOpen() {
        return pendingTx != null;
    }

    public synchronized void endBatch() {
        if (pendingTx != null) {
            try {
                pendingTx.setTransactionSuccessful();
            } finally {
                pendingTx.endTransaction();
                pendingTx = null;
            }
        }
    }

    public synchronized void insertMinute(int ts, int steps, int calories, int distance, int hr, int spo2, int restingHr) {
        SQLiteDatabase db = getWritableDatabase();
        // Merge with an existing row: the band may send the same minute more than once
        Integer oldHr = null, oldSpo = null;
        try (Cursor c = db.rawQuery("SELECT hr, spo2 FROM minute WHERE ts=?", new String[]{String.valueOf(ts)})) {
            if (c.moveToFirst()) {
                if (!c.isNull(0)) oldHr = c.getInt(0);
                if (!c.isNull(1)) oldSpo = c.getInt(1);
            }
        }
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("steps", Math.max(0, steps));
        v.put("calories", Math.max(0, calories));
        v.put("distance", Math.max(0, distance));
        boolean hrValid = hr > 20 && hr < 250;
        boolean spoValid = spo2 >= 50 && spo2 <= 100;
        if (hrValid) v.put("hr", hr);
        else if (oldHr != null) v.put("hr", oldHr);
        else v.putNull("hr");
        if (spoValid) v.put("spo2", spo2);
        else if (oldSpo != null) v.put("spo2", oldSpo);
        else v.putNull("spo2");
        if (restingHr > 20 && restingHr < 200) v.put("resting_hr", restingHr);
        db.insertWithOnConflict("minute", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void insertSleep(int start, int end, int type, int source) {
        if (end <= start) return;
        ContentValues v = new ContentValues();
        v.put("start_ts", start);
        v.put("end_ts", end);
        v.put("type", type);
        v.put("source", source);
        getWritableDatabase().insertWithOnConflict("sleep", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void insertNight(long fallAsleep, long bedTime, long rising, long wakeup, int score, int latency,
                                         int efficiency, int wakeCount, int deepPart, int minHr, int maxHr, int avgHr,
                                         double minSpo2, double maxSpo2, int avgSpo2, double minBreath, double maxBreath,
                                         int avgBreath, int avgHrv, int minHrvBase, int maxHrvBase, int rdi) {
        if (fallAsleep <= 0) return;
        ContentValues v = new ContentValues();
        v.put("fall_asleep", fallAsleep);
        v.put("bed_time", bedTime);
        v.put("rising", rising);
        v.put("wakeup", wakeup);
        v.put("score", score);
        v.put("latency", latency);
        v.put("efficiency", efficiency);
        v.put("wake_count", wakeCount);
        v.put("deep_part", deepPart);
        v.put("min_hr", minHr);
        v.put("max_hr", maxHr);
        v.put("avg_hr", avgHr);
        v.put("min_spo2", minSpo2);
        v.put("max_spo2", maxSpo2);
        v.put("avg_spo2", avgSpo2);
        v.put("min_breath", minBreath);
        v.put("max_breath", maxBreath);
        v.put("avg_breath", avgBreath);
        v.put("avg_hrv", avgHrv);
        v.put("min_hrv_base", minHrvBase);
        v.put("max_hrv_base", maxHrvBase);
        v.put("rdi", rdi);
        getWritableDatabase().insertWithOnConflict("night", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void insertStress(int start, int end, int score, int level) {
        if (start <= 0 || score <= 0) return;
        ContentValues v = new ContentValues();
        v.put("start_ts", start);
        v.put("end_ts", end);
        v.put("score", score);
        v.put("level", level);
        getWritableDatabase().insertWithOnConflict("stress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void insertDict(String kind, long tsMs, double value) {
        ContentValues v = new ContentValues();
        v.put("kind", kind);
        v.put("ts", tsMs);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict("dict", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized long lastDictTs(String kind) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(ts) FROM dict WHERE kind=?", new String[]{kind})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        }
    }

    // ------------------------------------------------------------------ sleep stages

    public synchronized void insertSleepStage(int start, int end, int stage) {
        if (start <= 0 || end <= start) return;
        ContentValues v = new ContentValues();
        v.put("start_ts", start);
        v.put("end_ts", end);
        v.put("stage", stage);
        getWritableDatabase().insertWithOnConflict("sleep_stage", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Hypnogram runs overlapping [from, to): parallel int arrays. */
    public synchronized Map<String, Object> sleepStages(long from, long to) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ts, end_ts, stage FROM sleep_stage WHERE end_ts > ? AND start_ts < ? ORDER BY start_ts",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            int n = c.getCount();
            int[] start = new int[n], end = new int[n], stage = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                start[i] = c.getInt(0);
                end[i] = c.getInt(1);
                stage[i] = c.getInt(2);
                i++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("start", start);
            m.put("end", end);
            m.put("stage", stage);
            return m;
        }
    }

    /** All samples of one workout, oldest first: parallel arrays (-1 = not reported). */
    public synchronized Map<String, Object> workoutSamples(long workoutStart) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, heart_rate, speed, cadence, step_rate, calories FROM workout_sample WHERE workout_start = ? ORDER BY ts",
                new String[]{String.valueOf(workoutStart)})) {
            int n = c.getCount();
            int[] ts = new int[n], hr = new int[n], speed = new int[n], cadence = new int[n], stepRate = new int[n], cal = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                ts[i] = c.getInt(0);
                hr[i] = c.isNull(1) ? -1 : c.getInt(1);
                speed[i] = c.isNull(2) ? -1 : c.getInt(2);
                cadence[i] = c.isNull(3) ? -1 : c.getInt(3);
                stepRate[i] = c.isNull(4) ? -1 : c.getInt(4);
                cal[i] = c.isNull(5) ? -1 : c.getInt(5);
                i++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("ts", ts);
            m.put("hr", hr);
            m.put("speed", speed);
            m.put("cadence", cadence);
            m.put("stepRate", stepRate);
            m.put("calories", cal);
            return m;
        }
    }

    // ------------------------------------------------------------------ workouts

    public synchronized void insertWorkout(HuaweiSupportProvider.WorkoutSummary w) {
        if (w.startTs <= 0) return;
        ContentValues v = new ContentValues();
        v.put("start_ts", w.startTs);
        v.put("end_ts", w.endTs);
        v.put("number", w.number);
        putPositive(v, "type", w.type);
        putPositive(v, "status", w.status);
        putPositive(v, "calories", w.calories);
        putPositive(v, "distance", w.distance);
        putPositive(v, "steps", w.steps);
        putPositive(v, "total_time", w.totalTime);
        putPositive(v, "duration", w.duration);
        putPositive(v, "min_hr_peak", w.minHrPeak);
        putPositive(v, "max_hr_peak", w.maxHrPeak);
        putPositive(v, "load", w.load);
        putPositive(v, "aerobic_effect", w.aerobicEffect);
        putPositive(v, "anaerobic_effect", w.anaerobicEffect);
        putPositive(v, "recovery_time", w.recoveryTime);
        putPositive(v, "max_met", w.maxMet);
        putPositive(v, "training_points", w.trainingPoints);
        if (w.elevationGain != null) v.put("elevation_gain", w.elevationGain);
        if (w.elevationLoss != null) v.put("elevation_loss", w.elevationLoss);
        if (w.maxAltitude != null) v.put("max_altitude", w.maxAltitude);
        if (w.minAltitude != null) v.put("min_altitude", w.minAltitude);
        putPositive(v, "strokes", w.strokes);
        putPositive(v, "avg_stroke_rate", w.avgStrokeRate);
        putPositive(v, "laps", w.laps);
        putPositive(v, "avg_swolf", w.avgSwolf);
        putPositive(v, "pool_length", w.poolLength);
        putPositive(v, "swim_type", w.swimType);
        v.put("data_count", w.dataCount);
        v.put("pace_count", w.paceCount);
        getWritableDatabase().insertWithOnConflict("workout", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void insertWorkoutSample(int workoutStart, HuaweiSupportProvider.WorkoutSample s) {
        if (workoutStart <= 0 || s.ts <= 0) return;
        ContentValues v = new ContentValues();
        v.put("workout_start", workoutStart);
        v.put("ts", s.ts);
        putPositive(v, "heart_rate", s.heartRate);
        putPositive(v, "speed", s.speed);
        putPositive(v, "step_rate", s.stepRate);
        putPositive(v, "cadence", s.cadence);
        putPositive(v, "step_length", s.stepLength);
        putPositive(v, "ground_contact_time", s.groundContactTime);
        putPositive(v, "calories", s.calories);
        putPositive(v, "spo2", s.spo2);
        putPositive(v, "temp", s.temp);
        putPositive(v, "cycling_power", s.cyclingPower);
        putPositive(v, "ride_cadence", s.rideCadence);
        putPositive(v, "swolf", s.swolf);
        putPositive(v, "stroke_rate", s.strokeRate);
        if (s.altitude != null) v.put("altitude", s.altitude);
        getWritableDatabase().insertWithOnConflict("workout_sample", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ------------------------------------------------------------------ detail tables (captura máxima)

    /** Stores a row built by {@link DetailRows} (same rows as the gateway); a repeated key replaces the old row. */
    private void replaceRow(String table, Map<String, Object> row) {
        ContentValues v = new ContentValues();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object x = e.getValue();
            if (x == null) v.putNull(e.getKey());
            else if (x instanceof Long) v.put(e.getKey(), (Long) x);
            else if (x instanceof Double) v.put(e.getKey(), (Double) x);
            else v.put(e.getKey(), String.valueOf(x));
        }
        getWritableDatabase().insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void insertNightDetails(HuaweiTrueSleepSequenceDataParser.SleepSummary s) {
        Map<String, Object> r = DetailRows.nightDetails(s);
        if (r != null) replaceRow(DetailRows.NIGHT_DETAIL, r);
    }

    /** {@code source}: {@link DetailRows#SOURCE_AUTO} (the band's stress file) or {@link DetailRows#SOURCE_MANUAL} (on-demand HRV test). */
    public synchronized void insertStressDetails(HuaweiStressParser.StressData s, String source) {
        Map<String, Object> r = DetailRows.stressDetails(s, source);
        if (r != null) replaceRow(DetailRows.STRESS_DETAIL, r);
    }

    public synchronized void insertWorkoutDetails(Workout.WorkoutTotals.Response t) {
        Map<String, Object> r = DetailRows.workoutDetails(t);
        if (r != null) replaceRow(DetailRows.WORKOUT_DETAIL, r);
    }

    public synchronized void insertWorkoutSampleDetails(int workoutStart, List<Workout.WorkoutData.Response.Data> samples) {
        for (Workout.WorkoutData.Response.Data x : samples) {
            Map<String, Object> r = DetailRows.workoutSampleDetails(workoutStart, x);
            if (r != null) replaceRow(DetailRows.WORKOUT_SAMPLE_DETAIL, r);
        }
    }

    /** Adds this sync's undecoded minute-record fields to the catalog. */
    public synchronized void addUnknownStepTags(List<br.dimensono.bandsono.huawei.UnknownStepTag> tags) {
        SQLiteDatabase db = getWritableDatabase();
        for (br.dimensono.bandsono.huawei.UnknownStepTag t : tags) {
            Map<String, Object> existing = null;
            try (Cursor c = db.rawQuery("SELECT first_seen, last_seen, count, sample_value FROM step_tag WHERE bitmap = ? AND tag = ?",
                    new String[]{String.valueOf(t.bitmap), String.valueOf(t.tag)})) {
                if (c.moveToFirst()) {
                    existing = new HashMap<>();
                    existing.put("first_seen", c.isNull(0) ? null : c.getLong(0));
                    existing.put("last_seen", c.isNull(1) ? null : c.getLong(1));
                    existing.put("count", c.isNull(2) ? null : c.getLong(2));
                    existing.put("sample_value", c.isNull(3) ? null : c.getLong(3));
                }
            }
            replaceRow(DetailRows.STEP_TAG, DetailRows.stepTag(existing, t.bitmap, t.tag, t.count, t.sampleValue, t.firstTs, t.lastTs));
        }
    }

    private final Map<String, Map<String, Object>> lastDeviceStatus = new HashMap<>();

    /** Records the band's battery / hardware / firmware / model, but only when one of them changed since the last row. */
    public synchronized void noteDeviceStatus(String address, int battery, String hardwareVersion, String firmware, String model, long nowSec) {
        Map<String, Object> next = DetailRows.deviceStatus(address, battery, hardwareVersion, firmware, model, nowSec);
        if (next.get("battery") == null && next.get("firmware") == null && next.get("model") == null && next.get("hardware_version") == null)
            return; // nothing known yet
        String addr = (String) next.get("device_address");
        Map<String, Object> prev = lastDeviceStatus.get(addr);
        if (prev == null) {
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT battery, hardware_version, firmware, model FROM device_status WHERE device_address = ? ORDER BY ts DESC LIMIT 1",
                    new String[]{addr})) {
                if (c.moveToFirst()) {
                    prev = new HashMap<>();
                    prev.put("battery", c.isNull(0) ? null : c.getLong(0));
                    prev.put("hardware_version", c.getString(1));
                    prev.put("firmware", c.getString(2));
                    prev.put("model", c.getString(3));
                }
            }
        }
        if (!DetailRows.deviceStatusChanged(prev, next)) {
            lastDeviceStatus.put(addr, next);
            return;
        }
        lastDeviceStatus.put(addr, next);
        replaceRow(DetailRows.DEVICE_STATUS, next);
    }

    /** -1 means "not reported by the band": leave the column NULL. */
    private static void putPositive(ContentValues v, String col, int value) {
        if (value >= 0) v.put(col, value);
    }

    public synchronized int lastWorkoutStart() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(start_ts) FROM workout", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : 0;
        }
    }

    /** Most recent workouts (newest first) with their sample count and mean heart rate. */
    public synchronized List<Map<String, Object>> workouts(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT w.start_ts, w.end_ts, w.type, w.duration, w.calories, w.distance, w.steps, w.min_hr_peak, w.max_hr_peak, "
                        + "(SELECT COUNT(*) FROM workout_sample s WHERE s.workout_start = w.start_ts), "
                        + "(SELECT AVG(heart_rate) FROM workout_sample s WHERE s.workout_start = w.start_ts AND heart_rate > 0) "
                        + "FROM workout w ORDER BY w.start_ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            String[] names = {"start", "end", "type", "duration", "calories", "distance", "steps", "minHr", "maxHr", "samples", "avgHr"};
            while (c.moveToNext()) {
                Map<String, Object> m = new HashMap<>();
                for (int i = 0; i < names.length; i++) {
                    if (c.isNull(i)) continue;
                    if (c.getType(i) == Cursor.FIELD_TYPE_FLOAT) m.put(names[i], c.getDouble(i));
                    else m.put(names[i], c.getLong(i));
                }
                out.add(m);
            }
        }
        return out;
    }

    /** Latest value, its time and the count of every dictionary kind (hrv, temp, emotion..., apnea). */
    public synchronized Map<String, Object> dictOverview() {
        Map<String, Object> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT d.kind, COUNT(*), MAX(d.ts), (SELECT value FROM dict x WHERE x.kind = d.kind ORDER BY ts DESC LIMIT 1) "
                        + "FROM dict d GROUP BY d.kind", null)) {
            while (c.moveToNext()) {
                Map<String, Object> m = new HashMap<>();
                m.put("count", c.getInt(1));
                m.put("lastTs", c.getLong(2));
                m.put("last", c.getDouble(3));
                out.put(c.getString(0), m);
            }
        }
        return out;
    }

    /**
     * Everything the "Dados coletados" screen shows in one call: counts per table, the latest
     * value of each measurement, today's totals (from {@code dayStart}, unix seconds) and the
     * latest night, workouts and dictionary values.
     */
    public synchronized Map<String, Object> overview(long dayStart) {
        Map<String, Object> out = new HashMap<>();
        out.put("stats", stats());
        out.put("dict", dictOverview());
        out.put("workouts", workouts(5));
        SQLiteDatabase db = getReadableDatabase();
        String[][] latest = {
                {"hr", "SELECT ts, hr FROM minute WHERE hr > 0 ORDER BY ts DESC LIMIT 1"},
                {"spo2", "SELECT ts, spo2 FROM minute WHERE spo2 > 0 ORDER BY ts DESC LIMIT 1"},
                {"restingHr", "SELECT ts, resting_hr FROM minute WHERE resting_hr > 0 ORDER BY ts DESC LIMIT 1"},
                {"stress", "SELECT start_ts, score FROM stress ORDER BY start_ts DESC LIMIT 1"},
        };
        for (String[] q : latest) {
            try (Cursor c = db.rawQuery(q[1], null)) {
                if (c.moveToFirst()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("ts", c.getLong(0));
                    m.put("value", c.getLong(1));
                    out.put(q[0], m);
                }
            }
        }
        try (Cursor c = db.rawQuery("SELECT SUM(steps), SUM(calories), SUM(distance), MIN(NULLIF(hr, 0)), MAX(hr), "
                + "AVG(NULLIF(hr, 0)), MIN(NULLIF(spo2, 0)), COUNT(*) FROM minute WHERE ts >= ?", new String[]{String.valueOf(dayStart)})) {
            if (c.moveToFirst()) {
                Map<String, Object> m = new HashMap<>();
                String[] names = {"steps", "calories", "distance", "minHr", "maxHr", "avgHr", "minSpo2", "minutes"};
                for (int i = 0; i < names.length; i++) {
                    if (c.isNull(i)) continue;
                    m.put(names[i], c.getType(i) == Cursor.FIELD_TYPE_FLOAT ? (Object) c.getDouble(i) : (Object) c.getLong(i));
                }
                out.put("today", m);
            }
        }
        try (Cursor c = db.rawQuery("SELECT MAX(fall_asleep) FROM night", null)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                long last = c.getLong(0);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) nights(last, last + 1).get("rows");
                if (!rows.isEmpty()) out.put("lastNight", rows.get(0));
            }
        }
        return out;
    }

    /** Stores an RR recording; returns the metrics computed from it. */
    public synchronized Map<String, Object> insertRrTest(long tsMs, int durationS, int[] rri, int[] sqi, int stress) {
        Map<String, Object> m = Hrv.compute(rri);
        ContentValues v = new ContentValues();
        v.put("ts", tsMs);
        v.put("duration_s", durationS);
        v.put("rri", Hrv.join(rri));
        v.put("sqi", Hrv.join(sqi));
        v.put("n", (Integer) m.get("n"));
        v.put("rmssd", (Double) m.get("rmssd"));
        v.put("sdnn", (Double) m.get("sdnn"));
        v.put("pnn50", (Double) m.get("pnn50"));
        v.put("mean_hr", (Double) m.get("meanHr"));
        if (stress > 0) v.put("stress", stress);
        getWritableDatabase().insertWithOnConflict("rr_test", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        m.put("ts", tsMs);
        m.put("durationS", durationS);
        m.put("stress", stress);
        return m;
    }

    public synchronized List<Map<String, Object>> rrTests(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, duration_s, rri, n, rmssd, sdnn, pnn50, mean_hr, stress FROM rr_test ORDER BY ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, Object> m = new HashMap<>();
                m.put("ts", c.getLong(0));
                m.put("durationS", c.getInt(1));
                m.put("rri", Hrv.split(c.getString(2)));
                m.put("n", c.getInt(3));
                m.put("rmssd", c.getDouble(4));
                m.put("sdnn", c.getDouble(5));
                m.put("pnn50", c.getDouble(6));
                m.put("meanHr", c.getDouble(7));
                m.put("stress", c.isNull(8) ? 0 : c.getInt(8));
                out.add(m);
            }
        }
        return out;
    }

    public synchronized Map<String, Object> nights(long fromSec, long toSec) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT fall_asleep, bed_time, rising, wakeup, score, latency, efficiency, wake_count, deep_part, min_hr, max_hr, avg_hr, "
                        + "min_spo2, max_spo2, avg_spo2, min_breath, max_breath, avg_breath, avg_hrv, min_hrv_base, max_hrv_base, rdi "
                        + "FROM night WHERE fall_asleep >= ? AND fall_asleep < ? ORDER BY fall_asleep",
                new String[]{String.valueOf(fromSec), String.valueOf(toSec)})) {
            String[] names = {"fallAsleep", "bedTime", "rising", "wakeup", "score", "latency", "efficiency", "wakeCount", "deepPart",
                    "minHr", "maxHr", "avgHr", "minSpo2", "maxSpo2", "avgSpo2", "minBreath", "maxBreath", "avgBreath", "avgHrv",
                    "minHrvBase", "maxHrvBase", "rdi"};
            while (c.moveToNext()) {
                Map<String, Object> m = new HashMap<>();
                for (int i = 0; i < names.length; i++) {
                    if (c.isNull(i)) continue;
                    if (c.getType(i) == Cursor.FIELD_TYPE_FLOAT) m.put(names[i], c.getDouble(i));
                    else m.put(names[i], c.getLong(i));
                }
                rows.add(m);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("rows", rows);
        return out;
    }

    public synchronized Map<String, Object> stress(long fromSec, long toSec) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ts, end_ts, score, level FROM stress WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts",
                new String[]{String.valueOf(fromSec), String.valueOf(toSec)})) {
            int n = c.getCount();
            int[] start = new int[n], end = new int[n], score = new int[n], level = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                start[i] = c.getInt(0);
                end[i] = c.getInt(1);
                score[i] = c.getInt(2);
                level[i] = c.getInt(3);
                i++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("start", start);
            m.put("end", end);
            m.put("score", score);
            m.put("level", level);
            return m;
        }
    }

    public synchronized Map<String, Object> dict(String kind, long fromMs, long toMs) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, value FROM dict WHERE kind = ? AND ts >= ? AND ts < ? ORDER BY ts",
                new String[]{kind, String.valueOf(fromMs), String.valueOf(toMs)})) {
            int n = c.getCount();
            long[] ts = new long[n];
            double[] value = new double[n];
            int i = 0;
            while (c.moveToNext()) {
                ts[i] = c.getLong(0);
                value[i] = c.getDouble(1);
                i++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("ts", ts);
            m.put("value", value);
            return m;
        }
    }

    public synchronized void logSync(long ts, boolean ok, String summary) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("ok", ok ? 1 : 0);
        v.put("summary", summary);
        getWritableDatabase().insertWithOnConflict("sync_log", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized int lastMinuteTs() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(ts) FROM minute", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : 0;
        }
    }

    public synchronized int lastSleepEnd() {
        int a = 0, b = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(end_ts) FROM sleep", null)) {
            a = c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : 0;
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(wakeup) FROM night", null)) {
            b = c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : 0;
        }
        return Math.max(a, b);
    }

    // ------------------------------------------------------------------ raw files

    /** Keeps the bytes of a file downloaded from the band (identical files are stored once). */
    public synchronized void insertRawFile(String kind, int startSec, int endSec, byte[] data) {
        if (data == null || data.length == 0) return;
        ContentValues v = new ContentValues();
        v.put("sha256", sha256(data));
        v.put("kind", kind);
        v.put("received_at", System.currentTimeMillis());
        v.put("start_ts", startSec);
        v.put("end_ts", endSec);
        v.put("size", data.length);
        v.put("data", data);
        getWritableDatabase().insertWithOnConflict("raw_file", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Drops raw files already sent (after 3 days) and any raw file older than 30 days. */
    public synchronized int pruneRawFiles() {
        if (pendingTx != null) return 0;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        int n = db.delete("raw_file", "(uploaded = 1 AND received_at < ?) OR received_at < ?",
                new String[]{String.valueOf(now - 3 * 86400_000L), String.valueOf(now - 30 * 86400_000L)});
        // detail rows already sent go too (the catalog of unknown tags is tiny and is never dropped: its count must keep adding up)
        for (String[] t : PRUNABLE)
            n += db.delete(t[0], "uploaded = 1 AND " + t[1] + " < ?", new String[]{String.valueOf((now - PRUNE_SENT_AFTER_MS) / 1000)});
        return n;
    }

    static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder(h.length * 2);
            for (byte x : h) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(data)) + "-" + data.length;
        }
    }

    /** Label of a stored sleep segment. Only the "trusleep*" ones are shown in the sonogram. */
    public static String sleepOrigin(int source, int type) {
        return br.dimensono.bandsono.sync.UploadRows.sleepOrigin(source, type);
    }

    // ------------------------------------------------------------------ upload bookkeeping

    private void checkNotBusy() {
        if (pendingTx != null) throw new BusyException();
    }

    /** Rows waiting to be sent, all tables together. */
    public synchronized int pendingUploads() {
        int n = 0;
        SQLiteDatabase db = getReadableDatabase();
        for (String t : UPLOAD_TABLES) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + t + " WHERE uploaded <> 1 AND uploaded <> " + UPLOAD_REFUSED, null)) {
                if (c.moveToFirst()) n += c.getInt(0);
            }
        }
        return n;
    }

    /** Rows left "being sent" by an interrupted upload go back to pending. */
    public synchronized void resetInFlight() {
        checkNotBusy();
        SQLiteDatabase db = getWritableDatabase();
        for (String t : UPLOAD_TABLES)
            db.execSQL("UPDATE " + t + " SET uploaded = 0 WHERE uploaded <> 1 AND uploaded = 2");
    }

    /**
     * Marks up to {@code limit} pending rows of {@code table} as being sent and returns them
     * (column name to value: Long, Double, String, byte[] or null).
     */
    public synchronized List<Map<String, Object>> claimPending(String table, int limit) {
        checkNotBusy();
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE " + table + " SET uploaded = 2 WHERE rowid IN (SELECT rowid FROM " + table
                + " WHERE uploaded <> 1 AND uploaded = 0 LIMIT " + limit + ")");
        List<Map<String, Object>> rows = new ArrayList<>();
        // raw_file: the BLOB is not selected (a row over the ~2 MB CursorWindow would fail the whole
        // query); the uploader reads it in pieces with readRawData() only when it is sent.
        String cols0 = "raw_file".equals(table) ? "sha256, kind, received_at, start_ts, end_ts, size" : "*";
        try (Cursor c = db.rawQuery("SELECT " + cols0 + " FROM " + table + " WHERE uploaded <> 1 AND uploaded = 2", null)) {
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < cols.length; i++) {
                    if ("uploaded".equals(cols[i])) continue;
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            m.put(cols[i], null);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            m.put(cols[i], c.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            m.put(cols[i], c.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            m.put(cols[i], c.getBlob(i));
                            break;
                        default:
                            m.put(cols[i], c.getString(i));
                            break;
                    }
                }
                rows.add(m);
            }
        }
        return rows;
    }

    /**
     * Ends a claim: rows still "being sent" become sent (ok) or pending again. A row replaced
     * by a sync in the meantime is already back at 0 and is left alone, so it is sent again.
     */
    public synchronized void finishClaim(String table, boolean ok) {
        finishClaim(table, ok ? 1 : 0);
    }

    /** Same, with an explicit final status: 1 sent, 0 pending, {@link #UPLOAD_REFUSED} refused for good. */
    public synchronized void finishClaim(String table, int status) {
        checkNotBusy();
        getWritableDatabase().execSQL("UPDATE " + table + " SET uploaded = " + status + " WHERE uploaded <> 1 AND uploaded = 2");
    }

    /** Stored size in bytes of a raw file's data, or -1 if there is none. */
    public synchronized long rawFileLength(String sha256) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT length(data) FROM raw_file WHERE sha256 = ?", new String[]{sha256})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
        }
    }

    /**
     * Reads a raw file's bytes in {@link #BLOB_CHUNK} pieces with substr(), so no single cursor row
     * exceeds the CursorWindow. Returns null if the file is gone or {@code length} is not its size.
     */
    public synchronized byte[] readRawData(String sha256, int length) {
        byte[] out = new byte[length];
        SQLiteDatabase db = getReadableDatabase();
        for (int off = 0; off < length; off += BLOB_CHUNK) {
            int n = Math.min(BLOB_CHUNK, length - off);
            // integers are inlined: substr() on a BLOB with bound text arguments is not guaranteed
            try (Cursor c = db.rawQuery("SELECT substr(data, " + (off + 1) + ", " + n + ") FROM raw_file WHERE sha256 = ?",
                    new String[]{sha256})) {
                byte[] piece = c.moveToFirst() ? c.getBlob(0) : null;
                if (piece == null || piece.length != n) return null;
                System.arraycopy(piece, 0, out, off, n);
            }
        }
        return out;
    }

    /** Marks everything as not sent (e.g. when the Supabase project changes). */
    public synchronized void markAllPending() {
        checkNotBusy();
        SQLiteDatabase db = getWritableDatabase();
        for (String t : UPLOAD_TABLES)
            db.execSQL("UPDATE " + t + " SET uploaded = 0 WHERE uploaded <> 0");
    }

    /** Minute samples in [from, to): parallel int arrays, -1 for missing HR/SpO2. */
    public synchronized Map<String, Object> minutes(long from, long to) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, steps, calories, hr, spo2, resting_hr FROM minute WHERE ts >= ? AND ts < ? ORDER BY ts",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            int n = c.getCount();
            int[] ts = new int[n], steps = new int[n], cal = new int[n], hr = new int[n], spo = new int[n], rhr = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                ts[i] = c.getInt(0);
                steps[i] = c.getInt(1);
                cal[i] = c.getInt(2);
                hr[i] = c.isNull(3) ? -1 : c.getInt(3);
                spo[i] = c.isNull(4) ? -1 : c.getInt(4);
                rhr[i] = c.isNull(5) ? -1 : c.getInt(5);
                i++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("ts", ts);
            m.put("steps", steps);
            m.put("calories", cal);
            m.put("hr", hr);
            m.put("spo2", spo);
            m.put("restingHr", rhr);
            return m;
        }
    }

    /** Sleep segments overlapping [from, to). */
    public synchronized Map<String, Object> sleep(long from, long to) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ts, end_ts, type, source FROM sleep WHERE end_ts > ? AND start_ts < ? ORDER BY start_ts",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            int n = c.getCount();
            int[] start = new int[n], end = new int[n], type = new int[n], source = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                start[i] = c.getInt(0);
                end[i] = c.getInt(1);
                type[i] = c.getInt(2);
                source[i] = c.getInt(3);
                i++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("start", start);
            m.put("end", end);
            m.put("type", type);
            m.put("source", source);
            return m;
        }
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), MIN(ts), MAX(ts) FROM minute", null)) {
            if (c.moveToFirst()) {
                m.put("minutes", c.getInt(0));
                m.put("firstTs", c.isNull(1) ? 0 : c.getInt(1));
                m.put("lastTs", c.isNull(2) ? 0 : c.getInt(2));
            }
        }
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sleep", null)) {
            if (c.moveToFirst()) m.put("sleepSegments", c.getInt(0));
        }
        String[][] counts = {{"nights", "night"}, {"stressCount", "stress"}, {"dictCount", "dict"}, {"rrTests", "rr_test"},
                {"workouts", "workout"}, {"workoutSamples", "workout_sample"}, {"rawFiles", "raw_file"},
                {"nightDetails", DetailRows.NIGHT_DETAIL}, {"stressDetails", DetailRows.STRESS_DETAIL},
                {"workoutDetails", DetailRows.WORKOUT_DETAIL}, {"workoutSampleDetails", DetailRows.WORKOUT_SAMPLE_DETAIL},
                {"unknownStepTags", DetailRows.STEP_TAG}, {"deviceStatus", DetailRows.DEVICE_STATUS}};
        for (String[] k : counts) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + k[1], null)) {
                if (c.moveToFirst()) m.put(k[0], c.getInt(0));
            }
        }
        return m;
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("minute", null, null);
        db.delete("sleep", null, null);
        db.delete("sync_log", null, null);
        db.delete("night", null, null);
        db.delete("stress", null, null);
        db.delete("dict", null, null);
        db.delete("rr_test", null, null);
        db.delete("raw_file", null, null);
        db.delete("workout", null, null);
        db.delete("workout_sample", null, null);
        db.delete("sleep_stage", null, null);
        for (String t : DetailRows.TABLES)
            db.delete(t, null, null);
        lastDeviceStatus.clear();
    }
}
