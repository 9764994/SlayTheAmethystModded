import java.nio.charset.StandardCharsets;
import java.util.*;

public class ExecEnvTest {
    private static native byte[] rebuild(byte[] block);
    private static native void setNativeEnv();
    private static Map<String, String> entries(byte[] block) {
        if (block == null) throw new AssertionError("no environment block");
        Map<String, String> map = new HashMap<>();
        for (String entry : new String(block, StandardCharsets.UTF_8).split("\0")) {
            int equal = entry.indexOf('=');
            if (equal < 0) throw new AssertionError("bad entry");
            if (map.put(entry.substring(0, equal), entry.substring(equal + 1)) != null) throw new AssertionError("duplicate");
        }
        return map;
    }
    private static Map<String, String> paths() {
        return Map.of("PATH", "/plugin/ffmpeg", "LD_LIBRARY_PATH", "/plugin/ffmpeg");
    }
    public static void main(String[] args) {
        System.load(args[0]);
        Map<String, String> expected = new HashMap<>(System.getenv());
        setNativeEnv();
        expected.put("JNI_LATE_ENV", "set-after-System.getenv");
        expected.putAll(paths());
        if (!entries(rebuild(null)).equals(expected)) throw new AssertionError("inherited environment lost");
        if (!entries(rebuild(new byte[0])).equals(paths())) throw new AssertionError("explicit empty environment inherited");
        Map<String, String> explicit = new HashMap<>(paths());
        explicit.put("CUSTOM", "你好");
        byte[] block = "CUSTOM=你好\0PATH=old\0LD_LIBRARY_PATH=old\0".getBytes(StandardCharsets.UTF_8);
        if (!entries(rebuild(block)).equals(explicit)) throw new AssertionError("explicit environment changed");
        for (int i=0;i<100;i++) if (!entries(rebuild(null)).equals(expected)) throw new AssertionError();
        System.out.println("PASS exec env: inherited native snapshot, late setenv, explicit empty and populated blocks");
    }
}
