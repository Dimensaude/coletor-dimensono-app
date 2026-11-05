package br.dimensono.bandsono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.dimensono.bandsono.sync.DetailRows;

/**
 * The SQL of the app's local database ({@link BandDatabase}), one list of statements per schema version. Pure Java (no
 * Android classes) so a test can run the very same statements on a plain SQLite: a fresh database must equal an
 * upgraded one, and an upgrade must keep the data (gateway-pi/test_schema_migration.py).
 */
public final class LocalSchema {
    private LocalSchema() {
    }

    /** Tables that got the {@code uploaded} column in version 3 (later tables are created with it). */
    static final String[] V3_TABLES = {"night", "sleep", "minute", "stress", "dict", "rr_test", "sync_log", "raw_file"};

    /** Version 1: what the first release created (resting_hr is added by {@link #upgradeToV2Column()} on old installs). */
    public static List<String> base() {
        return new ArrayList<>(Arrays.asList(
                "CREATE TABLE minute (ts INTEGER PRIMARY KEY, steps INTEGER, calories INTEGER, distance INTEGER, hr INTEGER, spo2 INTEGER, resting_hr INTEGER)",
                "CREATE TABLE sleep (start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, type INTEGER, source INTEGER NOT NULL, PRIMARY KEY(start_ts, source))",
                "CREATE INDEX sleep_end ON sleep(end_ts)",
                "CREATE TABLE sync_log (ts INTEGER PRIMARY KEY, ok INTEGER, summary TEXT)"));
    }

    /** Statement run only when upgrading a version 1 database (a fresh one already has the column). */
    public static String upgradeToV2Column() {
        return "ALTER TABLE minute ADD COLUMN resting_hr INTEGER";
    }

    public static List<String> v2() {
        return new ArrayList<>(Arrays.asList(
                "CREATE TABLE IF NOT EXISTS night (fall_asleep INTEGER PRIMARY KEY, bed_time INTEGER, rising INTEGER, wakeup INTEGER, "
                        + "score INTEGER, latency INTEGER, efficiency INTEGER, wake_count INTEGER, deep_part INTEGER, "
                        + "min_hr INTEGER, max_hr INTEGER, avg_hr INTEGER, min_spo2 REAL, max_spo2 REAL, avg_spo2 INTEGER, "
                        + "min_breath REAL, max_breath REAL, avg_breath INTEGER, avg_hrv INTEGER, min_hrv_base INTEGER, max_hrv_base INTEGER, rdi INTEGER)",
                "CREATE TABLE IF NOT EXISTS stress (start_ts INTEGER PRIMARY KEY, end_ts INTEGER, score INTEGER, level INTEGER)",
                "CREATE TABLE IF NOT EXISTS dict (kind TEXT NOT NULL, ts INTEGER NOT NULL, value REAL, PRIMARY KEY(kind, ts))",
                "CREATE TABLE IF NOT EXISTS rr_test (ts INTEGER PRIMARY KEY, duration_s INTEGER, rri TEXT, sqi TEXT, "
                        + "n INTEGER, rmssd REAL, sdnn REAL, pnn50 REAL, mean_hr REAL, stress INTEGER)"));
    }

    public static List<String> v3() {
        List<String> s = new ArrayList<>();
        s.add("CREATE TABLE IF NOT EXISTS raw_file (sha256 TEXT PRIMARY KEY, kind TEXT NOT NULL, received_at INTEGER NOT NULL, "
                + "start_ts INTEGER, end_ts INTEGER, size INTEGER, data BLOB)");
        s.add("CREATE INDEX IF NOT EXISTS raw_file_received ON raw_file(received_at)");
        for (String t : V3_TABLES) {
            s.add("ALTER TABLE " + t + " ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0");
            s.add("CREATE INDEX IF NOT EXISTS " + t + "_pending ON " + t + "(uploaded) WHERE uploaded <> 1");
        }
        return s;
    }

    public static List<String> v4() {
        List<String> s = new ArrayList<>();
        s.add("CREATE TABLE IF NOT EXISTS workout (start_ts INTEGER PRIMARY KEY, end_ts INTEGER, number INTEGER, type INTEGER, status INTEGER, "
                + "calories INTEGER, distance INTEGER, steps INTEGER, total_time INTEGER, duration INTEGER, min_hr_peak INTEGER, max_hr_peak INTEGER, "
                + "load INTEGER, aerobic_effect INTEGER, anaerobic_effect INTEGER, recovery_time INTEGER, max_met INTEGER, training_points INTEGER, "
                + "elevation_gain INTEGER, elevation_loss INTEGER, max_altitude INTEGER, min_altitude INTEGER, strokes INTEGER, avg_stroke_rate INTEGER, "
                + "laps INTEGER, avg_swolf INTEGER, pool_length INTEGER, swim_type INTEGER, data_count INTEGER, pace_count INTEGER, "
                + "uploaded INTEGER NOT NULL DEFAULT 0)");
        s.add("CREATE TABLE IF NOT EXISTS workout_sample (workout_start INTEGER NOT NULL, ts INTEGER NOT NULL, heart_rate INTEGER, speed INTEGER, "
                + "step_rate INTEGER, cadence INTEGER, step_length INTEGER, ground_contact_time INTEGER, calories INTEGER, spo2 INTEGER, temp INTEGER, "
                + "cycling_power INTEGER, ride_cadence INTEGER, swolf INTEGER, stroke_rate INTEGER, altitude INTEGER, "
                + "uploaded INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(workout_start, ts))");
        for (String t : new String[]{"workout", "workout_sample"})
            s.add("CREATE INDEX IF NOT EXISTS " + t + "_pending ON " + t + "(uploaded) WHERE uploaded <> 1");
        return s;
    }

    public static List<String> v5() {
        return new ArrayList<>(Arrays.asList(
                "CREATE TABLE IF NOT EXISTS sleep_stage (start_ts INTEGER PRIMARY KEY, end_ts INTEGER NOT NULL, stage INTEGER NOT NULL, "
                        + "uploaded INTEGER NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS sleep_stage_pending ON sleep_stage(uploaded) WHERE uploaded <> 1"));
    }

    /**
     * Version 6: the detail tables of the "captura máxima" (columns, types and keys come from
     * {@link DetailRows}, the same description the upload and the gateway use, so they cannot drift apart).
     * Every statement is idempotent.
     */
    public static List<String> v6() {
        List<String> s = new ArrayList<>();
        for (String t : DetailRows.TABLES) {
            String[] cols = DetailRows.COLUMNS.get(t);
            List<String> keys = Arrays.asList(DetailRows.KEYS.get(t));
            StringBuilder b = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(t).append(" (");
            for (String c : cols) {
                String type = DetailRows.REAL_COLUMNS.get(t).contains(c) ? "REAL"
                        : DetailRows.TEXT_COLUMNS.get(t).contains(c) ? "TEXT" : "INTEGER";
                b.append(c).append(' ').append(type).append(keys.contains(c) ? " NOT NULL" : "").append(", ");
            }
            b.append("uploaded INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(").append(String.join(", ", keys)).append("))");
            s.add(b.toString());
            s.add("CREATE INDEX IF NOT EXISTS " + t + "_pending ON " + t + "(uploaded) WHERE uploaded <> 1");
        }
        return s;
    }

    /** All statements of a new database, in order. */
    public static List<String> fresh() {
        List<String> s = base();
        s.addAll(v2());
        s.addAll(v3());
        s.addAll(v4());
        s.addAll(v5());
        s.addAll(v6());
        return s;
    }

    /** All statements of a new database at version 5 (what the previous release created). */
    public static List<String> freshV5() {
        List<String> s = base();
        s.addAll(v2());
        s.addAll(v3());
        s.addAll(v4());
        s.addAll(v5());
        return s;
    }
}
