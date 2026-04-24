package it.cnr.isti.labsedc.concern.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for reading fields from the {@code property} JSON of events produced by
 * concern-probes-runtime. Add to the monitor's source tree (same package path).
 * No new library needed: {@code org.json} is already in the monitor's pom.xml.
 *
 * <p>All methods are null-safe — a missing path returns null, never throws.
 *
 * <p>Drools usage example:
 * <pre>
 * import it.cnr.isti.labsedc.concern.utils.ProbeUtils;
 *
 * rule "high temperature"
 * when
 *   $e : ConcernBaseEvent(
 *         ProbeUtils.probeType(property)  == "ConcernTemperatureProbe",
 *         ProbeUtils.jsonDouble(property, "payload.value") > 40.0
 *       )
 * then
 *   System.out.println("ALERT node=" + ProbeUtils.probeNode($e.getProperty()));
 * end
 * </pre>
 *
 * <p>Path syntax examples:
 * <ul>
 *   <li>{@code "probeType"}</li>
 *   <li>{@code "payload.value"}</li>
 *   <li>{@code "payload.readings[0].value"}</li>
 *   <li>{@code "payload.matrix[1][2]"}</li>
 * </ul>
 */
public final class ProbeUtils {

    private ProbeUtils() {}

    public static String jsonField(String property, String path) {
        if (property == null || path == null || path.isEmpty()) return null;
        try {
            Object cur = new JSONObject(property);
            for (String rawPart : path.split("\\.")) {
                // split off any [N][M]... index suffixes
                String key;
                List<Integer> indices = new ArrayList<>();
                int bracket = rawPart.indexOf('[');
                if (bracket < 0) {
                    key = rawPart;
                } else {
                    key = rawPart.substring(0, bracket);
                    String rest = rawPart.substring(bracket);
                    int i = 0;
                    while (i < rest.length()) {
                        if (rest.charAt(i) != '[') return null;
                        int close = rest.indexOf(']', i);
                        if (close < 0) return null;
                        try { indices.add(Integer.parseInt(rest.substring(i + 1, close))); }
                        catch (NumberFormatException nfe) { return null; }
                        i = close + 1;
                    }
                }
                if (!key.isEmpty()) {
                    if (!(cur instanceof JSONObject obj)) return null;
                    if (!obj.has(key) || obj.isNull(key)) return null;
                    cur = obj.get(key);
                }
                for (int idx : indices) {
                    if (!(cur instanceof JSONArray arr)) return null;
                    if (idx < 0 || idx >= arr.length() || arr.isNull(idx)) return null;
                    cur = arr.get(idx);
                }
            }
            if (cur == null || cur == JSONObject.NULL) return null;
            return cur.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static Double  jsonDouble (String p, String path) { String s = jsonField(p, path); if (s == null) return null; try { return Double.parseDouble(s); }  catch (NumberFormatException e) { return null; } }
    public static Long    jsonLong   (String p, String path) { String s = jsonField(p, path); if (s == null) return null; try { return Long.parseLong(s); }    catch (NumberFormatException e) { return null; } }
    public static Integer jsonInt    (String p, String path) { String s = jsonField(p, path); if (s == null) return null; try { return Integer.parseInt(s); }  catch (NumberFormatException e) { return null; } }
    public static Boolean jsonBoolean(String p, String path) { String s = jsonField(p, path); return s == null ? null : Boolean.parseBoolean(s); }

    public static JSONObject payload  (String p) { try { JSONObject r = new JSONObject(p); return r.has("payload") && !r.isNull("payload") ? r.getJSONObject("payload") : null; } catch (Exception e) { return null; } }
    public static String     probeType(String p) { return jsonField(p, "probeType"); }
    public static String     probeNode(String p) { return jsonField(p, "probeNode"); }
}
