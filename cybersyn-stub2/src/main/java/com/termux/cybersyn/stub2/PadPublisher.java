package com.termux.cybersyn.stub2;

import android.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * One DroidPad-shaped event into Cybersyn's ingest FIFO — the only sanctioned outbound path for
 * anything on this phone (`docs-consolidated/86-ipc-consolidation-plan.md`): a
 * `topic<TAB>payload` line into MqttIngest, which forwards it over the ONE persistent hub
 * connection. Nothing here opens a socket.
 *
 * The topic and the payload shape are deliberately DroidPad's own, so the comrade side
 * (`droidpad-shader-bridge` -> `pad.json` -> the renderer's `u_pad_*` uniforms) needs no change:
 * this satellite is a drop-in replacement for the app's control surface.
 */
public final class PadPublisher {

    private static final String TAG = "stub2-publish";

    /** The same topic DroidPad publishes to; the bridge consumes it as-is. */
    public static final String TOPIC = "DroidPad/Events";

    private static final String FIFO = "/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo";

    /** Held open O_RDWR: open() never blocks even when Cybersyn's reader is respawning. */
    private RandomAccessFile fifo;
    private boolean warned;

    public boolean publish(String json) {
        return publish(TOPIC, json);
    }

    /** Publish on any topic — the same ingest contract, used by the sensor lanes. */
    public boolean publish(String topic, String json) {
        if (json == null || json.indexOf('\n') >= 0 || topic == null || topic.indexOf('\t') >= 0
                || topic.indexOf('\n') >= 0) {
            // A newline in either half would split into a second, caller-chosen message.
            return false;
        }
        byte[] line = (topic + "\t" + json + "\n").getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RandomAccessFile out = fifo;
                if (out == null) {
                    // "rw" is open(O_RDWR), the same trick cybersyn-send uses.
                    out = new RandomAccessFile(FIFO, "rw");
                    fifo = out;
                    warned = false;
                }
                out.write(line);
                return true;
            } catch (IOException e) {
                // FIFO replaced (Termux cleared tmp) or Cybersyn stopped: drop the fd and retry once.
                close();
                if (!warned) {
                    warned = true;
                    Log.w(TAG, "ingest FIFO unavailable (" + FIFO + "): " + e.getMessage());
                }
            }
        }
        return false;
    }

    public void close() {
        RandomAccessFile out = fifo;
        fifo = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
