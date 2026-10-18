package org.json;
import java.util.Iterator;
import java.util.LinkedHashMap;
/** Minimal shim with Android semantics (optDouble -> NaN when missing). No text parsing. */
public class JSONObject {
    final LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
    public JSONObject() {}
    public JSONObject put(String k, Object o) throws JSONException { if (o == null) m.remove(k); else m.put(k, o); return this; }
    public JSONObject put(String k, double d) throws JSONException { if (Double.isNaN(d) || Double.isInfinite(d)) throw new JSONException("Forbidden numeric value: " + d); m.put(k, d); return this; }
    public JSONObject put(String k, int i) throws JSONException { m.put(k, i); return this; }
    public JSONObject put(String k, long i) throws JSONException { m.put(k, i); return this; }
    public JSONObject put(String k, boolean b) throws JSONException { m.put(k, b); return this; }
    public boolean has(String k) { return m.containsKey(k); }
    public Object opt(String k) { return m.get(k); }
    public Iterator<String> keys() { return m.keySet().iterator(); }
    public int length() { return m.size(); }
    public double optDouble(String k) { return optDouble(k, Double.NaN); }
    public double optDouble(String k, double d) { Object o = m.get(k); return o instanceof Number ? ((Number) o).doubleValue() : d; }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { Object o = m.get(k); return o instanceof Number ? ((Number) o).intValue() : d; }
    public long optLong(String k) { return optLong(k,0); }
    public long optLong(String k, long d) { Object o = m.get(k); return o instanceof Number ? ((Number) o).longValue() : d; }
    public boolean optBoolean(String k) { return optBoolean(k, false); }
    public boolean optBoolean(String k, boolean d) { Object o = m.get(k); return o instanceof Boolean ? (Boolean) o : d; }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object o = m.get(k); return o == null ? d : String.valueOf(o); }
    public double getDouble(String k) throws JSONException { Object o = m.get(k); if (!(o instanceof Number)) throw new JSONException("No value for " + k); return ((Number) o).doubleValue(); }
    public int getInt(String k) throws JSONException { return (int) getDouble(k); }
    public long getLong(String k) throws JSONException { return (long) getDouble(k); }
    public boolean getBoolean(String k) throws JSONException { Object o = m.get(k); if (!(o instanceof Boolean)) throw new JSONException("No value for " + k); return (Boolean) o; }
    public String getString(String k) throws JSONException { Object o = m.get(k); if (o == null) throw new JSONException("No value for " + k); return String.valueOf(o); }
    public JSONObject getJSONObject(String k) throws JSONException { Object o = m.get(k); if (!(o instanceof JSONObject)) throw new JSONException("No object for " + k); return (JSONObject) o; }
    public JSONObject optJSONObject(String k) { Object o = m.get(k); return o instanceof JSONObject ? (JSONObject) o : null; }
    public JSONArray getJSONArray(String k) throws JSONException { Object o = m.get(k); if (!(o instanceof JSONArray)) throw new JSONException("No array for " + k); return (JSONArray) o; }
    public JSONArray optJSONArray(String k) { Object o = m.get(k); return o instanceof JSONArray ? (JSONArray) o : null; }
}
