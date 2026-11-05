package android.content;
import java.util.Map;
public interface SharedPreferences {
    interface Editor { Editor putString(String k, String v); Editor putBoolean(String k, boolean v); Editor putInt(String k, int v); Editor putLong(String k, long v); Editor putFloat(String k, float v); Editor remove(String k); Editor clear(); void apply(); boolean commit(); }
    String getString(String k, String d); boolean getBoolean(String k, boolean d); int getInt(String k, int d); long getLong(String k, long d); Map<String, ?> getAll(); Editor edit();
}
