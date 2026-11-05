package br.dimensono.bandsono;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Properties;
import java.util.Map;

import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

/** All app settings live in one SharedPreferences file (also read by the protocol code). */
public final class Settings {
    public static final String DEVICE_ADDRESS = "device_address";
    public static final String DEVICE_NAME = "device_name";
    public static final String BACKGROUND = "background";
    public static final String INTERVAL_HOURS = "interval_hours";
    public static final String LAST_SYNC = "last_sync";
    public static final String LAST_SYNC_OK = "last_sync_ok";
    public static final String LAST_SYNC_SUMMARY = "last_sync_summary";
    public static final String NEXT_SYNC = "next_sync";
    /** The patient agreed to send the data (set when linked or by setConsent). */
    public static final String CONSENT = "consent";
    /** True once the participant code came from the professional (link or setParticipantCode). */
    public static final String PARTICIPANT_LINKED = "participant_linked";
    /** True once the patient answered the consent question (also set for installs from before 1.5.0). */
    public static final String CONSENT_ASKED = "consent_asked";
    /** Code from a link that differs from the one already linked: waits for the patient to confirm. */
    public static final String PENDING_PARTICIPANT = "pending_participant";
    /** The server answered 410 participant_deleted: uploads stopped, local data kept, the entry flow comes back. */
    public static final String PARTICIPANT_DELETED = "participant_deleted";
    /** Last link that could not be used (shown once by the UI); kept in memory only. */
    private static volatile String linkError = "";
    /** Professional code (DMS-...) from a /actigrafia/p/ link, waiting for the entry flow to prefill it. Memory only. */
    private static volatile String professionalPrefill = "";
    /** Sync interval and background operation are fixed for the study. */
    public static final int FIXED_INTERVAL_HOURS = 2;

    private Settings() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(HuaweiSupportProvider.PREFS_SETTINGS, Context.MODE_PRIVATE);
    }

    public static String deviceAddress(Context c) {
        return prefs(c).getString(DEVICE_ADDRESS, null);
    }

    public static String deviceName(Context c) {
        return prefs(c).getString(DEVICE_NAME, "");
    }

    public static boolean background(Context c) {
        return true; // fixed: always runs in the background
    }

    public static int intervalHours(Context c) {
        return FIXED_INTERVAL_HOURS; // fixed: older stored values are ignored
    }

    public static boolean consent(Context c) {
        return prefs(c).getBoolean(CONSENT, false);
    }

    public static boolean participantLinked(Context c) {
        return prefs(c).getBoolean(PARTICIPANT_LINKED, false);
    }

    public static boolean consentAsked(Context c) {
        return prefs(c).getBoolean(CONSENT_ASKED, false);
    }

    public static boolean participantDeleted(Context c) {
        return prefs(c).getBoolean(PARTICIPANT_DELETED, false);
    }

    public static String pendingParticipant(Context c) {
        return prefs(c).getString(PENDING_PARTICIPANT, "");
    }

    public static String linkError() {
        return linkError;
    }

    public static String professionalPrefill() {
        return professionalPrefill;
    }

    public static void clearProfessionalPrefill(Context c) {
        professionalPrefill = "";
        publishLinkStatus(c);
    }

    /** Link /actigrafia/p/DMS-...: only prefills the professional code, never touches the participant code. */
    public static void applyProfessionalLink(Context c, String raw) {
        String code = ParticipantCode.normalizeProfessional(raw);
        if (code == null) {
            linkError = "Este link não é válido. Confira com o profissional.";
            publishLinkStatus(c);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                linkError = "";
                publishLinkStatus(c);
            }, 3000);
            return;
        }
        linkError = "";
        professionalPrefill = code;
        publishLinkStatus(c);
    }

    /** Optional override of the Dimensono API address (coletor.properties, key dimensono_api_url); empty when absent. */
    public static String dimensonoApiUrl(Context c) {
        Properties props = new Properties();
        try (InputStream in = c.getAssets().open("coletor.properties")) {
            props.load(in);
        } catch (Exception ignored) {
            return "";
        }
        return props.getProperty("dimensono_api_url", "").trim();
    }

    /**
     * Deletes everything of this patient on the phone: database rows, settings, upload state and the session.
     * Run off the main thread. The caller stops the band service first.
     */
    public static void wipeLocal(Context c) {
        BandDatabase.get(c).clearAll();
        prefs(c).edit().clear().apply();
        SupabaseUploader.clearState(c);
        Account.clear(c);
        professionalPrefill = "";
        linkError = "";
        provisionDefaults(c);
        SupabaseUploader.ensureScheduled(c, true);
        Map<String, Object> m = new HashMap<>();
        m.put("address", "");
        m.put("name", "");
        m.put("state", "DISCONNECTED");
        m.put("detail", "");
        m.put("battery", -1);
        BandStatus.update(m);
        publishLinkStatus(c);
    }

    /**
     * A link from the professional. On a phone not linked yet (or the same code) it links right away;
     * a different code never replaces the current one silently: it waits for the patient to confirm.
     */
    public static void applyLink(Context c, String raw) {
        // Fresh install (nothing linked) or an install of the new flow: the participant code comes only from the
        // server, so an old-style link is ignored and the entry flow is shown.
        if (Account.newFlow(c) || !participantLinked(c))
            return;
        String code = ParticipantCode.normalize(raw);
        if (code == null) {
            linkError = "Este link não é válido. Confira com o profissional.";
            publishLinkStatus(c);
            // One-shot notice: cleared after a moment so the same error shows again on the next bad link
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                linkError = "";
                publishLinkStatus(c);
            }, 3000);
            return;
        }
        linkError = "";
        if (participantLinked(c) && !code.equals(participant(c))) {
            prefs(c).edit().putString(PENDING_PARTICIPANT, code).apply();
            publishLinkStatus(c);
            return;
        }
        applyParticipantCode(c, code);
    }

    /** The patient accepted (true) or refused (false) the code waiting in {@link #PENDING_PARTICIPANT}. */
    public static boolean resolvePendingParticipant(Context c, boolean accept) {
        String code = pendingParticipant(c);
        prefs(c).edit().remove(PENDING_PARTICIPANT).apply();
        if (accept && !code.isEmpty())
            return applyParticipantCode(c, code);
        publishLinkStatus(c);
        return !code.isEmpty();
    }

    public static String participant(Context c) {
        return prefs(c).getString(SupabaseUploader.KEY_PARTICIPANT, "");
    }

    /**
     * Links this phone to the professional: stores the code, marks it linked and starts the upload
     * when the patient already consented. Returns false for an invalid code.
     */
    public static boolean applyParticipantCode(Context c, String raw) {
        String code = ParticipantCode.normalize(raw);
        if (code == null) return false;
        linkError = "";
        prefs(c).edit().putString(SupabaseUploader.KEY_PARTICIPANT, code).putBoolean(PARTICIPANT_LINKED, true)
                .remove(PENDING_PARTICIPANT).remove(PARTICIPANT_DELETED).apply();
        publishLinkStatus(c);
        if (consent(c)) {
            SupabaseUploader.ensureScheduled(c, true);
            SupabaseUploader.requestNow(c);
        }
        return true;
    }

    /**
     * The code was unlinked on the server (the professional removed it): no code, no upload, but the account and
     * the consent answer stay, so the app shows "aguardando liberação" and asks the server again.
     */
    public static void unlinkParticipant(Context c) {
        prefs(c).edit().putBoolean(PARTICIPANT_LINKED, false).apply();
        SupabaseUploader.cancel();
        SupabaseUploader.ensureScheduled(c, true);
        publishLinkStatus(c);
    }

    /**
     * 410 participant_deleted from the study server: the participant was erased, so nothing else may be uploaded
     * (it would recreate data on the server). Un-links, turns the consent off and keeps the local data.
     */
    public static void applyParticipantDeleted(Context c) {
        prefs(c).edit().putBoolean(PARTICIPANT_LINKED, false).putBoolean(PARTICIPANT_DELETED, true).apply();
        applyConsent(c, false);
    }

    /** Stores the consent and switches the upload on or off with it. */
    public static void applyConsent(Context c, boolean value) {
        prefs(c).edit().putBoolean(CONSENT, value).putBoolean(CONSENT_ASKED, true)
                .putBoolean(SupabaseUploader.KEY_ENABLED, value).apply();
        if (!value)
            SupabaseUploader.cancel(); // a running upload stops at the next batch
        publishLinkStatus(c);
        SupabaseUploader.ensureScheduled(c, true);
        if (value) SupabaseUploader.requestNow(c);
    }

    /** Pushes participant/consent to the status stream. */
    public static void publishLinkStatus(Context c) {
        Map<String, Object> m = new HashMap<>();
        m.put("participant", participant(c));
        m.put("participantLinked", participantLinked(c));
        m.put("participantDeleted", participantDeleted(c));
        m.put("consent", consent(c));
        m.put("consentAsked", consentAsked(c));
        m.put("pendingParticipant", pendingParticipant(c));
        m.put("linkError", linkError);
        m.put("hasSession", Account.hasSession(c));
        m.put("sessionExpired", Account.expired(c));
        m.put("accountPhone", Account.phoneSuffix(c));
        m.put("professionalPrefill", professionalPrefill);
        BandStatus.update(m);
    }

    /**
     * First run of a distributed build: the server URL and the collector key come from
     * {@code assets/coletor.properties} (kept out of git) and the
     * participant gets a fallback code derived from this phone (not linked to a professional). Anything the user already set wins.
     */
    public static void provisionDefaults(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor ed = p.edit();
        boolean changed = false;
        // Installs that already had the upload on (before consent existed) keep it: consent is taken as given
        if (!p.contains(CONSENT)) {
            boolean enabled = p.getBoolean(SupabaseUploader.KEY_ENABLED, false);
            ed.putBoolean(CONSENT, enabled);
            if (enabled) {
                // Install from before 1.5.0 that was already sending: keep its code (no split history) and
                // do not show the first-use card again
                ed.putBoolean(CONSENT_ASKED, true);
                if (!p.getString(SupabaseUploader.KEY_PARTICIPANT, "").trim().isEmpty())
                    ed.putBoolean(PARTICIPANT_LINKED, true);
            }
            changed = true;
        }
        if (p.getString(SupabaseUploader.KEY_URL, "").trim().isEmpty()) {
            Properties props = new Properties();
            try (InputStream in = c.getAssets().open("coletor.properties")) {
                props.load(in);
            } catch (Exception ignored) {
                // Development build without the file: the user fills in Settings
            }
            String url = props.getProperty("server_url", "").trim();
            String key = props.getProperty("collector_key", "").trim();
            if (!url.isEmpty() && !key.isEmpty()) {
                // The upload is NOT switched on here: it starts when the patient consents
                ed.putString(SupabaseUploader.KEY_URL, url).putString(SupabaseUploader.KEY_KEY, key);
                changed = true;
            }
        }
        if (p.getString(SupabaseUploader.KEY_PARTICIPANT, "").trim().isEmpty()) {
            String id = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) id = Long.toHexString(System.nanoTime());
            String code = "P-" + id.substring(Math.max(0, id.length() - 6)).toUpperCase(java.util.Locale.ROOT);
            ed.putString(SupabaseUploader.KEY_PARTICIPANT, code);
            changed = true;
        }
        if (changed) ed.apply();
    }

    /** Defaults used by the UI when a key was never written. */
    public static Map<String, Object> defaults() {
        Map<String, Object> d = new HashMap<>();
        d.put(HuaweiSupportProvider.PREF_HR_AUTO, true);
        d.put(HuaweiSupportProvider.PREF_HR_REALTIME, true);
        d.put(HuaweiSupportProvider.PREF_STRESS_AUTO, true);
        d.put(HuaweiSupportProvider.PREF_SPO2_AUTO, true);
        d.put(HuaweiSupportProvider.PREF_TRUSLEEP, true);
        d.put(HuaweiSupportProvider.PREF_INACTIVITY, true);
        d.put(HuaweiSupportProvider.PREF_SLEEP_BREATH, true);
        d.put(HuaweiSupportProvider.PREF_LIFT_WRIST, false);
        d.put(HuaweiSupportProvider.PREF_WEAR_LEFT, true);
        d.put(BACKGROUND, true);
        d.put(INTERVAL_HOURS, FIXED_INTERVAL_HOURS);
        d.put(CONSENT, false);
        d.put(PARTICIPANT_LINKED, false);
        return d;
    }

    public static Map<String, Object> all(Context c) {
        Map<String, Object> out = defaults();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Float) v = ((Float) v).doubleValue();
            out.put(e.getKey(), v);
        }
        out.put(BACKGROUND, true);
        out.put(INTERVAL_HOURS, FIXED_INTERVAL_HOURS);
        return out;
    }

    public static void put(Context c, String key, Object value) {
        SharedPreferences.Editor ed = prefs(c).edit();
        if (value == null) ed.remove(key);
        else if (value instanceof Boolean) ed.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) ed.putInt(key, (Integer) value);
        else if (value instanceof Long) ed.putInt(key, (int) (long) (Long) value);
        else if (value instanceof Double) ed.putFloat(key, (float) (double) (Double) value);
        else ed.putString(key, value.toString());
        ed.apply();
    }
}
