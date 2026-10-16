import java.nio.charset.StandardCharsets;

public class ClipboardTest {
    private static native String decode(int action, byte[] value);
    private static void check(String expected, int action, byte[] bytes) {
        String actual = decode(action, bytes);
        if (!expected.equals(actual)) throw new AssertionError("expected length " + expected.length()
                + ", got " + (actual == null ? "null" : actual.length()));
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) {
        System.load(args[0]);
        check("hello", 2000, utf8("hello\0"));
        check("hello", 2000, utf8("hello"));
        check("", 2000, new byte[0]);
        check("", 2000, new byte[]{0});
        check("你好😀", 2000, utf8("你好😀\0"));
        check("https://example.test/中文", 2002, utf8("https://example.test/中文\0second-argument\0"));
        if (decode(2001, null) != null) throw new AssertionError();
        System.out.println("PASS clipboard: bounded C strings, UTF-8, empty and argv boundaries");
    }
}
