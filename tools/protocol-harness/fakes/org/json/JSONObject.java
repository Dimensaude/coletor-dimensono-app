package org.json;
import java.util.*;
/** Small JSON implementation (objects, arrays, numbers, strings, booleans) for the harness. */
public class JSONObject {
    private final Map<String, Object> m = new LinkedHashMap<>();
    public static final Object NULL = new Object() { @Override public String toString() { return "null"; } };
    public JSONObject() {}
    public JSONObject(String s) throws JSONException { Parser p = new Parser(s); Object o = p.value(); if (!(o instanceof JSONObject)) throw new JSONException("not an object"); m.putAll(((JSONObject) o).m); }
    public JSONObject put(String k, Object v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, int v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, long v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, double v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, boolean v) throws JSONException { m.put(k, v); return this; }
    public Object opt(String k) { return m.get(k); }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
    public long optLong(String k) { return optLong(k, 0L); }
    public long optLong(String k, long d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : d; }
    public double optDouble(String k, double d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).doubleValue() : d; }
    public boolean optBoolean(String k) { Object v = m.get(k); return v instanceof Boolean && (Boolean) v; }
    public boolean optBoolean(String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object v = m.get(k); return v == null ? d : String.valueOf(v); }
    public JSONObject optJSONObject(String k) { Object v = m.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = m.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public int length() { return m.size(); }
    public Iterator<String> keys() { return m.keySet().iterator(); }
    public boolean has(String k) { return m.containsKey(k); }
    private Object need(String k) throws JSONException { if (!m.containsKey(k)) throw new JSONException("missing " + k); return m.get(k); }
    public String getString(String k) throws JSONException { return String.valueOf(need(k)); }
    public int getInt(String k) throws JSONException { return ((Number) need(k)).intValue(); }
    public long getLong(String k) throws JSONException { return ((Number) need(k)).longValue(); }
    public double getDouble(String k) throws JSONException { return ((Number) need(k)).doubleValue(); }
    public boolean getBoolean(String k) throws JSONException { return (Boolean) need(k); }
    public JSONObject getJSONObject(String k) throws JSONException { return (JSONObject) need(k); }
    public JSONArray getJSONArray(String k) throws JSONException { return (JSONArray) need(k); }
    static String q(Object v) { if (v instanceof String) return "\"" + ((String) v).replace("\\", "\\\\").replace("\"", "\\\"") + "\""; return String.valueOf(v); }
    public String toString() { StringBuilder b = new StringBuilder("{"); boolean f = true; for (Map.Entry<String, Object> e : m.entrySet()) { if (!f) b.append(','); f = false; b.append(q(e.getKey())).append(':').append(q(e.getValue())); } return b.append('}').toString(); }
    static class Parser {
        final String s; int i = 0;
        Parser(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() throws JSONException {
            ws(); char c = s.charAt(i);
            if (c == '{') { i++; JSONObject o = new JSONObject(); ws(); if (s.charAt(i) == '}') { i++; return o; }
                while (true) { ws(); String k = (String) value(); ws(); i++; Object v = value(); o.m.put(k, v); ws(); char d = s.charAt(i++); if (d == '}') return o; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return new JSONArray(l); }
                while (true) { l.add(value()); ws(); char d = s.charAt(i++); if (d == ']') return new JSONArray(l); } }
            if (c == '"') { StringBuilder r = new StringBuilder(); i++;
                while (s.charAt(i) != '"') { char ch = s.charAt(i++); if (ch != '\\') { r.append(ch); continue; }
                    char e = s.charAt(i++);
                    switch (e) { case 'n': r.append('\n'); break; case 't': r.append('\t'); break; case 'r': r.append('\r'); break;
                        case 'b': r.append('\b'); break; case 'f': r.append('\f'); break;
                        case 'u': r.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: r.append(e); } }
                i++; return r.toString(); }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            int j = i; while (j < s.length() && "+-0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            String n = s.substring(i, j); i = j;
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            long L = Long.parseLong(n); return (L >= Integer.MIN_VALUE && L <= Integer.MAX_VALUE) ? (Object) (int) L : (Object) L;
        }
    }
}
