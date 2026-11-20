package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.automation.core.DeathTracker;
import com.logvex.addon.automation.modules.DeathTrackerModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code !deaths} — list remembered deaths; {@code !back} — the most recent one.
 *
 * <p>Both read the same history. {@code !back} is the shortcut you want while running back to your
 * items, so it prints one line rather than a table.
 */
public class DeathsCommand extends Command {
    public DeathsCommand() {
        super("deaths", "List where you died, newest first.", "died");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            list();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("back").executes(context -> {
            back();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            DeathTrackerModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            int count = module.tracker().size();
            module.tracker().clear();
            info("Cleared %d remembered death%s.", count, count == 1 ? "" : "s");
            return SINGLE_SUCCESS;
        }));
    }

    private void list() {
        DeathTrackerModule module = module();
        if (module == null) return;

        List<DeathTracker.Death> deaths = module.tracker().recent();
        if (deaths.isEmpty()) {
            info("No deaths recorded. Turn on the Death Tracker module to start.");
            return;
        }

        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (DeathTracker.Death death : deaths) {
            fields.add(new ReplyFormat.Field(
                DeathTrackerModule.shortWorld(death.world()),
                death.coords() + "  " + ago(death.when())));
        }
        CommandOutput.sendList(this, "Deaths (" + deaths.size() + ")", fields,
            prefix() + "back for the most recent");
    }

    private void back() {
        DeathTrackerModule module = module();
        if (module == null) return;

        String world = mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
        DeathTracker.Death death = module.tracker().lastIn(world);
        // Falling back to any world would send the player to coordinates that mean nothing where
        // they are standing, so a death in another dimension is reported, not offered.
        if (death == null) {
            DeathTracker.Death other = module.tracker().last();
            if (other == null) {
                info("No deaths recorded.");
            } else {
                warning("No death recorded in this dimension. The most recent was in %s at %s.",
                    DeathTrackerModule.shortWorld(other.world()), other.coords());
            }
            return;
        }

        List<ReplyFormat.Field> fields = new ArrayList<>();
        fields.add(new ReplyFormat.Field("Coordinates", death.coords()));
        fields.add(new ReplyFormat.Field("Dimension", DeathTrackerModule.shortWorld(death.world())));
        fields.add(new ReplyFormat.Field("When", ago(death.when())));
        CommandOutput.send(this, "Last death", fields, "Distance is measured from where you stand now.");
    }

    private static String prefix() {
        return com.logvex.addon.framework.systems.config.ClientConfig.get().prefix();
    }

    private DeathTrackerModule module() {
        DeathTrackerModule module = Modules.get().get(DeathTrackerModule.class);
        if (module == null) warning("The Death Tracker module is not registered.");
        return module;
    }

    /** A short, human "how long ago", since a raw timestamp is unreadable at a glance. */
    static String ago(long when) {
        long seconds = Math.max(0, (System.currentTimeMillis() - when) / 1000);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }
}
