package com.logvex.addon.framework;

import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;

/**
 * The addon's own subscribers on Meteor's event bus.
 *
 * <p>The addon draws its HUD with its own renderer rather than Meteor's HUD system, so it hooks
 * Meteor's {@link Render2DEvent} and paints on top of the vanilla HUD. It also flushes the addon
 * configuration when it has changed, on the tick, so a settings change is persisted within a tick of
 * being made. Both are subscribed once by the addon entrypoint.
 */
public final class AddonEvents {
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        // A throw here escapes into the vanilla render loop, which treats it as fatal and exits.
        SafeEventHandler.run("AddonEvents.onRender2D", () -> {
            HudRenderer renderer = HudRenderer.INSTANCE;
            renderer.begin(event.drawContext, event.frameTime);
            try {
                Hud.get().render(renderer);
            } finally {
                renderer.end();
            }
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Same reasoning as the render hook: an exception in a tick handler closes the game.
        SafeEventHandler.run("AddonEvents.onTick", () -> ConfigManager.get().saveIfDirty());
    }
}
