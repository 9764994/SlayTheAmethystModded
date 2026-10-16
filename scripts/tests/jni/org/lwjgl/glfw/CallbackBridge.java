package org.lwjgl.glfw;

// Identity stand-in for the Android callback; exercise the actual native decoder.
public class CallbackBridge {
    public static String accessAndroidClipboard(int action, String text) { return text; }
}
