package br.dimensono.bandsono.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Supabase REST client: anonymous sign-in (Auth), token refresh and bulk upserts
 * through PostgREST. Uses only the project URL and the public (anon / publishable) key; every
 * row is written as the signed-in anonymous user, so Row Level Security keeps each phone's
 * data separate. No service_role key is ever needed on the phone.
 */
public class SupabaseClient {

    /** Where the session tokens are kept between runs. */
    public interface SessionStore {
        Session load();

        void save(Session s);
    }

    public static class Session {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAt = 0; // unix seconds
        public String userId = "";

        public boolean valid(long nowSec) {
            return !accessToken.isEmpty() && expiresAt - 60 > nowSec;
        }
    }

    /** HTTP or network failure with a message suitable for the user. */
    public static class ApiException extends IOException {
        public final int status;
        /** True when trying again later may work (network, 5xx, rate limit). */
        public final boolean transientError;
        /** Error code from the server body ({code,message,hint}), or empty. */
        public final String code;

        public ApiException(int status, String message, boolean transientError) {
            this(status, message, transientError, "");
        }

        public ApiException(int status, String message, boolean transientError, String code) {
            super(message);
            this.status = status;
            this.transientError = transientError;
            this.code = code == null ? "" : code;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final String baseUrl;
    private final String apiKey;
    private final SessionStore store;
    private Session session;

    public SupabaseClient(String url, String apiKey, SessionStore store) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/rest/v1")) u = u.substring(0, u.length() - "/rest/v1".length());
        this.baseUrl = u;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.store = store;
    }

    public static boolean looksConfigured(String url, String key) {
        return url != null && url.trim().startsWith("https://") && key != null && key.trim().length() > 20;
    }

    public String userId() {
        return session == null ? "" : session.userId;
    }

    // ------------------------------------------------------------------ auth

    /** Makes sure there is a valid access token: reuses, refreshes or signs in anonymously. */
    public void ensureSession() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        if (session == null)
            session = store.load();
        if (session == null)
            session = new Session();
        if (session.valid(now))
            return;
        if (!session.refreshToken.isEmpty()) {
            try {
                JSONObject body = new JSONObject().put("refresh_token", session.refreshToken);
                Response r = request("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), false, null);
                if (r.ok()) {
                    applySession(r.body);
                    return;
                }
                if (r.status >= 500 || r.status == 429 || r.status == 0)
                    throw error(r, "renovar o acesso");
                // 410 participant_deleted: the account was erased on the server. Do NOT sign up anonymously again
                // (that would create a user "after the deletion" and bring the erased data back); the caller
                // un-links and shows the "account deleted" state.
                ApiException gone = error(r, "renovar o acesso");
                if (UploadRows.isParticipantDeleted(gone.status, gone.code))
                    throw gone;
                // Refresh token revoked or expired: start a new anonymous session below
            } catch (JSONException e) {
                throw new ApiException(0, "Resposta inválida do servidor do estudo", false);
            }
        }
        signInAnonymously();
    }

    private void signInAnonymously() throws IOException {
        try {
            JSONObject body = new JSONObject().put("data", new JSONObject());
            Response r = request("POST", "/auth/v1/signup", body.toString(), false, null);
            if (!r.ok())
                throw error(r, "entrar (login anônimo)");
            applySession(r.body);
        } catch (JSONException e) {
            throw new ApiException(0, "Resposta inválida do servidor do estudo", false);
        }
    }

    private void applySession(String json) throws JSONException, ApiException {
        JSONObject o = new JSONObject(json);
        String access = o.optString("access_token", "");
        if (access.isEmpty())
            throw new ApiException(200, "O servidor do estudo não devolveu um token de acesso (confirme que o login anônimo está ativado)", false);
        Session s = new Session();
        s.accessToken = access;
        s.refreshToken = o.optString("refresh_token", "");
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = o.optLong("expires_at", 0);
        long expiresIn = o.optLong("expires_in", 3600);
        s.expiresAt = expiresAt > now ? expiresAt : now + expiresIn;
        JSONObject user = o.optJSONObject("user");
        s.userId = user != null ? user.optString("id", "") : "";
        if (s.userId.isEmpty() && session != null)
            s.userId = session.userId;
        session = s;
        store.save(s);
    }

    /** Forgets the tokens (a new anonymous user is created on the next upload). */
    public void signOut() {
        session = new Session();
        store.save(session);
    }

    // ------------------------------------------------------------------ data

    /**
     * Insert-or-update of many rows in one request. All objects must have the same keys.
     * {@code onConflict} lists the primary key columns of the remote table.
     */
    public void upsert(String table, String onConflict, JSONArray rows) throws IOException {
        if (rows.length() == 0)
            return;
        ensureSession();
        String path = "/rest/v1/" + table + "?on_conflict=" + onConflict;
        Response r = request("POST", path, rows.toString(), true, "resolution=merge-duplicates,return=minimal");
        if (r.status == 401) {
            // Token expired early or was revoked: refresh once and retry
            session.expiresAt = 0;
            ensureSession();
            r = request("POST", path, rows.toString(), true, "resolution=merge-duplicates,return=minimal");
        }
        if (!r.ok())
            throw error(r, "enviar " + table);
    }

    /** Small read used by the connection test (checks that the table exists and RLS lets us in). */
    public void probe(String table) throws IOException {
        ensureSession();
        Response r = request("GET", "/rest/v1/" + table + "?select=user_id&limit=1", null, true, null);
        if (!r.ok())
            throw error(r, "ler " + table);
    }

    // ------------------------------------------------------------------ HTTP

    static class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private Response request(String method, String path, String body, boolean authed, String prefer) throws ApiException {
        if (baseUrl.isEmpty() || apiKey.isEmpty())
            throw new ApiException(0, "Informe a URL e a chave do servidor do estudo", false);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("apikey", apiKey);
            c.setRequestProperty("Accept", "application/json");
            if (authed)
                c.setRequestProperty("Authorization", "Bearer " + session.accessToken);
            if (prefer != null)
                c.setRequestProperty("Prefer", prefer);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(status, readAll(is));
        } catch (java.net.UnknownHostException e) {
            throw new ApiException(0, "Sem internet ou endereço do servidor incorreto", true);
        } catch (java.net.SocketTimeoutException e) {
            throw new ApiException(0, "O servidor do estudo demorou demais para responder", true);
        } catch (IOException e) {
            throw new ApiException(0, "Falha de rede: " + e.getMessage(), true);
        } finally {
            if (c != null)
                c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null)
            return "";
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Turns an error response into a message that says what to fix. */
    static ApiException error(Response r, String what) {
        String code = "", msg = "";
        try {
            JSONObject o = new JSONObject(r.body);
            code = o.optString("code", o.optString("error_code", o.optString("error", "")));
            msg = o.optString("message", o.optString("msg", o.optString("error_description", "")));
        } catch (Exception ignored) {
            msg = r.body.length() > 160 ? r.body.substring(0, 160) : r.body;
        }
        String lower = (code + " " + msg).toLowerCase(java.util.Locale.ROOT);
        String text;
        boolean transientError = r.status == 0 || r.status == 429 || r.status >= 500;
        if (lower.contains("anonymous") && (lower.contains("disabled") || lower.contains("not enabled")))
            text = "O servidor do estudo não está aceitando o login anônimo do app; avise a equipe do estudo";
        else if (lower.contains("captcha"))
            text = "O servidor do estudo exige CAPTCHA no login do app; avise a equipe do estudo";
        else if (r.status == 401 && (lower.contains("api key") || lower.contains("apikey") || lower.contains("invalid_api_key") || lower.contains("jwt")))
            text = "Chave do servidor do estudo inválida; confira a chave em Ajustes";
        else if ("PGRST205".equals(code) || "42P01".equals(code) || (r.status == 404 && lower.contains("relation")))
            text = "O servidor do estudo ainda não está preparado para estes dados; avise a equipe do estudo";
        else if ("42501".equals(code) || r.status == 403)
            text = "Permissão negada pelo servidor do estudo; avise a equipe do estudo";
        else if ("PGRST204".equals(code) || "42703".equals(code))
            text = "O servidor do estudo está com versão diferente da do app; avise a equipe do estudo";
        else if (r.status == 429)
            text = "Limite de requisições do servidor atingido, tentando de novo mais tarde";
        else
            text = "Erro " + r.status + (msg.isEmpty() ? "" : ": " + msg);
        return new ApiException(r.status, "Falha ao " + what + ". " + text, transientError, code);
    }
}
