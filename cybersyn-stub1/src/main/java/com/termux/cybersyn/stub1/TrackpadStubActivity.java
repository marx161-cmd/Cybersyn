package com.termux.cybersyn.stub1;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class TrackpadStubActivity extends Activity implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
    private static final int SURFACE_TINT = 0x1A003D1F;
    private static final String BROKER = "100.108.8.60";
    private static final int PORT = 1883;
    private static final String TOPIC_MOUSE = "cybersyn/hid/mouse";
    private static final String TOPIC_CLICK = "cybersyn/hid/click";
    private static final String TOPIC_SCROLL = "cybersyn/hid/scroll";

    private GestureDetector gestureDetector;
    private float previousX;
    private float previousY;
    private boolean dragging;
    private boolean scrolling;
    private double accumulatedScroll;
    private MqttPublisher publisher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        publisher = new MqttPublisher();
        gestureDetector = new GestureDetector(this, this);
        gestureDetector.setOnDoubleTapListener(this);

        View surface = new View(this);
        surface.setBackgroundColor(SURFACE_TINT);
        surface.setOnTouchListener((view, event) -> handleTouch(event));
        setContentView(surface);
    }

    @Override
    protected void onDestroy() {
        if (publisher != null) publisher.close();
        super.onDestroy();
    }

    private boolean handleTouch(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scrolling = false;
                previousX = event.getX();
                previousY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) {
                    scrolling = true;
                    accumulatedScroll += event.getY() - previousY;
                    if (Math.abs(accumulatedScroll) > 2.5) {
                        publish(TOPIC_SCROLL, Double.toString(accumulatedScroll));
                        accumulatedScroll = 0;
                    }
                } else if (!scrolling) {
                    float dx = event.getX() - previousX;
                    float dy = event.getY() - previousY;
                    if (dx != 0 || dy != 0) publish(TOPIC_MOUSE, dx + "," + dy);
                }
                previousX = event.getX();
                previousY = event.getY();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() == 2) publish(TOPIC_CLICK, "right");
                if (event.getPointerCount() == 3) publish(TOPIC_CLICK, "middle");
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                scrolling = false;
                return true;
            default:
                return true;
        }
    }

    private void publish(String topic, String payload) {
        MqttPublisher current = publisher;
        if (current != null) current.publish(topic, payload);
    }

    @Override public boolean onDown(MotionEvent e) { return false; }
    @Override public void onShowPress(MotionEvent e) {}
    @Override public boolean onSingleTapUp(MotionEvent e) { return false; }
    @Override public void onLongPress(MotionEvent e) { publish(TOPIC_CLICK, "hold"); dragging = true; }
    @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) { return false; }
    @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) { return false; }
    @Override public boolean onSingleTapConfirmed(MotionEvent e) { publishClick("left"); return true; }
    @Override public boolean onDoubleTap(MotionEvent e) { publishClick("double"); return true; }
    @Override public boolean onDoubleTapEvent(MotionEvent e) { return true; }

    private void publishClick(String type) {
        if (dragging) {
            publish(TOPIC_CLICK, "release");
            dragging = false;
        } else {
            publish(TOPIC_CLICK, type);
        }
    }

    private static final class MqttPublisher {
        // All socket I/O runs on this background thread. Publishing from the UI
        // thread (onTouch) would throw NetworkOnMainThreadException and silently
        // drop every packet, which is exactly the bug this replaces.
        private final HandlerThread thread;
        private final Handler handler;
        private Socket socket;
        private OutputStream out;
        private int packetId;

        MqttPublisher() {
            thread = new HandlerThread("cybersyn-stub1-mqtt");
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        void publish(String topic, String payload) {
            handler.post(() -> doPublish(topic, payload));
        }

        private void doPublish(String topic, String payload) {
            try {
                ensureConnected();
                byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                int remaining = 2 + topicBytes.length + payloadBytes.length;
                out.write(0x30);
                writeRemainingLength(out, remaining);
                writeUtf8(out, topicBytes);
                out.write(payloadBytes);
                out.flush();
            } catch (Exception ignored) {
                closeSocket();
            }
        }

        private void ensureConnected() throws Exception {
            if (socket != null && socket.isConnected() && !socket.isClosed()) return;
            socket = new Socket(BROKER, PORT);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            byte[] protocol = "MQTT".getBytes(StandardCharsets.UTF_8);
            byte[] clientId = ("cybersyn-stub1-" + android.os.Process.myPid() + "-" + (++packetId)).getBytes(StandardCharsets.UTF_8);
            int remaining = 2 + protocol.length + 1 + 1 + 2 + 2 + clientId.length;
            out.write(0x10);
            writeRemainingLength(out, remaining);
            writeUtf8(out, protocol);
            out.write(4);
            out.write(2);
            out.write(0);
            out.write(30);
            writeUtf8(out, clientId);
            out.flush();
            socket.getInputStream().read(new byte[4]);
        }

        private void closeSocket() {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            out = null;
            socket = null;
        }

        void close() {
            handler.post(this::closeSocket);
            thread.quitSafely();
        }

        private static void writeUtf8(OutputStream out, byte[] bytes) throws Exception {
            out.write((bytes.length >> 8) & 0xff);
            out.write(bytes.length & 0xff);
            out.write(bytes);
        }

        private static void writeRemainingLength(OutputStream out, int value) throws Exception {
            do {
                int encoded = value % 128;
                value /= 128;
                if (value > 0) encoded |= 128;
                out.write(encoded);
            } while (value > 0);
        }
    }
}
