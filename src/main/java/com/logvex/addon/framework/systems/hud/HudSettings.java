package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.Settings;
import com.logvex.addon.framework.systems.hud.layout.HudAnchor;
import com.logvex.addon.framework.utils.render.color.SettingColor;

/**
 * The settings every HUD element shares, built once so all elements look and behave the same.
 *
 * <p>An element calls {@link #install} in its constructor and gets a consistent "Appearance" group
 * (background, border, colours, text scale, shadow) and a "Position" group (anchor, offsets, grid
 * snapping). Because one builder produces every element's shared controls, a new element cannot drift
 * into its own bespoke style, and the editor can rely on every element exposing the same options.
 *
 * <p>Colour options are paired with a "theme colour" toggle: with it on the element follows the active
 * GUI theme (so the HUD is blue on the blue theme); turning it off reveals the picker and the
 * element's own colour wins. That keeps a coherent default while allowing per-element control.
 */
public final class HudSettings {
    private HudSettings() {
    }

    /** The shared settings handed back to the element that installed them. */
    public static final class Shared {
        public final Setting<Boolean> useThemeBackground;
        public final Setting<Boolean> background;
        public final Setting<SettingColor> backgroundColor;
        public final Setting<Boolean> border;
        public final Setting<SettingColor> borderColor;
        public final Setting<Boolean> useThemeTitle;
        public final Setting<SettingColor> titleColor;
        public final Setting<Boolean> useThemeText;
        public final Setting<SettingColor> textColor;
        public final Setting<Boolean> shadow;
        public final Setting<Boolean> customScale;
        public final Setting<Double> scale;
        public final Setting<HudAnchor> anchor;
        public final Setting<Integer> offsetX;
        public final Setting<Integer> offsetY;
        public final Setting<Boolean> snapToGrid;

        private Shared(SettingGroup style, SettingGroup position) {
            useThemeBackground = style.add(new BoolSetting.Builder()
                .name("theme-background").description("Take the background colour from the active theme.")
                .defaultValue(true).build());
            background = style.add(new BoolSetting.Builder()
                .name("background").description("Draw a background panel behind the element.")
                .defaultValue(true).build());
            backgroundColor = style.add(new ColorSetting.Builder()
                .name("background-color").description("Background colour, when not using the theme colour.")
                .defaultValue(new SettingColor(10, 14, 24, 175))
                .visible(() -> background.get() && !useThemeBackground.get()).build());
            border = style.add(new BoolSetting.Builder()
                .name("border").description("Draw a border around the panel.").defaultValue(false).build());
            borderColor = style.add(new ColorSetting.Builder()
                .name("border-color").description("Border colour, when not using the theme colour.")
                .defaultValue(new SettingColor(70, 90, 130)).visible(border::get).build());
            useThemeTitle = style.add(new BoolSetting.Builder()
                .name("theme-title").description("Take the title colour from the active theme.")
                .defaultValue(true).build());
            titleColor = style.add(new ColorSetting.Builder()
                .name("title-color").description("Title colour, when not using the theme colour.")
                .defaultValue(new SettingColor(120, 190, 255)).visible(() -> !useThemeTitle.get()).build());
            useThemeText = style.add(new BoolSetting.Builder()
                .name("theme-text").description("Take the text colour from the active theme.")
                .defaultValue(true).build());
            textColor = style.add(new ColorSetting.Builder()
                .name("text-color").description("Text colour, when not using the theme colour.")
                .defaultValue(new SettingColor(230, 230, 230)).visible(() -> !useThemeText.get()).build());
            shadow = style.add(new BoolSetting.Builder()
                .name("text-shadow").description("Draw text with a shadow.").defaultValue(true).build());
            customScale = style.add(new BoolSetting.Builder()
                .name("custom-scale").description("Use a scale instead of the global HUD text scale.")
                .defaultValue(false).build());
            scale = style.add(new DoubleSetting.Builder()
                .name("scale").description("Text scale.").defaultValue(1).min(0.5).sliderRange(0.5, 3)
                .visible(customScale::get).build());

            anchor = position.add(new EnumSetting.Builder<HudAnchor>()
                .name("anchor").description("Screen corner or edge the element is placed against.")
                .defaultValue(HudAnchor.TOP_LEFT).build());
            offsetX = position.add(new IntSetting.Builder()
                .name("offset-x").description("Pixels from the anchor on the horizontal axis.")
                .defaultValue(0).min(-2000).max(2000).sliderRange(-400, 400).build());
            offsetY = position.add(new IntSetting.Builder()
                .name("offset-y").description("Pixels from the anchor on the vertical axis.")
                .defaultValue(0).min(-2000).max(2000).sliderRange(-400, 400).build());
            snapToGrid = position.add(new BoolSetting.Builder()
                .name("snap-to-grid").description("Snap to the editor's grid when dragged.")
                .defaultValue(true).build());
        }
    }

    /** Adds the shared Appearance and Position groups to {@code settings} and returns them. */
    public static Shared install(Settings settings) {
        SettingGroup style = settings.hasGroup("Appearance")
            ? find(settings, "Appearance")
            : settings.createGroup("Appearance");
        SettingGroup position = settings.hasGroup("Position")
            ? find(settings, "Position")
            : settings.createGroup("Position");
        return new Shared(style, position);
    }

    private static SettingGroup find(Settings settings, String name) {
        for (SettingGroup group : settings.groups()) {
            if (group.name().equals(name)) return group;
        }
        return settings.createGroup(name);
    }
}
