package com.termux.cybersyn.stub2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A DroidPad control-pad profile, read from the app's own export format (documented in
 * ~/builds/amd/shader-backdrop/pads/README.md).
 *
 * These live in Termux (~/.cybersyn/pads/*.json) so a pad can be written from comrade over ssh,
 * read here, and edited on either side with no APK rebuild in the loop.
 *
 * The contract that must not drift: `itemIdentifier` is the widget id that reaches the renderer as
 * a pad.json key, so it is carried through verbatim.
 */
public final class PadProfile {

    public final File file;
    public final String name;
    public final String orientation;
    public final float width;
    public final float height;
    public final List<Item> items;

    /**
     * Widget ids this pad has been arranged to in the remote (see {@link PadLayout}), in canvas
     * units. Empty when the pad still sits exactly where the app exported it.
     */
    public final Map<String, float[]> overrides;

    /** DroidPad's own base item sizes in dp, from ControlPadJoyStick/Button/SteeringWheel. */
    public static float baseSizeDp(String type) {
        switch (type == null ? "" : type) {
            case "JOYSTICK":      return 150f;
            case "BUTTON":        return 100f;
            case "STEERING_WHEEL":return 200f;
            case "SLIDER":
            case "STEP_SLIDER":
            case "DPAD":          return 150f;
            case "GAUGE":
            case "LED":
            case "SWITCH":
            default:              return 110f;
        }
    }

    public static final class Item {
        public final String id;
        public final String type;
        public final float offsetX;
        public final float offsetY;
        /** Degrees, straight from the export (a vertical slider exports as -89.9/-91.0). */
        public final float rotation;
        public final float scale;
        public final JSONObject props;

        Item(JSONObject o, Map<String, float[]> overrides) {
            this.id = o.optString("itemIdentifier", "");
            this.type = o.optString("itemType", "");
            float x = (float) o.optDouble("offsetX", 0.0);
            float y = (float) o.optDouble("offsetY", 0.0);
            float[] arranged = overrides == null ? null : overrides.get(this.id);
            if (arranged != null) {
                // What a finger moved in the remote wins over what the app exported.
                x = arranged[0];
                y = arranged[1];
            }
            this.offsetX = x;
            this.offsetY = y;
            this.rotation = (float) o.optDouble("rotation", 0.0);
            this.scale = (float) o.optDouble("scale", 1.0);
            JSONObject p;
            try {
                // `properties` is a JSON *string* nested inside the JSON.
                p = new JSONObject(o.optString("properties", "{}"));
            } catch (JSONException e) {
                p = new JSONObject();
            }
            this.props = p;
        }

        public boolean propBool(String key, boolean fallback) {
            return props.optBoolean(key, fallback);
        }

        public int propInt(String key, int fallback) {
            return props.optInt(key, fallback);
        }

        public float propFloat(String key, float fallback) {
            return (float) props.optDouble(key, fallback);
        }

        public String propText(String key, String fallback) {
            String s = props.optString(key, "");
            return s.isEmpty() ? fallback : s;
        }

        /**
         * DroidPad writes colours as Compose `Color.toULong()` values, which puts the ARGB in the
         * high 32 bits (e.g. 18374702968051073024 -> 0xFF000000). Tolerate the plain-ARGB form too.
         */
        public int propColor(String key, int fallback) {
            if (!props.has(key)) return fallback;
            long v = props.optLong(key, Long.MIN_VALUE);
            if (v == Long.MIN_VALUE) return fallback;
            int high = (int) (v >>> 32);
            return high != 0 ? high : (int) v;
        }
    }

    private PadProfile(File file, JSONObject root, Map<String, float[]> overrides) {
        this.file = file;
        this.overrides = Collections.unmodifiableMap(
                overrides == null ? new HashMap<String, float[]>() : overrides);
        JSONObject pad = root.optJSONObject("controlPad");
        if (pad == null) pad = new JSONObject();
        this.name = pad.optString("name", file.getName().replaceFirst("\\.json$", ""));
        this.orientation = pad.optString("orientation", "PORTRAIT");
        this.width = (float) pad.optDouble("width", 1280.0);
        this.height = (float) pad.optDouble("height", 2587.0);

        List<Item> list = new ArrayList<>();
        JSONArray arr = root.optJSONArray("controlPadItems");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item(o, overrides);
                if (!it.id.isEmpty() && !it.type.isEmpty()) list.add(it);
            }
        }
        this.items = Collections.unmodifiableList(list);
    }

    public static PadProfile load(File file) throws IOException, JSONException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        // The remote's own arrangement (if any) rides on top of the export's layout.
        return new PadProfile(file, new JSONObject(text), PadLayout.load(file));
    }

    /** Every `*.json` at the top level of the pads directory (a `superseded/` subdir is ignored). */
    public static List<File> listPadFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.startsWith(".")
                && !PadLayout.isSidecar(n));
        List<File> out = new ArrayList<>();
        if (files != null) Collections.addAll(out, files);
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }
}
