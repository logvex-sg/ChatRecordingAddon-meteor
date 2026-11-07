package com.logvex.addon.update.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.update.core.UpdateChannel;
import com.logvex.addon.update.core.UpdateSnapshot;
import com.logvex.addon.update.core.UpdateState;
import com.logvex.addon.update.mc.UpdateModule;
import com.logvex.addon.update.mc.UpdateService;

/**
 * The update screen: current state, release notes, and the check/download/install actions.
 *
 * <p>Everything shown is read from the live {@link UpdateSnapshot}; the screen never invents a
 * progress value. Because the snapshot can change on the worker thread while the screen is open, the
 * screen registers a listener and reloads when it does, and unregisters on close so it is not kept
 * alive after the user leaves.
 */
public class UpdateScreen extends WindowScreen {
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color GOOD = new Color(150, 220, 150);
    private static final Color WARN = new Color(255, 200, 120);
    private static final Color BAD = new Color(255, 140, 140);
    private static final Color ACCENT = new Color(140, 200, 255);

    private final UpdateModule module;
    private final Runnable listener = this::onSnapshotChanged;
    private boolean listening;
    private boolean showNotes;

    public UpdateScreen(UpdateModule module) {
        super(GuiThemes.get(), "Updates");
        this.module = module;
    }

    private UpdateSnapshot snapshot() {
        return module == null ? UpdateService.get().snapshot() : module.snapshot();
    }

    private void onSnapshotChanged() {
        if (net.minecraft.client.MinecraftClient.getInstance().currentScreen == this) reload();
    }

    @Override
    public void initWidgets() {
        if (!listening) {
            UpdateService.get().addListener(listener);
            listening = true;
        }
        render();
    }

    @Override
    public void close() {
        if (listening) {
            UpdateService.get().removeListener(listener);
            listening = false;
        }
        super.close();
    }

    private void render() {
        clear();
        UpdateSnapshot snapshot = snapshot();

        WVerticalList header = add(theme.verticalList()).expandX().widget();
        header.add(theme.label("Installed version: " + snapshot.installedVersion())).expandX();
        header.add(theme.label(snapshot.statusLine()).color(colorFor(snapshot.state()))).expandX();
        header.add(theme.label("Last checked: " + snapshot.lastCheckedText()
            + "   \u2022   Channel: " + snapshot.channel().label())).expandX().widget().color(MUTED);

        if (snapshot.state() == UpdateState.FAILED && !snapshot.error().isBlank()) {
            header.add(theme.label(snapshot.error()).color(BAD)).expandX();
        }

        WTable actions = add(theme.table()).expandX().widget();
        boolean busy = snapshot.state() == UpdateState.CHECKING
            || snapshot.state() == UpdateState.DOWNLOADING;

        WButton check = actions.add(theme.button(busy ? "Working..." : "Check Now")).widget();
        check.action = () -> {
            if (busy) return;
            if (module != null) module.checkNow();
            else UpdateService.get().check();
        };

        if (snapshot.state() == UpdateState.AVAILABLE) {
            WButton download = actions.add(theme.button("Download Update")).widget();
            download.action = () -> {
                if (module != null) module.downloadNow();
                else UpdateService.get().download();
            };
        }

        if (!snapshot.releaseUrl().isBlank()) {
            WButton openPage = actions.add(theme.button("Open Release Page")).widget();
            openPage.action = () -> openUrl(snapshot.releaseUrl());
        }

        if (snapshot.isReady()) {
            WButton openFolder = actions.add(theme.button("Open Downloads Folder")).widget();
            openFolder.action = () -> openUrl(UpdateService.updatesDirectory().toUri().toString());
        }
        actions.row();

        WTable channels = add(theme.table()).expandX().widget();
        channels.add(theme.label("Channel:").color(MUTED)).widget();
        for (UpdateChannel channel : UpdateChannel.values()) {
            boolean selected = channel == snapshot.channel();
            WButton button = channels.add(theme.button((selected ? "\u00bb " : "") + channel.label())).widget();
            button.action = () -> {
                UpdateService.get().setChannel(channel);
                if (module != null) module.checkNow();
                else UpdateService.get().check();
                render();
            };
        }
        channels.row();

        if (snapshot.state() == UpdateState.DOWNLOADING) {
            int percent = (int) Math.round(snapshot.downloadProgress() * 100);
            add(theme.label("Downloading: " + percent + "%")).expandX().widget().color(ACCENT);
        }

        if (snapshot.hasUpdate()) {
            add(theme.horizontalSeparator("Release " + snapshot.latestVersion())).expandX();
            WVerticalList details = add(theme.verticalList()).expandX().widget();
            details.add(theme.label("Name: " + Messages.truncate(snapshot.releaseName(), 90))).expandX();
            if (!snapshot.publishedAt().isBlank()) {
                details.add(theme.label("Published: " + Messages.truncate(snapshot.publishedAt(), 20)))
                    .expandX().widget().color(MUTED);
            }
            String assetLine = "Asset: " + (snapshot.assetName().isEmpty() ? "none" : snapshot.assetName())
                + (snapshot.assetSize() > 0 ? " (" + Messages.bytes(snapshot.assetSize()) + ")" : "")
                + "   \u2022   checksum: " + (snapshot.checksumAvailable() ? "verified" : "not provided");
            details.add(theme.label(assetLine)).expandX().widget().color(MUTED);
            WButton toggle = details.add(theme.button(showNotes ? "Hide Release Notes" : "Show Release Notes")).widget();
            toggle.action = () -> {
                showNotes = !showNotes;
                render();
            };

            if (showNotes) {
                String notes = snapshot.releaseNotes().isBlank()
                    ? "This release has no notes."
                    : snapshot.releaseNotes();
                for (String line : notes.split("\\R")) {
                    details.add(theme.label(Messages.truncate(line, 120))).expandX().widget().color(MUTED);
                }
            }
        } else {
            add(theme.label(snapshot.state() == UpdateState.IDLE
                ? "Press Check Now to look for a newer release."
                : "No newer release on this channel.")).expandX().widget().color(MUTED);
        }

        if (snapshot.isReady()) {
            add(theme.horizontalSeparator("Ready to install")).expandX();
            add(theme.label("Downloaded to:")).expandX().widget().color(MUTED);
            add(theme.label(Messages.truncate(snapshot.downloadedPath(), 100))).expandX().widget().color(GOOD);
            add(theme.label("Close Minecraft, drop this jar into your mods folder and remove the old one. "
                + "Log Client never replaces its own jar while it is running.")).expandX().widget().color(WARN);
        }

        add(theme.label("Updates are downloaded from " + UpdateService.OWNER + "/" + UpdateService.REPO
            + " and verified by SHA-256 when the release provides one.")).expandX().widget().color(MUTED);

        add(theme.button("Close", () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(parent))).expandX();
    }

    private void openUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
        } catch (RuntimeException e) {
            // A malformed or unsupported URL is not worth a crash; the status text already tells the story.
        }
    }

    private Color colorFor(UpdateState state) {
        return switch (state) {
            case UP_TO_DATE, READY -> GOOD;
            case AVAILABLE, DOWNLOADING, CHECKING -> ACCENT;
            case FAILED -> BAD;
            default -> MUTED;
        };
    }
}
