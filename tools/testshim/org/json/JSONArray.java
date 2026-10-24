package org.json;

import java.util.ArrayList;
import java.util.List;

/** Test-only stand-in for Android's org.json (the JVM has none). Just what the data layer calls. */
public class JSONArray {
    final List<Object> list = new ArrayList<>();

    public JSONArray() {}

    public JSONArray(String s) throws JSONException {
        Object o = new Parser(s).value();
        if (!(o instanceof JSONArray)) throw new JSONException("not an array");
        list.addAll(((JSONArray) o).list);
    }

    public int length() { return list.size(); }
    public JSONArray put(Object o) { list.add(o); return this; }
    public Object opt(int i) { return i >= 0 && i < list.size() ? list.get(i) : null; }
    public JSONObject optJSONObject(int i) { Object o = opt(i); return o instanceof JSONObject ? (JSONObject) o : null; }
    public JSONObject getJSONObject(int i) throws JSONException { JSONObject o = optJSONObject(i); if (o == null) throw new JSONException("no object"); return o; }
    public JSONArray optJSONArray(int i) { Object o = opt(i); return o instanceof JSONArray ? (JSONArray) o : null; }
    public JSONArray getJSONArray(int i) throws JSONException { JSONArray o = optJSONArray(i); if (o == null) throw new JSONException("no array"); return o; }
    public String optString(int i) { Object o = opt(i); return o == null || o == JSONObject.NULL ? "" : String.valueOf(o); }
    public String getString(int i) throws JSONException { return optString(i); }
    public int optInt(int i) { Object o = opt(i); return o instanceof Number ? ((Number) o).intValue() : 0; }
    public double getDouble(int i) throws JSONException { Object o = opt(i); if (o instanceof Number) return ((Number) o).doubleValue(); throw new JSONException("nan"); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) sb.append(i > 0 ? "," : "").append(JSONObject.write(list.get(i)));
        return sb.append("]").toString();
    }
}
