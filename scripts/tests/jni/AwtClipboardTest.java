import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

public class AwtClipboardTest {
    private static String text;
    public static String querySystemClipboard() { return text; }
    private static native void setup(Class<?> clazz, int mode);
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Class<?> c = Class.forName("net.java.openjdk.cacio.ctc.CTCClipboard");
        Clipboard clipboard = (Clipboard)c.getField("INSTANCE").get(null);
        Field checked = c.getDeclaredField("lastClipboardCheckTime");
        checked.setAccessible(true);
        for (int mode : new int[]{1, 2, 0, 0}) {
            checked.setLong(null, 0);
            setup(c, mode);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try { clipboard.getContents(null); }
                catch (Throwable e) { failure.set(e); }
            });
            t.setDaemon(true);
            t.start();
            t.join(3000);
            if (t.isAlive()) {
                t.interrupt(); t.join(3000);
                throw new AssertionError("clipboard query hung, mode=" + mode);
            }
            if (mode == 0 ? failure.get() != null : !(failure.get() instanceof IllegalStateException))
                throw new AssertionError("incorrect completion, mode=" + mode, failure.get());
            if (mode == 0) {
                if (text != null && !text.equals(clipboard.getContents(null).getTransferData(DataFlavor.stringFlavor)))
                    throw new AssertionError("clipboard text lost");
                text = "你好😀";
            }
        }
        System.out.println("PASS actual Cacio: attach/create failure, successful empty/text callbacks; no hangs");
    }
}
