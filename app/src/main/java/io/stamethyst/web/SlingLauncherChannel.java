package io.stamethyst.web;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/** Per-view capability exposing only the three launcher callbacks. */
public final class SlingLauncherChannel {
    public final String token = UUID.randomUUID().toString();
    private volatile Object launcher;
    public final io.stamethyst.backend.audio.SlingNativeAudioBridge audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<String> scripts = new ArrayBlockingQueue<>(128);
    private volatile boolean closed;

    public SlingLauncherChannel(Object launcher) { this(launcher, null); }
    public SlingLauncherChannel(Object launcher, io.stamethyst.backend.audio.SlingNativeAudioBridge audio) { this.launcher = launcher; this.audio = audio; }
    public void setLauncher(Object launcher) { this.launcher = launcher; }
    /** Strict allowlist; never expose arbitrary reflection over HTTP. */
    public synchronized String audio(String method, String encoded) throws org.json.JSONException {
        if (closed || audio == null) return "null";
        org.json.JSONArray a = new org.json.JSONArray(encoded);
        long token = a.length() == 0 ? 0 : a.getLong(0);
        switch (method) {
            case "capabilities": return org.json.JSONObject.quote(audio.capabilities());
            case "begin": return org.json.JSONObject.quote(audio.begin());
            case "upload": return String.valueOf(audio.upload(token, a.getInt(1), a.getString(2)));
            case "commit": return String.valueOf(audio.commit(token));
            case "play": return String.valueOf(audio.play(token, a.getString(1)));
            case "playBatch": return org.json.JSONObject.quote(audio.playBatch(token, a.getString(1)));
            case "setState": return String.valueOf(audio.setState(token, a.getDouble(1), a.getBoolean(2), a.getBoolean(3)));
            case "setMuted": return String.valueOf(audio.setMuted(token, a.getBoolean(1)));
            case "snapshot": return org.json.JSONObject.quote(audio.snapshot(token));
            case "uploadMusic": return String.valueOf(audio.uploadMusic(token, a.getInt(1), a.getString(2)));
            case "playMusic": return String.valueOf(audio.playMusic(token, a.getInt(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getString(5), a.getBoolean(6)));
            case "musicState": return String.valueOf(audio.musicState(token, a.getString(1), a.getDouble(2), a.getBoolean(3)));
            case "musicFade": return String.valueOf(audio.musicFade(token, a.getDouble(1), a.getDouble(2)));
            case "release": audio.release(token); return "null";
            default: throw new org.json.JSONException("Unsupported audio method");
        }
    }
    public void evaluate(String script) {
        if (closed) return;
        if (!scripts.offer(script)) { scripts.poll(); scripts.offer(script); }
    }
    public String poll() {
        JSONArray commands = new JSONArray();
        String script;
        while ((script = scripts.poll()) != null) commands.put(script);
        return commands.toString();
    }
    public void dispatch(String method, String detail) {
        if (closed || !(method.equals("onPageReady") || method.equals("enterGame") || method.equals("scriptError"))) return;
        main.post(() -> {
            if (closed) return;
            Object target = launcher;
            if (target == null) return;
            try {
                java.lang.reflect.Method callback = method.equals("scriptError")
                    ? target.getClass().getDeclaredMethod(method, String.class)
                    : target.getClass().getDeclaredMethod(method);
                callback.setAccessible(true);
                if (method.equals("scriptError")) callback.invoke(target, detail);
                else callback.invoke(target);
            } catch (Exception e) { android.util.Log.e("SlingLauncherBridge", "Callback failed: " + method, e); }
        });
    }
    public synchronized void close() { closed = true; scripts.clear(); if (audio != null) audio.shutdown(); }
}
