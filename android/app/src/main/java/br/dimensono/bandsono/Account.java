package br.dimensono.bandsono;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The patient's session in Dimensono. Its own private file (never in bandsono_settings, so it does not reach
 * Settings.all() or the UI settings channel). Stores only the token, the user id and the last 4 digits of the phone.
 * Never log the token.
 */
public final class Account {
    static final String PREFS = "bandsono_conta";
    private static final String TOKEN = "token";
    private static final String USER_ID = "user_id";
    private static final String PHONE_SUFFIX = "phone_suffix";
    private static final String EXPIRED = "expired";
    /** Set once a session was saved: the participant code is decided by the server from then on. */
    private static final String NEW_FLOW = "new_flow";

    private Account() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context c, String token, String userId, String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        String suffix = digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
        prefs(c).edit().putString(TOKEN, token == null ? "" : token).putString(USER_ID, userId == null ? "" : userId)
                .putString(PHONE_SUFFIX, suffix).putBoolean(EXPIRED, false).putBoolean(NEW_FLOW, true).apply();
    }

    public static String token(Context c) {
        return prefs(c).getString(TOKEN, "");
    }

    public static String userId(Context c) {
        return prefs(c).getString(USER_ID, "");
    }

    public static String phoneSuffix(Context c) {
        return prefs(c).getString(PHONE_SUFFIX, "");
    }

    public static boolean hasSession(Context c) {
        return !token(c).isEmpty();
    }

    public static boolean newFlow(Context c) {
        return prefs(c).getBoolean(NEW_FLOW, false);
    }

    public static boolean expired(Context c) {
        return prefs(c).getBoolean(EXPIRED, false);
    }

    public static void setExpired(Context c, boolean value) {
        prefs(c).edit().putBoolean(EXPIRED, value).apply();
    }

    public static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }
}
