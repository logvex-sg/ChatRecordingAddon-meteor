package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the addon's HUD elements over the vanilla HUD. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void logaddon$onRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo info) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        HudRenderer renderer = HudRenderer.INSTANCE;
        renderer.begin(context, tickCounter.getTickDelta(true));
        try {
            Hud.get().render(renderer);
        } finally {
            renderer.end();
        }
    }
}
