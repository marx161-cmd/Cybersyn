package com.termux.cybersyn.stub2;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The remote's own arrangement of a pad, kept *beside* DroidPad's export instead of inside it.
 *
 * `<pad>.json` stays exactly what the app produced (so a diff still shows the app's own layout, and
 * re-exporting or re-fetching a pad never throws an arrangement away); this sidecar holds only the
 * widget ids a finger actually moved, in the pad's own design-canvas units — so the same file means
 * the same thing on any screen, and a pad shared with comrade carries its arrangement with it.
 *
 * Written on every drop, atomically (temp + rename), so a drag that ends in a swipe-away is already
 * on disk and a reader never sees half a file.
 */
final class PadLayout {

    static final String SUFFIX = ".layout.json";

    private PadLayout() {
    }

    /** `brown_dwarf.json` -> `brown_dwarf.layout.json`, in the same directory. */
    static File sidecarFor(File padFile) {
        String name = padFile.getName();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        return new File(padFile.getParentFile(), base + SUFFIX);
    }

    /** A sidecar is not a pad: it must never show up in the pad chooser. */
    static boolean isSidecar(String fileName) {
        return fileName.endsWith(SUFFIX);
    }

    /** The saved layout, or an empty map when this pad has never been arranged. Never throws. */
    static Map<String, float[]> load(File padFile) {
        Map<String, float[]> out = new HashMap<>();
        File file = sidecarFor(padFile);
        if (!file.isFile()) return out;
        try {
            JSONObject root = new JSONObject(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONObject widgets = root.optJSONObject("widgets");
            if (widgets == null) return out;
            Iterator<String> ids = widgets.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject node = widgets.optJSONObject(id);
                if (node == null) continue;
                out.put(id, new float[]{
                        (float) node.optDouble("offsetX", 0.0),
                        (float) node.optDouble("offsetY", 0.0),
                });
            }
        } catch (Exception e) {
            // A corrupt sidecar must not stop a pad from opening: fall back to the export's layout.
        }
        return out;
    }

    static void save(File padFile, Map<String, float[]> nodes) throws IOException {
        JSONObject widgets = new JSONObject();
        try {
            for (Map.Entry<String, float[]> entry : nodes.entrySet()) {
                JSONObject node = new JSONObject();
                node.put("offsetX", round(entry.getValue()[0]));
                node.put("offsetY", round(entry.getValue()[1]));
                widgets.put(entry.getKey(), node);
            }
        } catch (Exception ignored) {
            // put() on a JSONObject only throws for non-finite values; nothing sensible to do here.
        }
        JSONObject root = new JSONObject();
        try {
            root.put("layoutVersion", 1);
            root.put("pad", padFile.getName());
            root.put("note", "Arrangement made by dragging in the remote. The export next to this "
                    + "file is untouched; delete this file to fall back to it.");
            root.put("widgets", widgets);
        } catch (Exception ignored) {
        }
        File out = sidecarFor(padFile);
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        String text;
        try {
            // Indented: this file is meant to be read (and hand-nudged) by a human too.
            text = root.toString(1);
        } catch (Exception e) {
            text = root.toString();
        }
        Files.write(tmp.toPath(), (text + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Canvas units carry three decimals, as the app's own export does. */
    private static double round(float v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
