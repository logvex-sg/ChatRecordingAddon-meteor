package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BuildPort;
import com.logvex.addon.schematicdropper.SchematicBuildController;
import com.logvex.addon.schematicdropper.core.PlacementSnapshot;

import java.util.function.Supplier;

/**
 * {@link BuildPort} adapter over the existing {@link SchematicBuildController}.
 *
 * <p>The controller already owns placement, verification, checkpoints and failure handling, so this
 * bridge only translates its API into the small surface the bot's build executor needs. The
 * controller is looked up lazily so the bot does not hold a stale reference if the schematic module
 * is reloaded.
 */
public final class MinecraftBuildPort implements BuildPort {
    private final Supplier<SchematicBuildController> supplier;

    public MinecraftBuildPort(Supplier<SchematicBuildController> supplier) {
        this.supplier = supplier;
    }

    private SchematicBuildController controller() {
        return supplier == null ? null : supplier.get();
    }

    @Override
    public boolean hasSchematic() {
        SchematicBuildController c = controller();
        return c != null && c.hasSchematic();
    }

    @Override
    public String schematicName() {
        SchematicBuildController c = controller();
        if (c == null || c.schematic() == null) return "";
        return c.schematic().name();
    }

    @Override
    public boolean startBuild() {
        SchematicBuildController c = controller();
        if (c == null || !c.hasSchematic()) return false;
        return c.startBuild(System.currentTimeMillis());
    }

    @Override
    public void stopBuild() {
        SchematicBuildController c = controller();
        if (c != null) c.stop(System.currentTimeMillis());
    }

    @Override
    public boolean running() {
        SchematicBuildController c = controller();
        return c != null && c.state() != null && c.state().isRunning();
    }

    @Override
    public double progress() {
        SchematicBuildController c = controller();
        if (c == null) return 0;
        PlacementSnapshot snapshot = c.snapshot();
        return snapshot == null ? 0 : snapshot.percent();
    }

    @Override
    public String status() {
        SchematicBuildController c = controller();
        if (c == null) return "unavailable";
        PlacementSnapshot snapshot = c.snapshot();
        if (snapshot == null) return "idle";
        return snapshot.completed() + " placed, " + snapshot.failed() + " failed";
    }

    @Override
    public String lastError() {
        SchematicBuildController c = controller();
        if (c == null) return "the schematic subsystem is unavailable";
        return c.failures() == null ? "" : "";
    }
}
