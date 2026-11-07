package com.logvex.addon.world.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.world.core.LocationCategory;
import com.logvex.addon.world.core.WorldPoint;
import com.logvex.addon.world.mc.WorldService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import static com.logvex.addon.framework.LogClientContext.mc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A top-down map of the remembered places in the current world.
 *
 * <p>It plots every known coordinate on a scaled grid, coloured by category, with the player marked
 * at their live position. The map auto-fits the recorded points and can be zoomed, filtered by
 * category, and centred on the player. It is a client-side plot of what the client has actually seen
 * — it does not read the server's world seed or hidden map data.
 */
public class WorldMapScreen extends WidgetScreen {
    private static final Map<LocationCategory, Color> COLORS = new EnumMap<>(LocationCategory.class);

    static {
        COLORS.put(LocationCategory.BASE, new Color(120, 220, 120));
        COLORS.put(LocationCategory.STRUCTURE, new Color(255, 200, 120));
        COLORS.put(LocationCategory.RESOURCE, new Color(120, 200, 255));
        COLORS.put(LocationCategory.VILLAGE, new Color(220, 180, 120));
        COLORS.put(LocationCategory.PORTAL, new Color(200, 120, 255));
        COLORS.put(LocationCategory.WAYPOINT, new Color(255, 255, 255));
        COLORS.put(LocationCategory.DANGER, new Color(255, 120, 120));
        COLORS.put(LocationCategory.OTHER, new Color(170, 170, 170));
    }

    private double scale = 2.0;
    private LocationCategory filter;
    private boolean centred = true;
    private int panX;
    private int panZ;

    public WorldMapScreen() {
        this(GuiThemes.get());
    }

    public WorldMapScreen(GuiTheme theme) {
        super(theme, "World Map");
    }

    @Override
    public void initWidgets() {
        var profile = WorldService.get().intelligence().current();
        if (profile == null) {
            add(theme.label("No world is loaded yet.")).expandX();
            return;
        }

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        controls.add(theme.button("Zoom In")).widget().action = () -> {
            scale = Math.min(16, scale * 1.5);
        };
        controls.add(theme.button("Zoom Out")).widget().action = () -> {
            scale = Math.max(0.25, scale / 1.5);
        };
        controls.add(theme.button("Centre")).widget().action = () -> {
            centred = true;
            panX = 0;
            panZ = 0;
        };
        controls.add(theme.button("All")).widget().action = () -> {
            filter = null;
            reload();
        };
        for (LocationCategory category : LocationCategory.values()) {
            controls.add(theme.button(category.toString())).widget().action = () -> {
                filter = category;
                reload();
            };
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xFF101018);

        var profile = WorldService.get().intelligence().current();
        if (profile == null) {
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        List<WorldPoint> points = new ArrayList<>();
        for (WorldPoint point : profile.locations().all()) {
            if (filter == null || point.category() == filter) points.add(point);
        }

        double playerX = 0;
        double playerZ = 0;
        boolean hasPlayer = mc != null && mc.player != null;
        if (hasPlayer) {
            playerX = mc.player.getX();
            playerZ = mc.player.getZ();
        }

        double[] bounds = bounds(points, hasPlayer, playerX, playerZ);
        double centreX = centred ? (hasPlayer ? playerX : (bounds[0] + bounds[1]) / 2) : (bounds[0] + bounds[1]) / 2 + panX;
        double centreZ = centred ? (hasPlayer ? playerZ : (bounds[2] + bounds[3]) / 2) : (bounds[2] + bounds[3]) / 2 + panZ;

        double screenCentreX = this.width / 2.0;
        double screenCentreZ = this.height / 2.0;

        // A light grid every 128 blocks, so scale is legible.
        int gridStep = 128;
        int minGridX = (int) Math.floor((centreX - screenCentreX / scale) / gridStep) * gridStep;
        int maxGridX = (int) Math.ceil((centreX + screenCentreX / scale) / gridStep) * gridStep;
        int minGridZ = (int) Math.floor((centreZ - screenCentreZ / scale) / gridStep) * gridStep;
        int maxGridZ = (int) Math.ceil((centreZ + screenCentreZ / scale) / gridStep) * gridStep;
        for (int gx = minGridX; gx <= maxGridX; gx += gridStep) {
            int sx = (int) (screenCentreX + (gx - centreX) * scale);
            context.fill(sx, 0, sx + 1, this.height, 0x30FFFFFF);
        }
        for (int gz = minGridZ; gz <= maxGridZ; gz += gridStep) {
            int sz = (int) (screenCentreZ + (gz - centreZ) * scale);
            context.fill(0, sz, this.width, sz + 1, 0x30FFFFFF);
        }

        for (WorldPoint point : points) {
            int sx = (int) (screenCentreX + (point.x() - centreX) * scale);
            int sz = (int) (screenCentreZ + (point.z() - centreZ) * scale);
            if (sx < 0 || sx > this.width || sz < 0 || sz > this.height) continue;
            Color color = COLORS.getOrDefault(point.category(), COLORS.get(LocationCategory.OTHER));
            int argb = 0xFF000000 | (color.r << 16) | (color.g << 8) | color.b;
            context.fill(sx - 2, sz - 2, sx + 3, sz + 3, argb);
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, point.name().isBlank() ? point.category().toString() : point.name(),
                sx + 5, sz - 4, argb);
        }

        if (hasPlayer) {
            int sx = (int) (screenCentreX + (playerX - centreX) * scale);
            int sz = (int) (screenCentreZ + (playerZ - centreZ) * scale);
            context.fill(sx - 4, sz, sx + 5, sz + 1, 0xFFFF4040);
            context.fill(sx, sz - 4, sx + 1, sz + 5, 0xFFFF4040);
        }

        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
            "Scale: " + String.format(java.util.Locale.ROOT, "%.2f", scale) + " px/block   Places: " + points.size(),
            6, 6, 0xFFDDDDDD);
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
            filter == null ? "Filter: All" : "Filter: " + filter,
            6, 18, 0xFFAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    private static double[] bounds(List<WorldPoint> points, boolean hasPlayer, double playerX, double playerZ) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (WorldPoint point : points) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
        }
        if (hasPlayer) {
            minX = Math.min(minX, playerX);
            maxX = Math.max(maxX, playerX);
            minZ = Math.min(minZ, playerZ);
            maxZ = Math.max(maxZ, playerZ);
        }
        if (minX > maxX) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{minX, maxX, minZ, maxZ};
    }
}
