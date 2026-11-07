package com.logvex.addon.schematicdropper;

import com.logvex.addon.LogAddon;
import com.logvex.addon.common.Messages;
import com.logvex.addon.schematicdropper.core.AcquisitionSnapshot;
import com.logvex.addon.schematicdropper.core.AcquisitionState;
import com.logvex.addon.schematicdropper.core.MaterialAcquisition;
import com.logvex.addon.schematicdropper.core.MaterialProgress;
import com.logvex.addon.schematicdropper.core.ProgressSnapshot;
import com.logvex.addon.schematicdropper.core.State;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Progress HUD for the dropper. Every value comes from {@link ProgressSnapshot}, which is itself
 * derived from confirmed transfers only, so the panel never shows progress the server did not accept.
 *
 * <p>The percentage bar animates towards the true value with a frame-rate independent easing, which
 * keeps a burst of transfers from making the bar jump. Rows are capped so an expanded panel cannot
 * grow past a third of the screen.
 */
public class SchematicProgressHud extends HudElement {
    public static final HudElementInfo<SchematicProgressHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "schematic-progress", "Schematic dropper progress.",
            SchematicProgressHud::new);

    private static final double BAR_WIDTH = 120;
    private static final int MAX_ROWS = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInfo = settings.createGroup("Information");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Compact shows the essentials; Expanded adds material details.")
        .defaultValue(Mode.Expanded).build());
    private final Setting<Boolean> showPercentage = sgInfo.add(new BoolSetting.Builder()
        .name("show-percentage").description("Show the progress percentage.").defaultValue(true).build());
    private final Setting<Boolean> showCounts = sgInfo.add(new BoolSetting.Builder()
        .name("show-counts").description("Show confirmed / total and remaining item counts.").defaultValue(true).build());
    private final Setting<Boolean> showCurrentMaterial = sgInfo.add(new BoolSetting.Builder()
        .name("show-current-material").description("Show the material currently being dropped.").defaultValue(true).build());
    private final Setting<Boolean> showMaterialSummary = sgInfo.add(new BoolSetting.Builder()
        .name("show-material-summary").description("Show how many material types are complete.").defaultValue(true).build());
    private final Setting<Boolean> showStatus = sgInfo.add(new BoolSetting.Builder()
        .name("show-status").description("Show the current execution state.").defaultValue(true).build());
    private final Setting<Boolean> showMissing = sgInfo.add(new BoolSetting.Builder()
        .name("show-missing").description("Show how many materials are missing.").defaultValue(true).build());
    private final Setting<Boolean> trackAcquisition = sgInfo.add(new BoolSetting.Builder()
        .name("track-acquisition").description("Show Creative acquisition progress while an acquisition is running.")
        .defaultValue(true).build());
    private final Setting<Boolean> showIcon = sgInfo.add(new BoolSetting.Builder()
        .name("show-material-icon").description("Show the icon of the current material.").defaultValue(true).build());

    private double animatedPercent;

    public SchematicProgressHud() {
        super(INFO);
    }

    /** Normalised view so the HUD can render either workflow with the same code. */
    private record HudData(double percent, long confirmed, long required, long remaining,
                           String currentMaterial, int currentCount, int completedTypes, int totalTypes,
                           int missingTypes, String status, Color statusColor, List<Row> rows) {
    }

    private record Row(String text, boolean warn) {
    }

    private HudData data(HudStyle style) {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        if (module == null) return fromDrops(ProgressSnapshot.idle(), style);
        if (trackAcquisition.get() && module.isAcquiring()) return fromAcquisition(module.acquisitionSnapshot(), style);
        return fromDrops(module.snapshot(), style);
    }

    private HudData fromDrops(ProgressSnapshot snap, HudStyle style) {
        List<Row> rows = new ArrayList<>();
        for (MaterialProgress m : snap.materials()) {
            if (m.complete()) continue;
            rows.add(new Row(m.displayName() + "  " + m.confirmed() + "/" + m.required()
                + (m.missing() > 0 ? "  (missing " + m.missing() + ")" : ""), m.missing() > 0));
        }
        return new HudData(snap.percent(), snap.confirmedDropped(), snap.totalRequired(), snap.remaining(),
            snap.currentMaterial(), snap.currentStackCount(), snap.completedTypes(), snap.totalTypes(),
            snap.missingTypes(), snap.state().toString(), statusColor(snap.state(), style), rows);
    }

    private HudData fromAcquisition(AcquisitionSnapshot snap, HudStyle style) {
        List<Row> rows = new ArrayList<>();
        for (MaterialAcquisition m : snap.materials()) {
            if (m.complete()) continue;
            rows.add(new Row(Messages.prettyId(m.itemId()) + "  " + m.acquired() + "/" + m.required(), false));
        }
        return new HudData(snap.percent(), snap.totalAcquired(), snap.totalRequired(), snap.totalRemaining(),
            snap.currentMaterial(), snap.currentAmount(), snap.materialsComplete(), snap.materialsTotal(),
            0, snap.state().toString(), acquisitionColor(snap.state(), style), rows);
    }

    private static Color acquisitionColor(AcquisitionState state, HudStyle style) {
        return switch (state) {
            case COMPLETED -> style.good();
            case ERROR -> style.bad();
            case WAITING_FOR_SPACE -> style.warn();
            case PAUSED -> style.warn();
            default -> style.text();
        };
    }

    private static Color statusColor(State state, HudStyle style) {
        return switch (state) {
            case COMPLETED -> style.good();
            case ERROR -> style.bad();
            case WAITING_FOR_INVENTORY -> style.warn();
            case PAUSED -> style.warn();
            default -> style.text();
        };
    }

    private boolean expanded() {
        return mode.get() == Mode.Expanded;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        HudData snap = data(style);

        double target = snap.percent();
        double factor = Math.min(1.0, Math.max(0.05, renderer.delta * 8.0));
        animatedPercent += (target - animatedPercent) * factor;
        if (Math.abs(target - animatedPercent) < 0.05) animatedPercent = target;

        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        double width = renderer.textWidth("Schematic Dropper", shadow, scale);
        width = Math.max(width, BAR_WIDTH + 30);
        for (String line : infoLines(snap)) width = Math.max(width, renderer.textWidth(line, shadow, scale));

        int rows = 0;
        if (expanded()) {
            for (Row row : snap.rows()) {
                if (rows >= MAX_ROWS) break;
                width = Math.max(width, renderer.textWidth(row.text(), shadow, scale));
                rows++;
            }
        }
        double height = lineHeight * (1 + infoLines(snap).size() + rows) + HudPanel.PAD * 2;
        setSize(width + HudPanel.PAD * 2, height);
    }

    /** The plain text rows, excluding the bar and the expanded material list. */
    private List<String> infoLines(HudData snap) {
        List<String> out = new ArrayList<>();
        if (showCounts.get()) {
            out.add(Messages.number(snap.confirmed()) + " / " + Messages.number(snap.required()) + " items");
            out.add(Messages.number(snap.remaining()) + " remaining");
        }
        if (showCurrentMaterial.get()) {
            out.add(snap.currentMaterial() == null ? "Current: -"
                : "Current: " + Messages.prettyId(snap.currentMaterial()) + " x" + snap.currentCount());
        }
        if (showMaterialSummary.get()) out.add("Materials: " + snap.completedTypes() + " / " + snap.totalTypes() + " complete");
        if (showMissing.get()) out.add("Missing: " + Messages.plural(snap.missingTypes(), "material"));
        if (showStatus.get()) out.add("Status: " + snap.status());
        return out;
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        HudData snap = data(style);
        double lineHeight = HudPanel.lineHeight(renderer, scale);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Schematic Dropper", scale, shadow);

        if (showPercentage.get()) {
            double barHeight = Math.max(4, renderer.textHeight(shadow, scale) - 2);
            renderer.bar(x + HudPanel.PAD, cursor, BAR_WIDTH, barHeight, animatedPercent / 100.0, style.accent(), style.track());
            renderer.text(String.format(Locale.ROOT, "%.1f%%", animatedPercent),
                x + HudPanel.PAD + BAR_WIDTH + 4, cursor - 1, style.text(), shadow, scale);
            cursor += lineHeight;
        }

        if (showCounts.get()) {
            HudPanel.line(renderer, this, Messages.number(snap.confirmed()) + " / " + Messages.number(snap.required()) + " items",
                cursor, style.text(), scale, shadow);
            cursor += lineHeight;
            HudPanel.line(renderer, this, Messages.number(snap.remaining()) + " remaining",
                cursor, style.muted(), scale, shadow);
            cursor += lineHeight;
        }

        if (showCurrentMaterial.get()) {
            if (snap.currentMaterial() == null) {
                HudPanel.line(renderer, this, "Current: -", cursor, style.muted(), scale, shadow);
                cursor += lineHeight;
            } else {
                double textX = x + HudPanel.PAD;
                if (showIcon.get()) {
                    ItemStack icon = iconFor(snap.currentMaterial());
                    if (!icon.isEmpty()) {
                        renderer.item(icon, (int) textX, (int) cursor, 1f, false);
                        textX += 18;
                    }
                }
                renderer.text("Current: " + Messages.prettyId(snap.currentMaterial()) + " x" + snap.currentCount(),
                    textX, cursor, style.text(), shadow, scale);
                cursor += lineHeight;
            }
        }

        if (showMaterialSummary.get()) {
            HudPanel.line(renderer, this, "Materials: " + snap.completedTypes() + " / " + snap.totalTypes() + " complete",
                cursor, style.muted(), scale, shadow);
            cursor += lineHeight;
        }
        if (showMissing.get()) {
            HudPanel.line(renderer, this, "Missing: " + Messages.plural(snap.missingTypes(), "material"),
                cursor, snap.missingTypes() > 0 ? style.warn() : style.muted(), scale, shadow);
            cursor += lineHeight;
        }
        if (showStatus.get()) {
            HudPanel.line(renderer, this, "Status: " + snap.status(), cursor, snap.statusColor(), scale, shadow);
            cursor += lineHeight;
        }

        if (expanded()) {
            int rows = 0;
            for (Row row : snap.rows()) {
                if (rows >= MAX_ROWS) break;
                HudPanel.line(renderer, this, row.text(), cursor, row.warn() ? style.bad() : style.muted(), scale, shadow);
                cursor += lineHeight;
                rows++;
            }
        }
    }

    private static ItemStack iconFor(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return ItemStack.EMPTY;
        Item item = Registries.ITEM.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    // ------------------------------------------------------------- lifecycle

    private static SchematicProgressHud instance;

    /** Adds the element to the HUD if it is not already present. */
    public static void enable() {
        Hud hud = Hud.get();
        hud.add(INFO, 4, 200);
        instance = (SchematicProgressHud) hud.instance(INFO);
    }

    /** Removes the element from the HUD. */
    public static void disable() {
        Hud hud = Hud.get();
        hud.remove(hud.instance(INFO));
        instance = null;
    }

    public enum Mode {
        Compact,
        Expanded
    }
}
