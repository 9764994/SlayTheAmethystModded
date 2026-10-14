/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;

import io.stamethyst.backend.file_interactive.FileLinkOpener;

public class MainActivity {
    public static ClipboardManager GLOBAL_CLIPBOARD;
    private static Context sApplicationContext;

    public static synchronized void init(Context applicationContext) {
        if (applicationContext == null) {
            return;
        }
        sApplicationContext = applicationContext.getApplicationContext();
        GLOBAL_CLIPBOARD = (ClipboardManager) sApplicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private static Context requireContext() {
        if (sApplicationContext == null) {
            throw new IllegalStateException("MainActivity not initialized");
        }
        return sApplicationContext;
    }

    public static void openLink(String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        FileLinkOpener.open(requireContext(), value);
    }

    public static String querySystemClipboard() {
        if (GLOBAL_CLIPBOARD == null || !GLOBAL_CLIPBOARD.hasPrimaryClip()) {
            return null;
        }
        ClipData clipData = GLOBAL_CLIPBOARD.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clipData.getItemAt(0).coerceToText(requireContext());
        if (text == null) {
            return null;
        }
        return text.toString();
    }

    public static void putClipboardData(String data, String mimeTypeSub) {
        if (GLOBAL_CLIPBOARD == null) {
            return;
        }
        GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("AWT", data == null ? "" : data));
    }
}
