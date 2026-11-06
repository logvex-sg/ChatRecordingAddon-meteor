package com.logvex.addon.schematicdropper.mc;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.function.Supplier;

/**
 * Native file-picker helper.
 *
 * <p>tinyfd's C API takes a {@code NULL}-terminated array of filter patterns. LWJGL passes the
 * buffer's remaining capacity as the element count, so a buffer without the trailing {@code NULL}
 * makes the native layer read one pointer past the filters. That is undefined behaviour and it
 * crashed the JVM when a dialog opened. Every call therefore goes through {@link #terminatedFilters}
 * so the terminator is never forgotten.
 */
public final class FileDialogs {
    private FileDialogs() {
    }

    /**
     * Returns {@code patterns} followed by a trailing {@code null}, matching tinyfd's contract.
     * The returned array is what the LWJGL buffer is built from.
     */
    public static String[] terminatedFilters(String... patterns) {
        String[] out = new String[patterns.length + 1];
        System.arraycopy(patterns, 0, out, 0, patterns.length);
        out[patterns.length] = null;
        return out;
    }

    /**
     * Opens the native open-file dialog.
     *
     * @param title        dialog title
     * @param initialDir   directory the dialog starts in
     * @param patterns     extension filters, e.g. {@code "*.txt"}
     * @return the chosen path, or {@code null} when the user cancels or the dialog cannot open
     */
    public static String openFile(String title, String initialDir, String... patterns) {
        String[] terminated = terminatedFilters(patterns);
        PointerBuffer filters = MemoryUtil.memAllocPointer(terminated.length);
        try {
            for (String pattern : terminated) {
                if (pattern == null) filters.put(0L);
                else filters.put(MemoryUtil.memUTF8(pattern));
            }
            filters.flip();
            return TinyFileDialogs.tinyfd_openFileDialog(title, initialDir, filters, null, false);
        } finally {
            MemoryUtil.memFree(filters);
        }
    }

    /** The current working directory, used as the dialog's starting folder. */
    public static String workingDirectory() {
        return new File(".").getAbsolutePath();
    }

    /**
     * Opens the dialog, returning {@code null} when the native library is unavailable or the dialog
     * fails. Callers treat {@code null} as "no file chosen" and offer the typed-path fallback, so a
     * broken dialog never propagates an exception into a GUI action.
     */
    public static String openFileSafely(Supplier<String> opener) {
        try {
            return opener.get();
        } catch (Throwable t) {
            return null;
        }
    }
}
