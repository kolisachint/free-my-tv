package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only stand-in for Android's org.json (the JVM has none). Just what the data layer calls. */
public class JSONObject {
    public static final Object NULL = new Object() { @Override public String toString() { return "null"; } };
    final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String s) throws JSONException {
        Object o = new Parser(s).value();
        if (!(o instanceof JSONObject)) throw new JSONException("not an object");
        map.putAll(((JSONObject) o).map);
    }

    public JSONObject put(String k, Object v) throws JSONException { map.put(k, v); return this; }
    public JSONObject put(String k, boolean v) throws JSONException { map.put(k, v); return this; }
    public JSONObject put(String k, int v) throws JSONException { map.put(k, v); return this; }
    public JSONObject put(String k, long v) throws JSONException { map.put(k, v); return this; }
    public JSONObject put(String k, double v) throws JSONException { map.put(k, v); return this; }
    public boolean has(String k) { return map.containsKey(k); }
    public boolean isNull(String k) { return !map.containsKey(k) || map.get(k) == NULL; }
    public Object opt(String k) { return map.get(k); }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object o = map.get(k); return o == null || o == NULL ? d : String.valueOf(o); }
    public String getString(String k) throws JSONException { if (!map.containsKey(k)) throw new JSONException("no " + k); return optString(k); }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { Object o = map.get(k); return o instanceof Number ? ((Number) o).intValue() : d; }
    public int getInt(String k) throws JSONException { if (!(map.get(k) instanceof Number)) throw new JSONException("no " + k); return optInt(k); }
    public long optLong(String k) { Object o = map.get(k); return o instanceof Number ? ((Number) o).longValue() : 0; }
    public double optDouble(String k, double d) { Object o = map.get(k); return o instanceof Number ? ((Number) o).doubleValue() : d; }
    public boolean optBoolean(String k) { Object o = map.get(k); return o instanceof Boolean && (Boolean) o; }
    public JSONObject optJSONObject(String k) { Object o = map.get(k); return o instanceof JSONObject ? (JSONObject) o : null; }
    public JSONArray optJSONArray(String k) { Object o = map.get(k); return o instanceof JSONArray ? (JSONArray) o : null; }
    public JSONArray getJSONArray(String k) throws JSONException { JSONArray a = optJSONArray(k); if (a == null) throw new JSONException("no " + k); return a; }

    static String write(Object o) {
        if (o instanceof String) return "\"" + ((String) o).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return String.valueOf(o);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            sb.append(first ? "" : ",").append(write(e.getKey())).append(":").append(write(e.getValue()));
            first = false;
        }
        return sb.append("}").toString();
    }
}
