package dev.t1m3.qplayer.customapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal dot-path resolver for the user-configured custom API field mappings
 * (see {@link CustomApiConfig}). Path syntax, per dot-separated segment:
 * <ul>
 *   <li>{@code name} — object field</li>
 *   <li>{@code name[3]} — array index into that field</li>
 *   <li>{@code name[]} (only meaningful in {@link #resolveString}) — treat the
 *       field as an array, resolve the remaining path against every element,
 *       and join the non-empty results with {@code "/"}. Used for shapes like
 *       {@code artists[].name} (multiple artist objects → one display string).</li>
 * </ul>
 * Never throws — any missing/mismatched step returns {@code null} so a wrong
 * user-supplied path degrades to a blank field instead of crashing the search.
 */
final class JsonPath {

    private JsonPath() {}

    /** Resolve to a raw {@link JsonElement} (e.g. the search-results array). No
     *  {@code []} join support — only plain fields and numeric indices. */
    static JsonElement resolve(JsonElement root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) return null;
        JsonElement cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null) return null;
            cur = step(cur, seg);
        }
        return cur;
    }

    /** Resolve to a display string, supporting the {@code a[].b} array-join form. */
    static String resolveString(JsonElement root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) return null;
        String[] segs = path.split("\\.");
        JsonElement cur = root;
        for (int i = 0; i < segs.length; i++) {
            if (cur == null) return null;
            String seg = segs[i];
            if (seg.endsWith("[]")) {
                String name = seg.substring(0, seg.length() - 2);
                JsonElement arrEl = name.isEmpty() ? cur : step(cur, name);
                if (arrEl == null || !arrEl.isJsonArray()) return null;
                String rest = String.join(".", Arrays.asList(segs).subList(i + 1, segs.length));
                List<String> parts = new ArrayList<>();
                for (JsonElement el : arrEl.getAsJsonArray()) {
                    String v = rest.isEmpty() ? asString(el) : resolveString(el, rest);
                    if (v != null && !v.isEmpty()) parts.add(v);
                }
                return String.join("/", parts);
            }
            cur = step(cur, seg);
        }
        return asString(cur);
    }

    private static JsonElement step(JsonElement cur, String seg) {
        String name = seg;
        Integer index = null;
        int b = seg.indexOf('[');
        if (b >= 0 && seg.endsWith("]")) {
            name = seg.substring(0, b);
            String idxStr = seg.substring(b + 1, seg.length() - 1);
            if (idxStr.isEmpty()) return null; // bare "[]" only valid via resolveString
            try {
                index = Integer.parseInt(idxStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!name.isEmpty()) {
            if (cur == null || !cur.isJsonObject()) return null;
            JsonObject obj = cur.getAsJsonObject();
            if (!obj.has(name)) return null;
            cur = obj.get(name);
        }
        if (index != null) {
            if (cur == null || !cur.isJsonArray()) return null;
            JsonArray arr = cur.getAsJsonArray();
            if (index < 0 || index >= arr.size()) return null;
            cur = arr.get(index);
        }
        return cur;
    }

    private static String asString(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        return el.getAsString();
    }
}
