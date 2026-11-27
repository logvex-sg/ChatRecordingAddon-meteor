package com.logvex.addon.companion.mc;

import com.logvex.addon.framework.systems.modules.AddonModule;
import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.LogClient;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotState;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionDiscovery;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionIconCache;
import com.logvex.addon.companion.core.CompanionJson;
import com.logvex.addon.companion.core.CompanionServer;
import com.logvex.addon.companion.gui.CompanionScreen;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The companion module: a small HTTP server that lets a phone or another machine drive this client.
 *
 * <p>When the module is on, it serves the bundled app and a JSON API over the loopback interface.
 * Everything the app can do is one of the actions on {@link MinecraftCompanionApi}, so it can never
 * do anything a user could not do by hand, and the server is off unless the module is on. The
 * snapshot the app reads is built on the client thread; the network work happens on daemon threads.
 *
 * <p>This is the one place that owns the server lifecycle, so enabling and disabling the module
 * reliably opens and closes the port, and no thread or socket outlives the module.
 */
public class CompanionModule extends AddonModule {
    private final SettingGroup sgServer = settings.getDefaultGroup();
    private final SettingGroup sgAccess = settings.createGroup("Access");
    private final SettingGroup sgEvents = settings.createGroup("Live updates");

    private final Setting<Integer> port = sgServer.add(new IntSetting.Builder()
        .name("port")
        .description("The TCP port the companion listens on.")
        .defaultValue(6767)
        .min(1024)
        .sliderRange(1024, 65535)
        .onChanged(value -> restart())
        .build()
    );

    private final Setting<String> nodeName = sgServer.add(new StringSetting.Builder()
        .name("node-name")
        .description("The name this client shows up as in the app.")
        .defaultValue("Log Client")
        .onChanged(value -> { /* read on the next snapshot */ })
        .build()
    );

    private final Setting<Boolean> allowRemote = sgAccess.add(new BoolSetting.Builder()
        .name("allow-remote")
        .description("Also listen on other interfaces. Anyone who can reach the port and knows the token can control this client.")
        .defaultValue(false)
        .onChanged(value -> restart())
        .build()
    );

    private final Setting<Boolean> remoteControl = sgAccess.add(new BoolSetting.Builder()
        .name("remote-control")
        .description("Let the app drive this character: move, look, click and pick hotbar slots. "
            + "Off by default; separate from allow-remote, which only decides who can reach the app.")
        .defaultValue(false)
        .onChanged(value -> applySettings())
        .build()
    );

    private final Setting<String> bindAddress = sgAccess.add(new StringSetting.Builder()
        .name("bind-address")
        .description("The interface to bind when remote access is allowed.")
        .defaultValue("0.0.0.0")
        .visible(allowRemote::get)
        .onChanged(value -> restart())
        .build()
    );

    private final Setting<String> token = sgAccess.add(new StringSetting.Builder()
        .name("token")
        .description("The access token the app must present. Regenerate it if it is ever exposed.")
        .defaultValue("")
        .wide()
        .onChanged(value -> restart())
        .build()
    );

    private final Setting<Boolean> publishChat = sgEvents.add(new BoolSetting.Builder()
        .name("publish-chat")
        .description("Stream new chat lines to the app as they arrive.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> publishBot = sgEvents.add(new BoolSetting.Builder()
        .name("publish-bot")
        .description("Stream bot state and task changes to the app.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnConnect = sgEvents.add(new BoolSetting.Builder()
        .name("notify-on-connect")
        .description("Show a notification when a client connects or disconnects.")
        .defaultValue(true)
        .build()
    );

    private final CompanionConfig config = new CompanionConfig();
    private CompanionServer server;

    /** Publishes this server's address so a local dashboard connects without a URL. */
    private CompanionDiscovery discovery;
    private CompanionEventBus events;
    private MinecraftCompanionApi api;

    private String nodeId;
    private boolean started;
    private boolean applying;
    private long lastSnapshotAt;
    private String lastBotState = "";
    private long lastChatTimestamp;
    private int lastSubscribers;
    private boolean lastInWorld;

    public CompanionModule() {
        super(LogClient.CATEGORY, "companion",
            "Serve a small web dashboard so a phone or another machine can watch and control this client.",
            "web", "remote", "app");
        this.nodeId = UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        applySettings();
        startServer();
        setTicker(this::tick);
        started = true;
        if (server != null && server.isRunning()) {
            info("Companion listening on %s.", url());
        }
    }

    @Override
    public void onDeactivate() {
        stopServer();
        started = false;
    }

    private void applySettings() {
        applying = true;
        try {
            config.setPort(port.get());
            config.setAllowRemote(allowRemote.get());
            config.setRemoteControl(remoteControl.get());
            config.setBindAddress(bindAddress.get());
            config.setNodeName(nodeName.get());
            if (token.get() != null && !token.get().isBlank()) {
                config.setToken(token.get());
            }
            // A blank token setting is filled with a fresh one on first start so the app has something
            // to use; the setting is written back so it survives a restart. The write-back is inside
            // the guard because the setting's own listener would otherwise restart the server from
            // inside its own start-up.
            if (!config.hasToken()) {
                config.setToken(CompanionConfig.generateToken());
            }
            if (!config.token().equals(token.get())) {
                token.set(config.token());
            }
        } finally {
            applying = false;
        }
    }

    /**
     * Restarts the server when a setting that affects it changes. Safe when it is not running, and a
     * no-op while settings are being applied, so writing a setting back from {@link #applySettings()}
     * cannot re-enter and start the server twice.
     */
    public void restart() {
        if (!isActive() || applying) return;
        stopServer();
        applySettings();
        startServer();
    }

    private void startServer() {
        if (server != null && server.isRunning()) return;
        events = new CompanionEventBus(200);
        api = new MinecraftCompanionApi(nodeId);
        CompanionClientThread clientThread = CompanionClientThread.of(this::runOnClientThread);
        // The icon cache is bounded: a large modpack has thousands of items and the app asks for
        // the same ones on every inventory refresh.
        MinecraftViewportSource viewportSource = new MinecraftViewportSource();
        MinecraftControlTarget controlTarget = new MinecraftControlTarget(config::remoteControl);
        api.setViewport(viewportSource, controlTarget);
        server = new CompanionServer(config, api, clientThread, events, loadAppHtml(),
            new MinecraftMapSource(),
            new CompanionIconCache(new MinecraftIconSource(), 512),
            viewportSource, controlTarget);
        try {
            server.start();
            if (boundPort() != config.port()) {
                info("Port %d was in use, so the companion moved to %d.",
                    config.port(), boundPort());
            }
            publishDiscovery();
        } catch (IOException e) {
            LogClient.LOG.warn("Companion could not bind near port {}: {}",
                config.port(), e.getMessage());
            warning("Could not start the companion near port %d. Try a different port.",
                config.port());
            server = null;
        }
    }

    private void stopServer() {
        withdrawDiscovery();
        if (server != null) {
            server.stop();
            server = null;
        }
        events = null;
    }

    /**
     * Writes the discovery file for a server a local client can reach.
     *
     * <p>Only a loopback bind is published. When the user has exposed the server to a network, the
     * token is already a wider secret, and copying it to a predictable file on disk would widen the
     * exposure for no benefit: a remote client is configured by hand anyway.
     */
    private void publishDiscovery() {
        if (!isLoopbackBind()) return;
        // The bound port, not the preferred one: a second client on this machine moves to the next
        // free port, and advertising the configured value would point a dashboard at the other bot.
        int bound = boundPort();
        if (bound <= 0) return;
        java.nio.file.Path target = new java.io.File(
            LogClientContext.dataFolder(), CompanionDiscovery.fileNameFor(bound)).toPath();
        if (discovery == null || !discovery.file().equals(target)) {
            discovery = new CompanionDiscovery(target);
        }
        boolean ok = discovery.publish("127.0.0.1", bound, config.token());
        if (!ok) LogClient.LOG.warn("Companion discovery: {}", discovery.lastError());
    }

    private void withdrawDiscovery() {
        if (discovery != null) {
            discovery.withdraw();
            discovery = null;
        }
    }

    /** True when the server only accepts connections from this machine. */
    private boolean isLoopbackBind() {
        if (config.allowRemote()) return false;
        try {
            return config.resolveBindAddress().isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            // An unresolvable bind address never reaches here in practice: startServer() would have
            // failed to bind first. Treat it as non-loopback so a secret is not published on a guess.
            return false;
        }
    }

    private void runOnClientThread(Runnable runnable) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.execute(runnable);
        else runnable.run();
    }

    private static String loadAppHtml() {
        try (InputStream in = CompanionModule.class.getResourceAsStream("/companion/app.html")) {
            if (in == null) return "<!DOCTYPE html><title>Log Client Companion</title><p>App resource missing.";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<!DOCTYPE html><title>Log Client Companion</title><p>The app could not be loaded.";
        }
    }

    // ------------------------------------------------------------------ live updates

    private void tick() {
        if (!started || server == null || events == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSnapshotAt < 1000) return;
        lastSnapshotAt = now;

        int subscribers = events.subscriberCount();
        if (subscribers != lastSubscribers) {
            if (notifyOnConnect.get() && subscribers > 0 && lastSubscribers == 0) {
                info("A companion client connected.");
            }
            lastSubscribers = subscribers;
        }

        if (publishBot.get()) publishBotState();
        if (publishChat.get()) publishChat();
        publishWorldChange();
    }

    private void publishBotState() {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return;
        BotState state = bot.controller().state();
        if (!state.name().equals(lastBotState)) {
            lastBotState = state.name();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("state", state.toString());
            payload.put("queued", bot.controller().queue().size());
            payload.put("active", bot.controller().activeTask() == null
                ? "" : bot.controller().activeTask().label());
            events.publish("bot", CompanionJson.write(payload));
        }
    }

    private void publishChat() {
        List<ChatEntry> recent = LogService.get().recent(10);
        // recent() is newest-first; publish oldest-first and only what is new.
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatEntry entry = recent.get(i);
            if (entry.timestamp() <= lastChatTimestamp) continue;
            lastChatTimestamp = entry.timestamp();
            events.publish("chat", CompanionJson.write(entry.toMap()));
        }
    }

    private void publishWorldChange() {
        boolean inWorld = MinecraftCompanionApi.inWorld();
        if (inWorld == lastInWorld) return;
        lastInWorld = inWorld;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inWorld", inWorld);
        payload.put("server", LogService.get().serverName());
        events.publish("session", CompanionJson.write(payload));
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        SafeEventHandler.guard("CompanionModule.onGameJoined", () -> {
            if (events == null) return;
            lastChatTimestamp = 0;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "joined");
            payload.put("server", LogService.get().serverName());
            events.publish("session", CompanionJson.write(payload));
        }).run();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (events == null) return;
        // Posted from inside MinecraftClient.disconnect(); a throw here would take the game down.
        SafeEventHandler.run("CompanionModule.onGameLeft", () -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "left");
            payload.put("server", LogService.get().serverName());
            events.publish("session", CompanionJson.write(payload));
        });
    }

    // ------------------------------------------------------------------ status

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    public int boundPort() {
        return server == null ? -1 : server.port();
    }

    public int connectedClients() {
        return events == null ? 0 : events.subscriberCount();
    }

    public String accessToken() {
        return config.token();
    }

    /**
     * The URL a browser can open. With remote access on this is the machine's LAN address, so the
     * link works from another device; with it off it is loopback, which is all that is listening.
     */
    public String url() {
        return config.advertisedUrl(boundPort());
    }

    /** The URL with the token, ready to paste into a phone or a browser. */
    public String urlWithToken() {
        return url() + "?token=" + accessToken();
    }

    /** True when the server only accepts connections from this machine. */
    public boolean isLoopbackOnly() {
        return config.isLoopbackOnly();
    }

    public String statusLine() {
        if (!isActive()) return "off";
        if (!isRunning()) return "unavailable";
        return "port " + boundPort() + " · " + connectedClients() + " client(s)";
    }

    /** Regenerates the token and restarts, so a leaked token can be replaced in one click. */
    public void regenerateToken() {
        config.setToken(CompanionConfig.generateToken());
        token.set(config.token());
        restart();
        info("A new access token was generated. Connected clients must be re-added.");
    }

    // ------------------------------------------------------------------ GUI

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = new WVerticalList();
        WButton open = list.add(new WButton("Open companion settings", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.setScreen(new CompanionScreen(theme, this));
        })).expandX().widget();
        WButton copy = list.add(new WButton("Copy address with token", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.keyboard.setClipboard(urlWithToken());
            info("Copied the companion address.");
        })).expandX().widget();
        WButton regenerate = list.add(new WButton("Generate a new token", this::regenerateToken))
            .expandX().widget();
        return list;
    }

    @Override
    public String getInfoString() {
        return statusLine();
    }

    /** A short, formatted status for the command and the GUI. */
    public String describe() {
        if (!isActive()) return Formatting.GRAY + "The companion is off.";
        if (!isRunning()) return Formatting.RED + "The companion is not listening.";
        return Formatting.GREEN + "Listening on " + boundPort() + Formatting.GRAY
            + " · " + connectedClients() + " app client(s)";
    }
}
