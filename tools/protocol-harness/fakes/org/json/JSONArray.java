package org.json;
import java.util.*;
public class JSONArray {
    final List<Object> list = new ArrayList<>();
    public JSONArray() {}
    JSONArray(List<Object> l) { list.addAll(l); }
    public JSONArray put(Object v) { list.add(v); return this; }
    public JSONArray put(double v) { list.add(v); return this; }
    public JSONArray put(int v) { list.add(v); return this; }
    public JSONArray put(long v) { list.add(v); return this; }
    public JSONArray put(boolean v) { list.add(v); return this; }
    public int length() { return list.size(); }
    public double getDouble(int i) throws JSONException { return ((Number) list.get(i)).doubleValue(); }
    public Object get(int i) { return list.get(i); }
    public String toString() { StringBuilder b = new StringBuilder("["); for (int i = 0; i < list.size(); i++) { if (i > 0) b.append(','); b.append(JSONObject.q(list.get(i))); } return b.append(']').toString(); }
}
