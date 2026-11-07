package com.logvex.addon.automation.gui;

import com.logvex.addon.automation.core.CreativeController;
import com.logvex.addon.automation.modules.AutoCreativeModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * The acquisition manager: what is being acquired, how far it has got, and the controls.
 *
 * <p>Every number shown is read from the live {@link CreativeController}, which in turn reads the
 * real inventory, so the screen can never show progress the server did not confirm. Items that
 * cannot be acquired are shown as failed with the reason, rather than being silently skipped.
 */
public class AcquisitionScreen extends WindowScreen {
    private final AutoCreativeModule module;
    private String filter = "";

    public AcquisitionScreen(AutoCreativeModule module) {
        super(GuiThemes.get(), "Acquisition");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        CreativeController controller = module.controller();

        // ------------------------------------------------------------ status
        WTable status = add(theme.table()).expandX().widget();
        status.add(theme.label("State")).widget().color(theme.muted);
        status.add(theme.label(stateLabel(controller))).expandCellX().widget();
        status.add(theme.label("Progress")).widget().color(theme.muted);
        status.add(theme.label(Messages.percent(controller.progress()))).widget();
        status.finish();

        add(theme.label("Completed: " + controller.completedCount()
            + "   Failed: " + controller.failedCount()
            + "   Remaining: " + Math.max(0, controller.items().size()
                - controller.completedCount() - controller.failedCount()))).expandX();
        if (controller.lastError() != null) {
            add(theme.label(controller.lastError()).color(theme.bad)).expandX();
        }

        // ------------------------------------------------------------ controls
        add(theme.horizontalSeparator("Controls")).expandX();
        WHorizontalList row1 = add(theme.horizontalList()).expandX().widget();
        button(row1, "Start", module::start);
        button(row1, "Pause", module::pause);
        button(row1, "Resume", module::resume);
        button(row1, "Stop", module::stop);

        WHorizontalList row2 = add(theme.horizontalList()).expandX().widget();
        button(row2, "Retry Failed", () -> {
            CreativeController c = module.controller();
            for (CreativeController.Item item : c.items()) {
                if (item.status() == CreativeController.ItemStatus.FAILED) c.retryItem(item.itemId());
            }
        });
        button(row2, "Restart Current", module::restartCurrent);
        button(row2, "Refresh", this::reload);

        WHorizontalList row3 = add(theme.horizontalList()).expandX().widget();
        button(row3, "Import File", module::importMaterialFile);
        button(row3, "Import Path", module::importMaterialPath);

        // ------------------------------------------------------------ list
        add(theme.horizontalSeparator("Materials")).expandX();
        WTextBox search = add(theme.textBox(filter, "Filter materials...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            reload();
        };

        String needle = filter.trim().toLowerCase(Locale.ROOT);
        List<CreativeController.Item> items = controller.items();
        if (items.isEmpty()) {
            add(theme.label("No materials queued. Add items in the Auto Creative settings or import a material list.")
                .color(theme.muted)).expandX();
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Item")).widget().color(theme.muted);
        table.add(theme.label("Required")).widget().color(theme.muted);
        table.add(theme.label("Held")).widget().color(theme.muted);
        table.add(theme.label("Need")).widget().color(theme.muted);
        table.add(theme.label("Status")).widget().color(theme.muted);
        table.finish();

        int shown = 0;
        for (CreativeController.Item item : items) {
            if (!needle.isEmpty() && !item.itemId().toLowerCase(Locale.ROOT).contains(needle)) continue;
            WTable line = add(theme.table()).expandX().widget();
            line.add(icon(item.itemId()));
            line.add(theme.label(Messages.prettyId(item.itemId()))).expandCellX().widget();
            line.add(theme.label(Messages.number(item.required()))).widget();
            line.add(theme.label(Messages.number(item.held()))).widget();
            line.add(theme.label(Messages.number(item.remaining()))).widget();
            line.add(theme.label(statusText(item))).widget().color(statusColor(item));
            WButton retry = line.add(theme.button("Retry")).widget();
            retry.action = () -> {
                controller.retryItem(item.itemId());
                reload();
            };
            line.finish();
            shown++;
        }
        if (shown == 0) {
            add(theme.label("No materials match \"" + filter + "\".").color(theme.muted)).expandX();
        }
    }

    private void button(WHorizontalList row, String text, Runnable action) {
        WButton button = row.add(theme.button(text)).expandX().widget();
        button.action = () -> {
            action.run();
            reload();
        };
    }

    private static String stateLabel(CreativeController controller) {
        if (!controller.running()) return "Idle";
        if (controller.paused()) return "Paused";
        if (controller.finished()) return "Finished";
        return "Running";
    }

    private static String statusText(CreativeController.Item item) {
        return switch (item.status()) {
            case PENDING -> "Waiting";
            case ACQUIRING -> "Acquiring";
            case DONE -> "Done";
            case FAILED -> item.failure() == null ? "Failed" : "Failed: " + item.failure();
        };
    }

    private com.logvex.addon.framework.utils.render.color.Color statusColor(CreativeController.Item item) {
        return switch (item.status()) {
            case DONE -> theme.good;
            case FAILED -> theme.bad;
            case ACQUIRING -> theme.accent;
            case PENDING -> theme.muted;
        };
    }

    /** A small item icon widget, or a text fallback when the id is unknown. */
    private com.logvex.addon.framework.gui.widgets.WWidget icon(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        var item = id == null ? null : Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) {
            return theme.label("?");
        }
        return new ItemIconWidget(new ItemStack(item));
    }
}
