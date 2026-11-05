package br.dimensono.bandsono;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;
import br.dimensono.bandsono.sync.SupabaseClient;
import br.dimensono.bandsono.sync.UploadRows;
import br.dimensono.bandsono.sync.UploadRows.Spec;

/**
 * Sends everything stored on the phone to a Supabase project, incrementally:
 * minute samples, sleep segments (all sources), the band's night summaries, stress, HRV and
 * skin temperature values, RR recordings, the sync log and the raw files downloaded from the
 * band. Runs after every sync and every 2 hours (JobScheduler, only with network), and on demand.
 */
public final class SupabaseUploader {
    private static final Logger LOG = LoggerFactory.getLogger(SupabaseUploader.class);

    // Settings written by the UI (main settings file)
    public static final String KEY_ENABLED = "sb_enabled";
    public static final String KEY_URL = "sb_url";
    public static final String KEY_KEY = "sb_key";
    public static final String KEY_PARTICIPANT = "sb_participant";

    // Device info kept for the device row
    public static final String KEY_MODEL = "device_model";
    public static final String KEY_FIRMWARE = "device_firmware";

    // Private state (separate file: tokens never reach the UI)
    private static final String PREFS = "bandsono_supabase";
    private static final String S_ACCESS = "access_token";
    private static final String S_REFRESH = "refresh_token";
    private static final String S_EXPIRES = "expires_at";
    private static final String S_USER = "user_id";
    private static final String S_LAST = "last_upload";
    private static final String S_LAST_OK = "last_upload_ok";
    private static final String S_LAST_MSG = "last_upload_msg";
    private static final String S_LAST_SUCCESS = "last_success";
    private static final String S_LAST_COUNT = "last_count";

    static final int JOB_PERIODIC = 4701;
    static final int JOB_NOW = 4702;
    static final long PERIOD_MS = 2 * 3600_000L;
    private static final int MAX_BATCHES = 400;
    /** Consecutive runs a raw file failed with a network error (in memory, by sha256). */
    private static final Map<String, Integer> RAW_NET_FAILS = new HashMap<>();
    private static final int RAW_NET_FAIL_LIMIT = 3;
    public static final class Result {
        public final boolean ok;
        public final boolean retry;
        public final String message;
        public final int sent;

        Result(boolean ok, boolean retry, String message, int sent) {
            this.ok = ok;
            this.retry = retry;
            this.message = message;
            this.sent = sent;
        }
    }

    public interface Done {
        void onDone(Result r);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean again = new AtomicBoolean(false);
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    private SupabaseUploader() {
    }

    // ------------------------------------------------------------------ config

    public static boolean enabled(Context c) {
        SharedPreferences p = Settings.prefs(c);
        // Nothing leaves the phone without a participant code that came from the professional/server
        return p.getBoolean(KEY_ENABLED, false) && Settings.participantLinked(c)
                && SupabaseClient.looksConfigured(p.getString(KEY_URL, ""), p.getString(KEY_KEY, ""));
    }

    /** Forgets the private upload state (session of the study server, last results). */
    static void clearState(Context c) {
        state(c).edit().clear().apply();
    }

    private static SharedPreferences state(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SupabaseClient client(Context c) {
        SharedPreferences p = Settings.prefs(c);
        final SharedPreferences st = state(c);
        return new SupabaseClient(p.getString(KEY_URL, ""), p.getString(KEY_KEY, ""), new SupabaseClient.SessionStore() {
            @Override
            public SupabaseClient.Session load() {
                SupabaseClient.Session s = new SupabaseClient.Session();
                s.accessToken = st.getString(S_ACCESS, "");
                s.refreshToken = st.getString(S_REFRESH, "");
                s.expiresAt = st.getLong(S_EXPIRES, 0);
                s.userId = st.getString(S_USER, "");
                return s;
            }

            @Override
            public void save(SupabaseClient.Session s) {
                st.edit().putString(S_ACCESS, s.accessToken).putString(S_REFRESH, s.refreshToken)
                        .putLong(S_EXPIRES, s.expiresAt).putString(S_USER, s.userId).apply();
            }
        });
    }

    /** Called when the UI changes a Supabase setting. */
    public static void onSettingChanged(Context c, String key) {
        if (KEY_URL.equals(key) || KEY_KEY.equals(key)) {
            // Another project (or key): new anonymous user there and everything is sent again
            state(c).edit().remove(S_ACCESS).remove(S_REFRESH).remove(S_EXPIRES).remove(S_USER).apply();
            final Context app = c.getApplicationContext();
            executor.execute(() -> {
                try {
                    BandDatabase.get(app).markAllPending();
                } catch (Exception e) {
                    LOG.warn("markAllPending: {}", e.toString());
                }
                publishStatus(app, null);
            });
        }
        ensureScheduled(c, true);
        if (KEY_ENABLED.equals(key) && enabled(c))
            requestNow(c);
    }

    // ------------------------------------------------------------------ scheduling

    /** Periodic upload every 2 h while enabled (kept across reboots). */
    public static void ensureScheduled(Context c, boolean force) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null)
            return;
        if (!enabled(c)) {
            js.cancel(JOB_PERIODIC);
            js.cancel(JOB_NOW);
            return;
        }
        if (!force && js.getPendingJob(JOB_PERIODIC) != null)
            return;
        JobInfo job = new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, UploadJobService.class))
                .setPeriodic(PERIOD_MS, 30 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (Exception e) {
            LOG.error("Could not schedule periodic upload", e);
        }
    }

    /** Upload as soon as there is network (used after every sync). */
    public static void requestNow(Context c) {
        if (!enabled(c))
            return;
        if (running.get()) {
            again.set(true);
            return;
        }
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null)
            return;
        JobInfo job = new JobInfo.Builder(JOB_NOW, new ComponentName(c, UploadJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        try {
            js.schedule(job);
        } catch (Exception e) {
            LOG.error("Could not schedule upload", e);
        }
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static void cancel() {
        cancelled.set(true);
    }

    // ------------------------------------------------------------------ running

    /** Runs an upload on the uploader thread; concurrent requests are merged into one extra run. */
    public static void runAsync(Context context, String reason, Done done) {
        final Context c = context.getApplicationContext();
        if (!running.compareAndSet(false, true)) {
            again.set(true);
            if (done != null)
                done.onDone(new Result(true, false, "Envio já em andamento", 0));
            return;
        }
        cancelled.set(false);
        executor.execute(() -> {
            Result r;
            try {
                int rounds = 0;
                do {
                    again.set(false);
                    r = runOnce(c, reason);
                    rounds++;
                } while (r.ok && again.get() && !cancelled.get() && rounds < 3);
            } catch (Throwable t) {
                // e.g. OutOfMemoryError with a large raw file: never leave "running" stuck
                LOG.error("Upload crashed", t);
                r = finish(c, new Result(false, true, "Falha no envio: " + t, 0));
            }
            running.set(false);
            publishStatus(c, null);
            if (done != null)
                done.onDone(r);
        });
    }

    private static Result runOnce(Context c, String reason) {
        if (!Settings.prefs(c).getBoolean(KEY_ENABLED, false))
            return new Result(true, false, "Envio desativado", 0);
        if (!Settings.participantLinked(c))
            return new Result(true, false, "Aguardando liberação do envio", 0);
        if (!enabled(c))
            return finish(c, new Result(false, false, "Informe a URL e a chave do servidor do estudo", 0));
        Map<String, Object> busy = new HashMap<>();
        busy.put("sbUploading", true);
        BandStatus.update(busy);
        LOG.info("Supabase upload started ({})", reason);
        BandDatabase db = BandDatabase.get(c);
        int sent = 0;
        try {
            db.resetInFlight();
            SupabaseClient client = client(c);
            client.ensureSession();
            client.upsert("band_devices", "user_id,device_address", new JSONArray().put(deviceRow(c, client.userId())));
            for (Spec spec : UploadRows.SPECS) {
                for (int i = 0; i < MAX_BATCHES && !cancelled.get(); i++) {
                    List<Map<String, Object>> rows = db.claimPending(spec.local, spec.batch);
                    if (rows.isEmpty())
                        break;
                    try {
                        if ("raw_file".equals(spec.local) && !loadRawData(db, rows)) {
                            // too big or unreadable: refused for good, the others carry on
                            db.finishClaim(spec.local, BandDatabase.UPLOAD_REFUSED);
                            continue;
                        }
                        JSONArray arr = new JSONArray();
                        for (Map<String, Object> row : rows)
                            arr.put(UploadRows.toJson(spec.local, row, client.userId()));
                        client.upsert(spec.remote, spec.onConflict, arr);
                        if ("raw_file".equals(spec.local))
                            RAW_NET_FAILS.remove(String.valueOf(rows.get(0).get("sha256")));
                        db.finishClaim(spec.local, true);
                        sent += rows.size();
                    } catch (Exception e) {
                        SupabaseClient.ApiException api = e instanceof SupabaseClient.ApiException ? (SupabaseClient.ApiException) e : null;
                        if (api != null && UploadRows.isParticipantDeleted(api.status, api.code)) {
                            // Erased on the server: keep the rows (local data stays), stop everything below
                            try {
                                db.finishClaim(spec.local, 0);
                            } catch (BandDatabase.BusyException ignored) {
                                // rows go back to pending at the start of the next run
                            }
                            throw e;
                        }
                        boolean raw = "raw_file".equals(spec.local);
                        boolean refused = raw && api != null && UploadRows.isPermanentRejection(api.status, api.code);
                        if (raw && api != null && api.status == 0 && api.transientError) {
                            // The server may close the connection on an oversized body: IOException, no status.
                            // The band_devices upsert of this run already worked, so the network is up.
                            String sha = String.valueOf(rows.get(0).get("sha256"));
                            Integer n = RAW_NET_FAILS.get(sha);
                            n = n == null ? 1 : n + 1;
                            RAW_NET_FAILS.put(sha, n);
                            if (n >= RAW_NET_FAIL_LIMIT) {
                                LOG.warn("Raw file failed {} runs in a row with network errors, refusing it: {}", n, rawLabel(rows));
                                refused = true;
                            }
                        }
                        try {
                            db.finishClaim(spec.local, refused ? BandDatabase.UPLOAD_REFUSED : 0);
                        } catch (BandDatabase.BusyException ignored) {
                            // rows go back to pending at the start of the next run
                        }
                        if (refused) {
                            // 400/413/422: this file will never be accepted. Mark it and go on with the next one.
                            LOG.warn("Raw file refused (HTTP {} {}), will not be resent: {}",
                                    api == null ? 0 : api.status, api == null ? "" : api.code, rawLabel(rows));
                            RAW_NET_FAILS.remove(String.valueOf(rows.get(0).get("sha256")));
                            continue;
                        }
                        // Server misconfiguration (missing table/column/constraint) or a non-data 4xx on a raw file:
                        // stop this table, keep the rows pending, carry on with the rest
                        if ((spec.optional || raw) && api != null && !api.transientError
                                && api.status != 401 && api.status != 403) {
                            LOG.warn("Skipping {} for now (server not ready for it?): {}", spec.remote, e.getMessage());
                            break;
                        }
                        throw e;
                    }
                }
                if (cancelled.get())
                    return finish(c, new Result(false, true, "Envio interrompido pelo sistema; continua depois", sent));
            }
            int pruned = db.pruneRawFiles();
            LOG.info("Supabase upload done: {} rows, {} old raw files pruned", sent, pruned);
            String msg = sent == 0 ? "Tudo já estava enviado" : sent + " registros enviados";
            return finish(c, new Result(true, false, msg, sent));
        } catch (BandDatabase.BusyException e) {
            again.set(false);
            return finish(c, new Result(false, true, "Sincronização com a pulseira em andamento; o envio continua em seguida", sent));
        } catch (SupabaseClient.ApiException e) {
            if (UploadRows.isParticipantDeleted(e.status, e.code)) {
                LOG.warn("Participant erased on the server (410): uploads stopped");
                Settings.applyParticipantDeleted(c);
                return finish(c, new Result(false, false, "Conta excluída; o envio foi interrompido", sent));
            }
            LOG.warn("Supabase upload failed: {}", e.getMessage());
            return finish(c, new Result(false, e.transientError, e.getMessage(), sent));
        } catch (Exception e) {
            LOG.error("Supabase upload failed", e);
            return finish(c, new Result(false, true, "Falha no envio: " + e, sent));
        }
    }

    private static String rawLabel(List<Map<String, Object>> rows) {
        if (rows.isEmpty())
            return "?";
        Map<String, Object> r = rows.get(0);
        return r.get("kind") + " " + r.get("sha256") + " (" + r.get("size") + " bytes)";
    }

    /**
     * Fills "data" of each claimed raw_file row, reading the BLOB in pieces. False (and a log line)
     * when a file is missing or its base64 would pass {@link UploadRows#MAX_RAW_BASE64}.
     */
    private static boolean loadRawData(BandDatabase db, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String sha = String.valueOf(row.get("sha256"));
            long len = db.rawFileLength(sha);
            if (len < 0) {
                LOG.warn("Raw file {} has no data, skipped", rawLabel(rows));
                return false;
            }
            if (len > UploadRows.MAX_RAW_BYTES) {
                LOG.warn("Raw file too large to send ({} bytes, limit {}), skipped for good: {}", len, UploadRows.MAX_RAW_BYTES, rawLabel(rows));
                return false;
            }
            byte[] data = db.readRawData(sha, (int) len);
            if (data == null) {
                LOG.warn("Raw file {} could not be read, skipped", rawLabel(rows));
                return false;
            }
            row.put("data", data);
        }
        return true;
    }

    private static Result finish(Context c, Result r) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = state(c).edit()
                .putLong(S_LAST, now)
                .putBoolean(S_LAST_OK, r.ok)
                .putString(S_LAST_MSG, r.message)
                .putInt(S_LAST_COUNT, r.sent);
        if (r.ok)
            ed.putLong(S_LAST_SUCCESS, now);
        ed.apply();
        publishStatus(c, null);
        return r;
    }

    /** Connection test for the settings screen: signs in and reads every table once. */
    public static Map<String, Object> test(Context context) {
        Context c = context.getApplicationContext();
        Map<String, Object> out = new HashMap<>();
        SharedPreferences p = Settings.prefs(c);
        if (!SupabaseClient.looksConfigured(p.getString(KEY_URL, ""), p.getString(KEY_KEY, ""))) {
            out.put("ok", false);
            out.put("message", "Informe a URL e a chave do servidor do estudo");
            return out;
        }
        try {
            SupabaseClient client = client(c);
            client.ensureSession();
            client.probe("band_devices");
            for (Spec s : UploadRows.SPECS)
                client.probe(s.remote);
            out.put("ok", true);
            out.put("message", "Conexão OK. Usuário anônimo " + shortId(client.userId()));
            out.put("userId", client.userId());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        publishStatus(c, null);
        return out;
    }

    /** Status fields for the UI (merged into the band status map). */
    public static Map<String, Object> status(Context c) {
        SharedPreferences st = state(c);
        Map<String, Object> m = new HashMap<>();
        m.put("sbUploading", running.get());
        m.put("sbLastUpload", st.getLong(S_LAST, 0));
        m.put("sbLastUploadOk", st.getBoolean(S_LAST_OK, true));
        m.put("sbLastUploadMsg", st.getString(S_LAST_MSG, ""));
        m.put("sbLastSuccess", st.getLong(S_LAST_SUCCESS, 0));
        m.put("sbUserId", st.getString(S_USER, ""));
        return m;
    }

    private static void publishStatus(Context c, Integer pendingOrNull) {
        Map<String, Object> m = status(c);
        try {
            m.put("sbPending", pendingOrNull != null ? pendingOrNull : BandDatabase.get(c).pendingUploads());
        } catch (Exception e) {
            LOG.warn("pendingUploads: {}", e.toString());
        }
        BandStatus.update(m);
    }

    // ------------------------------------------------------------------ rows

    private static JSONObject deviceRow(Context c, String userId) throws JSONException {
        SharedPreferences p = Settings.prefs(c);
        return UploadRows.deviceJson(userId, Settings.deviceAddress(c), Settings.deviceName(c), p.getString(KEY_MODEL, ""),
                p.getString(KEY_FIRMWARE, ""), p.getString(KEY_PARTICIPANT, ""), appVersion(c),
                "android " + android.os.Build.VERSION.RELEASE + " / " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                System.currentTimeMillis());
    }

    private static String shortId(String id) {
        return id == null || id.length() < 8 ? String.valueOf(id) : id.substring(0, 8) + "…";
    }

    private static String appVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
