package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.CreativeController;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.automation.mc.MinecraftCreativeEnv;
import com.logvex.addon.automation.mc.MinecraftCreativePort;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.notify.core.NotificationType;

import java.util.ArrayList;
import java.util.List;

/**
 * Acquires a list of items automatically in Creative mode, then hands them to the Schematic Dropper
 * queue so the existing ordered dropping workflow takes over.
 *
 * <p>Acquisition is the real, server-synchronised Creative give implemented by
 * {@link MinecraftCreativePort}: a give is only counted once the server has acknowledged it, so a
 * client-only prediction is never reported as success. In Survival, or without the creative
 * permission, the module reports that clearly and does nothing — it never fabricates items.
 *
 * <p>The list may be typed in the settings, loaded from a file, or imported straight from a
 * material list with the Schematic Dropper's importer.
 */
public class AutoCreativeModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBehaviour = settings.createGroup("Behaviour");

    private final Setting<List<String>> items = sgGeneral.add(new StringListSetting.Builder()
        .name("items")
        .description("Items to acquire, as 'namespace:id amount' or just 'namespace:id' for one.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<String> materialFile = sgGeneral.add(new com.logvex.addon.framework.settings.StringSetting.Builder()
        .name("material-file")
        .description("Full path to a material list to import, used by \"Import Path\".")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> autoStart = sgBehaviour.add(new BoolSetting.Builder()
        .name("auto-start")
        .description("Start acquiring as soon as the module is enabled.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sendToDropper = sgBehaviour.add(new BoolSetting.Builder()
        .name("queue-to-dropper")
        .description("Hand the acquired list to the Schematic Dropper's ordered queue when finished.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retryLimit = sgBehaviour.add(new IntSetting.Builder()
        .name("retry-limit")
        .description("How many times a rejected give is retried before the item is reported failed.")
        .defaultValue(3)
        .sliderRange(1, 10)
        .build()
    );

    private final MinecraftCreativeEnv env = new MinecraftCreativeEnv();
    private final MinecraftCreativePort port = new MinecraftCreativePort();
    private final CreativeController controller = new CreativeController(port, env, 3);

    public AutoCreativeModule() {
        super("auto-creative", "Automatically acquires items in Creative mode and can queue them for dropping.");
    }

    @Override
    public void onActivate() {
        controller.reset();
        controller.setRequests(parseItems(items.get()));
        info("Auto Creative on (%s requested).",
            com.logvex.addon.common.Messages.plural(parseItems(items.get()).size(), "item"));
        if (autoStart.get()) start();
    }

    @Override
    public void onDeactivate() {
        controller.stop();
        info("Auto Creative off.");
    }

    @Override
    protected void onWorldLeave() {
        controller.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready() || !controller.running()) return;

        boolean wasRunning = controller.running();
        controller.tick();

        if (wasRunning && controller.finished()) {
            reportFinished();
        }
    }

    /** Parses the setting into requests, ignoring malformed lines. */
    public static List<CreativeController.Request> parseItems(List<String> lines) {
        List<CreativeController.Request> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            String[] parts = line.trim().split("\\s+");
            String id = parts[0].trim();
            if (id.isEmpty()) continue;
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (amount > 0) out.add(new CreativeController.Request(id, amount));
        }
        return out;
    }

    // ------------------------------------------------------------------ control

    public void start() {
        controller.setRequests(parseItems(items.get()));
        controller.setRetryLimit(retryLimit.get());
        if (controller.items().isEmpty()) {
            warning("Nothing to acquire: add items first.");
            return;
        }
        if (!AutomationSupport.isCreative()) {
            warning("Creative mode is required to acquire items.");
            notifyUser(NotificationType.TASK_FAILED, "Auto Creative", "Creative mode is required.");
            return;
        }
        controller.start();
        info("Acquiring %s.", com.logvex.addon.common.Messages.plural(controller.items().size(), "item"));
    }

    public void pause() {
        controller.pause();
        info("Acquisition paused.");
    }

    public void resume() {
        controller.resume();
        info("Acquisition resumed.");
    }

    public void stop() {
        controller.stop();
        info("Acquisition stopped.");
    }

    public void retry(String itemId) {
        if (controller.retryItem(itemId)) info("Retrying %s.", itemId);
        else warning("No such item in the list: %s.", itemId);
    }

    public void restartCurrent() {
        if (controller.restartActive()) info("Restarting the current item.");
        else warning("Nothing is being acquired.");
    }

    public CreativeController controller() {
        return controller;
    }

    // ------------------------------------------------------------------ import

    /** Opens the built-in file browser to choose a material list to acquire. */
    public void importMaterialFile() {
        mc.setScreen(new com.logvex.addon.schematicdropper.gui.FileBrowserScreen(
            new java.io.File(com.logvex.addon.framework.Addon.FOLDER(), "schematicdropper"),
            "Select Material List",
            java.util.List.of(".txt", ".md", ".csv"),
            file -> importMaterialPath(file.getAbsolutePath())));
    }

    /** Imports the material list named by the {@code material-file} setting. */
    public void importMaterialPath() {
        String path = materialFile.get();
        if (path == null || path.isBlank()) {
            warning("Set \"material-file\" to the full path of a material list first.");
            return;
        }
        importMaterialPath(path);
    }

    /**
     * Imports a material list from an explicit path, parsing the entire file, validating every entry
     * against the item registry and reporting invalid ones. The result becomes the acquisition queue
     * in file order, with duplicates merged onto their first occurrence.
     */
    public void importMaterialPath(String path) {
        if (path == null || path.isBlank()) {
            warning("No file path given.");
            return;
        }
        java.io.File file = new java.io.File(path.trim());
        if (!file.isFile()) {
            error("That file could not be read: " + file.getName());
            notifyUser(NotificationType.CONFIG_ERROR, "Auto Creative", "Could not read " + file.getName());
            return;
        }
        materialFile.set(file.getAbsolutePath());

        List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            error("Could not read " + file.getName() + ".");
            org.slf4j.LoggerFactory.getLogger(getClass()).debug("Material import failed", e);
            return;
        }

        var result = com.logvex.addon.schematicdropper.core.MaterialListImporter.parse(
            lines, new com.logvex.addon.schematicdropper.mc.RegistryItemResolver());
        if (result.isEmpty()) {
            warning("No usable materials found in " + file.getName() + ".");
            return;
        }

        List<String> entries = new ArrayList<>(result.requirements().size());
        for (var requirement : result.requirements()) {
            entries.add(requirement.itemId() + " " + requirement.required());
        }
        items.set(entries);
        controller.reset();
        controller.setRequests(parseItems(entries));

        info("%s", com.logvex.addon.schematicdropper.mc.SchematicFileImport.describe(result, file.getName()));
        int shown = 0;
        for (var invalid : result.invalid()) {
            if (shown++ >= 10) break;
            warning("%s", invalid);
        }
        if (result.invalid().size() > 10) {
            warning("Skipped %s more invalid entries; see the debug log.",
                com.logvex.addon.common.Messages.number(result.invalid().size() - 10));
            for (var invalid : result.invalid()) {
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("Invalid entry: {}", invalid);
            }
        }
        if (!result.requirements().isEmpty()) {
            var first = result.requirements().get(0);
            var last = result.requirements().get(result.requirements().size() - 1);
            info("First: %s x%s. Last: %s x%s.",
                com.logvex.addon.common.Messages.prettyId(first.itemId()), first.required(),
                com.logvex.addon.common.Messages.prettyId(last.itemId()), last.required());
        }
    }

    /** The item list as {@code "id amount"} lines, for display and for the dropper queue. */
    public List<String> itemEntries() {
        return List.copyOf(items.get());
    }

    private void reportFinished() {
        int done = controller.completedCount();
        int failed = controller.failedCount();
        if (failed == 0) {
            info("Acquired %s.", com.logvex.addon.common.Messages.plural(done, "item"));
            notifyUser(NotificationType.TASK_COMPLETED, "Auto Creative", "Acquired " + done + " items.");
        } else {
            warning("Finished with %d failed.", failed);
            notifyUser(NotificationType.TASK_FAILED, "Auto Creative", done + " acquired, " + failed + " failed.");
        }
        if (sendToDropper.get()) queueToDropper();
    }

    private void queueToDropper() {
        var modules = com.logvex.addon.framework.systems.modules.Modules.get();
        var dropper = modules.get(com.logvex.addon.schematicdropper.SchematicDropperModule.class);
        if (dropper == null) {
            warning("Schematic Dropper is not available.");
            return;
        }
        if (!dropper.isActive()) {
            warning("Enable Schematic Dropper first to queue the acquired list.");
            return;
        }
        // Hand the exact ordered list over, in "id amount" form, so the dropper's own parser and
        // plan do the validation rather than this module duplicating it.
        List<String> entries = new ArrayList<>();
        for (CreativeController.Item item : controller.items()) {
            if (item.status() == CreativeController.ItemStatus.DONE) {
                entries.add(item.itemId() + " " + item.required());
            }
        }
        if (entries.isEmpty()) {
            warning("Nothing was acquired, so the queue was not changed.");
            return;
        }
        int queued = dropper.setMaterialEntries(entries);
        if (queued < 0) {
            warning("Schematic Dropper is not enabled; the list was not queued.");
            return;
        }
        info("Queued %s in Schematic Dropper.", com.logvex.addon.common.Messages.plural(queued, "material"));
        notifyUser(NotificationType.TASK_COMPLETED, "Auto Creative",
            "Queued " + queued + " materials for dropping.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Acquisition Manager")).expandX().widget();
        open.action = () -> mc.setScreen(new com.logvex.addon.automation.gui.AcquisitionScreen(this));
        WButton start = list.add(theme.button("Start")).expandX().widget();
        start.action = this::start;
        WButton pause = list.add(theme.button("Pause")).expandX().widget();
        pause.action = this::pause;
        WButton resume = list.add(theme.button("Resume")).expandX().widget();
        resume.action = this::resume;
        WButton stop = list.add(theme.button("Stop")).expandX().widget();
        stop.action = this::stop;
        return list;
    }

    @Override
    public String getInfoString() {
        if (!controller.running()) return "idle";
        return controller.status();
    }
}
