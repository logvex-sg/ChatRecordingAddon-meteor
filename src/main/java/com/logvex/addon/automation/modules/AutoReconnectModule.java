package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.AutoReconnectController;
import com.logvex.addon.framework.SafeEventHandler;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import com.logvex.addon.notify.core.NotificationType;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.util.Map;

/**
 * Reconnects to the last multiplayer server after a disconnect.
 *
 * <p>It only reconnects to a server the client already has a saved entry for, using vanilla's own
 * connection screen, so it cannot be pointed at an arbitrary address by a stale packet. The attempt
 * schedule is a bounded backoff in {@link AutoReconnectController}: it grows to a maximum and gives
 * up after a configured number of tries rather than hammering the server forever. Single-player
 * worlds are ignored because they cannot be resumed this way.
 */
public class AutoReconnectModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Each setting carries a hard range() as well as a sliderRange(): the slider only bounds the
    // widget, so without a range a typed value such as 40000 attempts is accepted and saved. That
    // turns a bounded backoff into an endless reconnect loop against a server that just kicked us.
    private final Setting<Integer> baseDelay = sgGeneral.add(new IntSetting.Builder()
        .name("base-delay-seconds")
        .description("Wait this long before the first reconnect attempt.")
        .defaultValue(5)
        .range(1, 60)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-seconds")
        .description("The delay doubles each attempt, up to this maximum.")
        .defaultValue(60)
        .range(1, 300)
        .sliderRange(5, 300)
        .build()
    );

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Give up after this many failed attempts.")
        .defaultValue(5)
        .range(1, 50)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Boolean> onlyOnServer = sgGeneral.add(new BoolSetting.Builder()
        .name("multiplayer-only")
        .description("Do nothing when the last session was a single-player world.")
        .defaultValue(true)
        .build()
    );

    private final AutoReconnectController controller = new AutoReconnectController(5_000, 60_000, 5);

    private ServerInfo target;
    private boolean waitingToReconnect;

    public AutoReconnectModule() {
        super("log-reconnect", "Reconnects to the last multiplayer server after a disconnect.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        pushSettings();
        info("Auto Reconnect on. Retrying up to %d times.", maxAttempts.get());
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        waitingToReconnect = false;
        info("Auto Reconnect off.");
    }

    @EventHandler
    private void onJoined(GameJoinedEvent event) {
        waitingToReconnect = false;
        controller.onConnected();
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        // Posted from inside MinecraftClient.disconnect(); a throw here would take the game down.
        SafeEventHandler.run("AutoReconnectModule.onLeft", () -> {
            // Remember where we were before the client tears the world down.
            ServerInfo entry = mc.getCurrentServerEntry();
            if (entry != null && entry.address != null && !entry.address.isBlank()) target = entry;
            waitingToReconnect = target != null && !(onlyOnServer.get() && isLocal(entry));
            controller.reset();
            controller.onDisconnect(System.currentTimeMillis());
            if (waitingToReconnect) {
                info("Disconnected. Reconnecting to %s.",
                    target.name == null ? target.address : target.name);
            }
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!waitingToReconnect || target == null) return;
        pushSettings();
        if (!controller.shouldAttempt(System.currentTimeMillis())) {
            if (controller.gaveUp()) {
                waitingToReconnect = false;
                warning("Gave up reconnecting after %d attempts.", controller.attempts());
                notifyUser(NotificationType.CONNECTION, "Auto Reconnect", "Gave up reconnecting.");
            }
            return;
        }

        int attempt = controller.markAttempt(System.currentTimeMillis());
        info("Reconnect attempt %d.", attempt);
        connect(target);
    }

    private void connect(ServerInfo entry) {
        ServerAddress address = ServerAddress.parse(entry.address);
        if (address == null || !ServerAddress.isValid(entry.address)) {
            waitingToReconnect = false;
            error("Cannot reconnect: the saved address is not valid.");
            return;
        }
        // Vanilla's own connection path, so the server sees a normal client.
        ConnectScreen.connect(new TitleScreen(), mc, address, entry, false, new CookieStorage(Map.of()));
        waitingToReconnect = false;
    }

    private static boolean isLocal(ServerInfo entry) {
        return entry == null || entry.isLocal() || entry.isRealm() && entry.address == null;
    }

    private void pushSettings() {
        controller.setBaseDelayMs(baseDelay.get() * 1000L);
        controller.setMaxDelayMs(maxDelay.get() * 1000L);
        controller.setMaxAttempts(maxAttempts.get());
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? controller.status() : "off";
    }
}
