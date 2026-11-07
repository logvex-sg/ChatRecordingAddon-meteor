package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.PlayerPort;
import com.logvex.addon.framework.utils.player.PlayerUtils;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.GameMode;

import static com.logvex.addon.framework.LogClientContext.mc;

/** {@link PlayerPort} backed by the live client player. Read-only. */
public final class MinecraftPlayerPort implements PlayerPort {
    @Override
    public boolean isCreative() {
        return mc.player != null && mc.player.isCreative();
    }

    @Override
    public boolean isSurvival() {
        GameMode mode = PlayerUtils.getGameMode();
        return mode != null && mode.isSurvivalLike();
    }

    @Override
    public float health() {
        return mc.player == null ? 0 : mc.player.getHealth();
    }

    @Override
    public float maxHealth() {
        return mc.player == null ? 0 : mc.player.getMaxHealth();
    }

    @Override
    public int foodLevel() {
        return mc.player == null ? 0 : mc.player.getHungerManager().getFoodLevel();
    }

    @Override
    public boolean screenOpen() {
        return mc.currentScreen != null;
    }

    @Override
    public boolean onGround() {
        return mc.player != null && mc.player.isOnGround();
    }

    @Override
    public double[] position() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return new double[]{0, 0, 0};
        return new double[]{player.getX(), player.getY(), player.getZ()};
    }

    @Override
    public String dimension() {
        if (mc.world == null) return "";
        return mc.world.getRegistryKey().getValue().toString();
    }

    @Override
    public boolean alive() {
        return mc.player != null && mc.player.isAlive();
    }
}
