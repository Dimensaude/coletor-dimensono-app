package android.content;
import java.util.*;
public class Context {
    public static final int MODE_PRIVATE = 0;
    static final Map<String, Prefs> prefs = new HashMap<>();
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs.computeIfAbsent(name, n -> new Prefs()); }
    static class Prefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
        public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
        public int getInt(String k, int d) { Object v = m.get(k); return v == null ? d : (Integer) v; }
        public long getLong(String k, long d) { Object v = m.get(k); return v == null ? d : (Long) v; }
        public Map<String, ?> getAll() { return new HashMap<>(m); }
        public Editor edit() { return new Editor() {
            public Editor putString(String k, String v){m.put(k,v);return this;} public Editor putBoolean(String k, boolean v){m.put(k,v);return this;}
            public Editor putInt(String k, int v){m.put(k,v);return this;} public Editor putLong(String k, long v){m.put(k,v);return this;}
            public Editor putFloat(String k, float v){m.put(k,v);return this;} public Editor remove(String k){m.remove(k);return this;}
            public Editor clear(){m.clear();return this;} public void apply(){} public boolean commit(){return true;} }; }
    }
}
