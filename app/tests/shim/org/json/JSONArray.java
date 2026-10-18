package org.json;
import java.util.ArrayList;
/** Minimal shim with Android semantics (optDouble -> NaN when missing). */
public class JSONArray {
    final ArrayList<Object> v = new ArrayList<Object>();
    public JSONArray() {}
    public int length() { return v.size(); }
    public JSONArray put(Object o) { v.add(o); return this; }
    public JSONArray put(double d) { v.add(d); return this; }
    public JSONArray put(int i) { v.add(i); return this; }
    public JSONArray put(boolean b) { v.add(b); return this; }
    public Object opt(int i) { return i >= 0 && i < v.size() ? v.get(i) : null; }
    public double optDouble(int i) { Object o = opt(i); return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN; }
    public double getDouble(int i) throws JSONException { Object o = opt(i); if (!(o instanceof Number)) throw new JSONException("no number at " + i); return ((Number) o).doubleValue(); }
    public int getInt(int i) throws JSONException { return (int) getDouble(i); }
    public int optInt(int i) { Object o = opt(i); return o instanceof Number ? ((Number) o).intValue() : 0; }
    public JSONObject getJSONObject(int i) throws JSONException { Object o = opt(i); if (!(o instanceof JSONObject)) throw new JSONException("no object at " + i); return (JSONObject) o; }
    public JSONObject optJSONObject(int i) { Object o = opt(i); return o instanceof JSONObject ? (JSONObject) o : null; }
    public JSONArray getJSONArray(int i) throws JSONException { Object o = opt(i); if (!(o instanceof JSONArray)) throw new JSONException("no array at " + i); return (JSONArray) o; }
    public JSONArray optJSONArray(int i) { Object o = opt(i); return o instanceof JSONArray ? (JSONArray) o : null; }
    public String getString(int i) throws JSONException { Object o = opt(i); if (o == null) throw new JSONException("no value"); return String.valueOf(o); }
}
