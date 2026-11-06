package com.logvex.addon.schematicdropper.gui;

import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A file browser built from the addon's own widgets.
 *
 * <p>It exists because the native tinyfd picker does not work everywhere: on some Linux desktops and
 * on headless setups {@code tinyfd_openFileDialog} returns null, so a "Load Schematic" button that
 * relied on it looked broken. This browser has no native dependency, so loading a file always has a
 * path that works. The list is paged and only the current page of entries is built, so opening a
 * directory with thousands of files stays responsive.
 */
public class FileBrowserScreen extends WindowScreen {
    private static final Color MUTED = new Color(150, 150, 150);
    private static final int PAGE_SIZE = 200;

    private final File directory;
    private final String title;
    private final List<String> extensions;
    private final Consumer<File> onChosen;

    private int page;

    /**
     * @param directory  folder to start in
     * @param title      window title
     * @param extensions lower-case extensions to accept, e.g. {@code ".schem"}; empty accepts all
     * @param onChosen   called with the chosen file; the screen closes afterwards
     */
    public FileBrowserScreen(File directory, String title, List<String> extensions, Consumer<File> onChosen) {
        super(GuiThemes.get(), title);
        this.directory = directory;
        this.title = title;
        this.extensions = extensions;
        this.onChosen = onChosen;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        add(theme.label(shorten(directory.getAbsolutePath()))).expandX().widget().color(MUTED);
        add(theme.horizontalSeparator()).expandX();

        WTable controls = add(theme.table()).expandX().widget();
        WButton up = controls.add(theme.button("Up")).widget();
        up.action = () -> open(directory.getParentFile());
        WButton home = controls.add(theme.button("Data Folder")).widget();
        home.action = () -> open(new File(Addon.FOLDER(), "schematicdropper"));

        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) {
            add(theme.label("This folder cannot be read.")).expandX().widget().color(MUTED);
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.isDirectory()) {
                if (!child.getName().startsWith(".")) dirs.add(child);
            } else if (matches(child)) {
                files.add(child);
            }
        }

        List<File> entries = new ArrayList<>(dirs.size() + files.size());
        entries.addAll(dirs);
        entries.addAll(files);

        if (entries.isEmpty()) {
            add(theme.label(extensions.isEmpty()
                ? "This folder is empty."
                : "No matching files here. Showing folders only.")).expandX().widget().color(MUTED);
            return;
        }

        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, totalPages - 1);
        int from = page * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);

        WTable table = add(theme.table()).expandX().widget();
        for (int i = from; i < to; i++) {
            File entry = entries.get(i);
            boolean isDir = entry.isDirectory();
            String label = entry.getName() + (isDir ? "/" : "  (" + size(entry) + ")");
            WButton button = table.add(theme.button(label)).expandCellX().widget();
            button.action = () -> {
                if (isDir) open(entry);
                else choose(entry);
            };
            table.row();
        }

        if (totalPages > 1) {
            WTable pages = add(theme.table()).expandX().widget();
            WButton prev = pages.add(theme.button("Previous")).widget();
            prev.action = () -> {
                if (page > 0) {
                    page--;
                    render();
                }
            };
            pages.add(theme.label("Page " + (page + 1) + " / " + totalPages)).widget().color(MUTED);
            WButton next = pages.add(theme.button("Next")).widget();
            next.action = () -> {
                if (page < totalPages - 1) {
                    page++;
                    render();
                }
            };
        }
    }

    private void open(File target) {
        if (target == null || !target.isDirectory()) return;
        page = 0;
        net.minecraft.client.MinecraftClient.getInstance()
            .setScreen(new FileBrowserScreen(target, title, extensions, onChosen));
    }

    private void choose(File file) {
        if (onChosen != null) onChosen.accept(file);
        close();
    }

    private boolean matches(File file) {
        if (extensions.isEmpty()) return true;
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    private static String size(File file) {
        long bytes = file.length();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    private static String shorten(String path) {
        return path.length() <= 90 ? path : "..." + path.substring(path.length() - 87);
    }

    @Override
    public boolean toClipboard() {
        return false;
    }

    @Override
    public boolean fromClipboard() {
        return false;
    }
}
