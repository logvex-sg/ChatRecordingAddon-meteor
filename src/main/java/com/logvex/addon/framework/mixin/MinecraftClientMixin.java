package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.MeteorClient;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.utils.world.BlockIterator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the addon's tick loop and reports a disconnect to listeners. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void logaddon$onTick(CallbackInfo info) {
        MeteorClient.EVENT_BUS.post(TickEvent.Post.get());
        BlockIterator.tick();
        com.logvex.addon.LogAddon.tick();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V", at = @At("HEAD"))
    private void logaddon$onDisconnect(Screen screen, boolean transferring, CallbackInfo info) {
        if (MinecraftClient.getInstance().world != null) {
            MeteorClient.EVENT_BUS.post(new GameLeftEvent());
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void logaddon$onStop(CallbackInfo info) {
        com.logvex.addon.LogAddon.shutdown();
    }
}
