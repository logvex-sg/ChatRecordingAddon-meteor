package com.logvex.addon.schematicdropper;

import com.logvex.addon.LogAddon;
import com.logvex.addon.schematicdropper.core.MaterialProgress;
import com.logvex.addon.schematicdropper.core.ProgressSnapshot;
import com.logvex.addon.schematicdropper.core.State;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Progress HUD for the dropper. Every value comes from {@link ProgressSnapshot}, which is itself
 * derived from confirmed transfers only.
 */
public class SchematicProgressHud extends HudElement {
    public static final HudElementInfo<SchematicProgressHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "schematic-progress", "Schematic dropper progress.", SchematicProgressHud::new);

    private static final Color TEXT = new Color(255, 255, 255);
    private static final Color MUTED = new Color(160, 160, 160);
    private static final Color TRACK = new Color(40, 40, 40, 200);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInfo = settings.createGroup("Information");
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Compact shows the essentials; Expanded adds material details.")
        .defaultValue(Mode.Expanded)
        .build()
    );

    private final Setting<Boolean> showPercentage = sgInfo.add(new BoolSetting.Builder()
        .name("show-percentage")
        .description("Show the progress percentage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCounts = sgInfo.add(new BoolSetting.Builder()
        .name("show-counts")
        .description("Show confirmed / total and remaining item counts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCurrentMaterial = sgInfo.add(new BoolSetting.Builder()
        .name("show-current-material")
        .description("Show the material currently being dropped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showMaterialSummary = sgInfo.add(new BoolSetting.Builder()
        .name("show-material-summary")
        .description("Show how many material types are complete.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showStatus = sgInfo.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Show the current execution state.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showMissing = sgInfo.add(new BoolSetting.Builder()
        .name("show-missing")
        .description("Show how many materials are missing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showIcon = sgInfo.add(new BoolSetting.Builder()
        .name("show-material-icon")
        .description("Show the icon of the current material.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a background behind the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgStyle.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color.")
        .defaultValue(new SettingColor(15, 15, 18, 170))
        .visible(background::get)
        .build()
    );

    private final Setting<Boolean> border = sgStyle.add(new BoolSetting.Builder()
        .name("border")
        .description("Draw a border around the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> borderColor = sgStyle.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color.")
        .defaultValue(new SettingColor(90, 90, 110))
        .visible(border::get)
        .build()
    );

    private final Setting<SettingColor> accentColor = sgStyle.add(new ColorSetting.Builder()
        .name("accent-color")
        .description("Progress bar fill color.")
        .defaultValue(new SettingColor(120, 200, 255))
        .build()
    );

    private final Setting<Boolean> customScale = sgStyle.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Use a custom text scale instead of the global one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgStyle.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .visible(customScale::get)
        .build()
    );

    private double animatedPercent;

    public SchematicProgressHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private ProgressSnapshot snapshot() {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        return module == null ? ProgressSnapshot.idle() : module.snapshot();
    }

    @Override
    public void tick(HudRenderer renderer) {
        ProgressSnapshot snap = snapshot();
        double target = snap.percent();
        double factor = Math.min(1.0, Math.max(0.05, renderer.delta * 8.0));
        animatedPercent += (target - animatedPercent) * factor;
        if (Math.abs(target - animatedPercent) < 0.05) animatedPercent = target;

        double w = 0;
        double h = 0;

        double titleH = renderer.textHeight(true, getScale());
        w = Math.max(w, renderer.textWidth("SchematicDropper", true, getScale()));
        h += titleH + 2;

        double barWidth = 120;
        if (showPercentage.get()) {
            w = Math.max(w, barWidth);
            h += renderer.textHeight(true, getScale()) + 2;
        }
        if (showCounts.get()) {
            w = Math.max(w, barWidth);
            h += renderer.textHeight(true, getScale()) * 2 + 2;
        }
        if (showCurrentMaterial.get()) {
            w = Math.max(w, barWidth);
            h += renderer.textHeight(true, getScale()) + 2;
        }
        if (showMaterialSummary.get()) {
            w = Math.max(w, barWidth);
            h += renderer.textHeight(true, getScale()) + 2;
        }
        if (showMissing.get()) {
            w = Math.max(w, barWidth);
            h += renderer.textHeight(true, getScale()) + 2;
        }
        if (showStatus.get()) {
            w = Math.max(w, barWidth);
            h += renderer.textHeight(true, getScale()) + 2;
        }

        if (mode.get() == Mode.Expanded) {
            h += 2;
            for (MaterialProgress m : snap.materials()) {
                if (m.complete()) continue;
                w = Math.max(w, renderer.textWidth(m.displayName(), true, getScale()) + 40);
                h += renderer.textHeight(true, getScale()) + 1;
                if (h > 160) break;
            }
        }

        setSize(w + 6, h + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        ProgressSnapshot snap = snapshot();
        double scaleValue = getScale();
        double lineH = renderer.textHeight(true, scaleValue) + 2;
        double left = x + 3;
        double top = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
        if (border.get()) {
            Color c = borderColor.get();
            renderer.quad(x, y, getWidth(), 1, c);
            renderer.quad(x, y + getHeight() - 1, getWidth(), 1, c);
            renderer.quad(x, y, 1, getHeight(), c);
            renderer.quad(x + getWidth() - 1, y, 1, getHeight(), c);
        }

        double cursor = top;
        renderer.text("SchematicDropper", left, cursor, accentColor.get(), true, scaleValue);
        cursor += lineH;

        double barWidth = 120;
        double barHeight = Math.max(4, renderer.textHeight(true, scaleValue) - 2);

        if (showPercentage.get()) {
            double filled = barWidth * (animatedPercent / 100.0);
            renderer.quad(left, cursor, barWidth, barHeight, TRACK);
            if (filled > 0) renderer.quad(left, cursor, filled, barHeight, accentColor.get());
            renderer.text(String.format("%.1f%%", animatedPercent), left + barWidth + 3, cursor - 1, TEXT, true, scaleValue);
            cursor += lineH;
        }

        if (showCounts.get()) {
            renderer.text(snap.confirmedDropped() + " / " + snap.totalRequired() + " items", left, cursor, TEXT, true, scaleValue);
            cursor += lineH;
            renderer.text(snap.remaining() + " remaining", left, cursor, MUTED, true, scaleValue);
            cursor += lineH;
        }

        if (showCurrentMaterial.get()) {
            String name = snap.currentMaterial();
            if (name == null) {
                renderer.text("Current: -", left, cursor, MUTED, true, scaleValue);
            } else {
                double textX = left;
                if (showIcon.get()) {
                    ItemStack icon = iconFor(name);
                    if (!icon.isEmpty()) {
                        renderer.item(icon, (int) left, (int) cursor, 1f, false);
                        textX = left + 18;
                    }
                }
                renderer.text("Current: " + shortName(name) + " x" + snap.currentStackCount(), textX, cursor, TEXT, true, scaleValue);
            }
            cursor += lineH;
        }

        if (showMaterialSummary.get()) {
            renderer.text("Materials: " + snap.completedTypes() + " / " + snap.totalTypes() + " complete",
                left, cursor, MUTED, true, scaleValue);
            cursor += lineH;
        }

        if (showMissing.get()) {
            renderer.text("Missing: " + snap.missingTypes() + " material(s)", left, cursor,
                snap.missingTypes() > 0 ? new Color(255, 190, 120) : MUTED, true, scaleValue);
            cursor += lineH;
        }

        if (showStatus.get()) {
            State state = snap.state();
            renderer.text("Status: " + state, left, cursor, statusColor(state), true, scaleValue);
            cursor += lineH;
        }

        if (mode.get() == Mode.Expanded) {
            cursor += 2;
            for (MaterialProgress m : snap.materials()) {
                if (m.complete()) continue;
                String text = m.displayName() + "  " + m.confirmed() + "/" + m.required()
                    + (m.missing() > 0 ? "  (missing " + m.missing() + ")" : "");
                renderer.text(text, left, cursor, m.missing() > 0 ? new Color(255, 160, 160) : MUTED, true, scaleValue);
                cursor += lineH - 1;
                if (cursor > y + getHeight()) break;
            }
        }
    }

    private Color statusColor(State state) {
        return switch (state) {
            case COMPLETED -> new Color(140, 235, 140);
            case ERROR -> new Color(255, 120, 120);
            case WAITING_FOR_INVENTORY -> new Color(255, 200, 120);
            case PAUSED -> new Color(200, 200, 120);
            default -> TEXT;
        };
    }

    private static ItemStack iconFor(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return ItemStack.EMPTY;
        Item item = Registries.ITEM.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String shortName(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }

    // ------------------------------------------------------------- lifecycle

    private static SchematicProgressHud instance;

    /** Adds the element to the HUD if it is not already present. */
    public static void enable() {
        Hud hud = Hud.get();
        SchematicProgressHud existing = find(hud);
        if (existing != null) {
            if (!existing.isActive()) existing.toggle();
            instance = existing;
            return;
        }
        hud.add(INFO, 4, 200);
        instance = find(hud);
    }

    /** Removes the element from the HUD. */
    public static void disable() {
        if (instance != null) {
            instance.remove();
            instance = null;
            return;
        }
        Hud hud = Hud.get();
        SchematicProgressHud existing = find(hud);
        if (existing != null) existing.remove();
    }

    private static SchematicProgressHud find(Hud hud) {
        for (HudElement element : hud) {
            if (element instanceof SchematicProgressHud found) return found;
        }
        return null;
    }

    public enum Mode {
        Compact,
        Expanded
    }
}
