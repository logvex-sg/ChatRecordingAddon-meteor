package com.logvex.addon.assistant.modules;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.CommandDefaults;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.ChatActivity;
import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.CommandPermission;
import com.logvex.addon.assistant.core.GreetingPools;
import com.logvex.addon.assistant.core.MacroSet;
import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.RemoteMacroGate;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.SelfReplyGuard;
import com.logvex.addon.assistant.core.SendPacer;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.assistant.gui.AssistantScreen;
import com.logvex.addon.assistant.mc.AssistantService;
import com.logvex.addon.automation.modules.AutomationModule;
import com.logvex.addon.chatlog.core.ChatClassifier;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.modules.WebhookRelayModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.stats.McServerStats;
import com.logvex.addon.stats.SpeedTracker;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommands;
import com.logvex.addon.stats.StatusOutput;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import com.logvex.addon.notify.core.NotificationType;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A configurable chat assistant: keyword triggers, persistent memory and memory commands.
 *
 * <p>When enabled, the module watches incoming chat and asks {@link AssistantRuntime} whether any
 * configured trigger matches or whether the message is a memory command. It never speaks unless a
 * rule explicitly asks it to, so an empty configuration is silent. The decision logic itself lives
 * in the Minecraft-free core; this class only turns a decision into a chat line, a notification, a
 * sound or a memory write.
 *
 * <p>Self-replies are suppressed: a message whose sender is the local player, or whose text is one
 * of the assistant's own recent replies, is ignored, so two assistants cannot talk to each other and
 * the assistant cannot answer itself.
 */
public class AssistantModule extends AutomationModule {
    /** How many block lines one reply may occupy before the rest is summarised. */
    private static final int MAX_EMBED_LINES = 12;

    /** How many reply lines may wait to be sent before the oldest is dropped. */
    private static final int PACER_CAPACITY = 40;

    /** How the user wants blocks laid out; {@link #AUTO} keeps whatever the reply was written for. */
    public enum ReplyLayout {
        AUTO, BOX, COMPACT, PLAIN, LIST;

        /** The style to render with, or null to keep the block's own. */
        public ReplyFormat.Style style() {
            return switch (this) {
                case AUTO -> null;
                case BOX -> ReplyFormat.Style.BOX;
                case COMPACT -> ReplyFormat.Style.COMPACT;
                case PLAIN -> ReplyFormat.Style.PLAIN;
                case LIST -> ReplyFormat.Style.LIST;
            };
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");
    private final SettingGroup sgMemory = settings.createGroup("Memory");
    private final SettingGroup sgLimits = settings.createGroup("Limits");
    private final SettingGroup sgPacing = settings.createGroup("Send pacing");
    private final SettingGroup sgStatus = settings.createGroup("Live Status");
    private final SettingGroup sgMacros = settings.createGroup("Chat Command Macros");
    private final SettingGroup sgGreetings = settings.createGroup("Greetings");
    private final SettingGroup sgPermissions = settings.createGroup("Permissions");

    private final Setting<String> assistantName = sgGeneral.add(new StringSetting.Builder()
        .name("name")
        .description("The assistant's name, shown in the HUD and in notifications.")
        .defaultValue("Assistant")
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> respondToTriggers = sgGeneral.add(new BoolSetting.Builder()
        .name("respond-to-triggers")
        .description("Answer messages that match a configured keyword trigger.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> memoryCommands = sgGeneral.add(new BoolSetting.Builder()
        .name("memory-commands")
        .description("Understand !remember, !forget, !fact, !facts, !memory and !history in chat.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<String> commandPrefix = sgGeneral.add(new StringSetting.Builder()
        .name("command-prefix")
        .description("The prefix memory commands start with.")
        .defaultValue("!")
        .visible(memoryCommands::get)
        .onChanged(ignored -> rebuild())
        .build()
    );

    // ------------------------------------------------------------------ live status

    private final Setting<Boolean> statusEnabled = sgStatus.add(new BoolSetting.Builder()
        .name("status-commands")
        .description("Answer the live status commands: !status, !tps, !ping, !coords and the rest.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<List<String>> commandLines = sgGeneral.add(new StringListSetting.Builder()
            .name("command-table")
            .description("One prefix command per line: name = on/off | local/chat/silent | template. "
                + "Blank template keeps the built-in wording. Reset to defaults to restore the "
                + "shipped table.")
            .defaultValue(CommandDefaults.encoded())
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<List<String>> statusLines = sgStatus.add(new StringListSetting.Builder()
            .name("status-table")
            .description("One command per line: name = on/off | local/chat | template. "
                + "Reset to defaults to restore the shipped table.")
            .defaultValue(defaultStatusLines())
            .visible(statusEnabled::get)
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<Boolean> statusWebhook = sgStatus.add(new BoolSetting.Builder()
        .name("status-webhook")
        .description("Post a status card to the webhook when you run !status locally. "
            + "Player-identifying figures are always left out of the card.")
        .defaultValue(false)
        .visible(statusEnabled::get)
        .build()
    );

    // ------------------------------------------------------------------ send pacing

    private final Setting<Boolean> pacingEnabled = sgPacing.add(new BoolSetting.Builder()
        .name("pace-replies")
        .description("Release multi-line replies a line at a time instead of all at once. "
            + "A block such as !cmds is a dozen lines, and servers kick for that burst.")
        .defaultValue(true)
        .onChanged(ignored -> rebuildPacer())
        .build()
    );

    private final Setting<Integer> pacingGapMs = sgPacing.add(new IntSetting.Builder()
        .name("line-gap-ms")
        .description("Shortest gap between two reply lines. Servers allow a few lines a second; "
            + "1000 is safe on the strictest ones.")
        .defaultValue(1_000)
        .min(0)
        .max(10_000)
        .sliderRange(250, 3_000)
        .visible(pacingEnabled::get)
        .onChanged(ignored -> rebuildPacer())
        .build()
    );

    private final Setting<Integer> pacingBurst = sgPacing.add(new IntSetting.Builder()
        .name("burst-lines")
        .description("Most lines allowed in one burst window, however fast they are released.")
        .defaultValue(3)
        .min(1)
        .max(50)
        .sliderRange(1, 10)
        .visible(pacingEnabled::get)
        .onChanged(ignored -> rebuildPacer())
        .build()
    );

    private final Setting<Integer> pacingBurstWindowMs = sgPacing.add(new IntSetting.Builder()
        .name("burst-window-ms")
        .description("The window the burst limit applies to.")
        .defaultValue(5_000)
        .min(1_000)
        .max(60_000)
        .sliderRange(1_000, 15_000)
        .visible(pacingEnabled::get)
        .onChanged(ignored -> rebuildPacer())
        .build()
    );

    // ------------------------------------------------------------------ chat command macros

    private final Setting<Boolean> macrosEnabled = sgMacros.add(new BoolSetting.Builder()
        .name("macros-enabled")
        .description("Turn shortcuts you type into server commands. Nothing is sent until you type one.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<List<String>> macroLines = sgMacros.add(new StringListSetting.Builder()
            .name("macros")
            .description("One macro per line: shortcut => command. Example: !spawn => /spawn")
            .defaultValue(defaultMacroLines())
            .visible(macrosEnabled::get)
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<Boolean> macroFeedback = sgMacros.add(new BoolSetting.Builder()
        .name("macro-feedback")
        .description("Show a local note when a macro runs or is used wrongly.")
        .defaultValue(true)
        .visible(macrosEnabled::get)
        .build()
    );

    private final Setting<Boolean> macroEchoCommand = sgMacros.add(new BoolSetting.Builder()
        .name("echo-command")
        .description("Show the expanded command in chat as if you typed it.")
        .defaultValue(true)
        .visible(macrosEnabled::get)
        .build()
    );

    private final Setting<Boolean> macrosRemote = sgMacros.add(new BoolSetting.Builder()
        .name("allow-remote")
        .description("Let other players run macros marked 'remote'. A macro sends a command as you, "
            + "so this is off by default and only shared macros are reachable.")
        .defaultValue(false)
        .visible(macrosEnabled::get)
        .onChanged(ignored -> rebuildRemoteGate())
        .build()
    );

    private final Setting<List<String>> macrosAllowList = sgMacros.add(new StringListSetting.Builder()
        .name("remote-allow-list")
        .description("Players allowed to run shared macros. Empty means everyone, once allow-remote is on.")
        .defaultValue(List.of())
        .visible(() -> macrosEnabled.get() && macrosRemote.get())
        .onChanged(ignored -> rebuildRemoteGate())
        .build()
    );

    private final Setting<Integer> macrosRemoteCooldownMs = sgMacros.add(new IntSetting.Builder()
        .name("remote-cooldown-ms")
        .description("Shortest gap between two remote macro runs by the same player.")
        .defaultValue(5_000)
        .min(0)
        .max(120_000)
        .sliderRange(0, 30_000)
        .visible(() -> macrosEnabled.get() && macrosRemote.get())
        .onChanged(ignored -> rebuildRemoteGate())
        .build()
    );

    private final Setting<Boolean> watchPublicChat = sgTriggers.add(new BoolSetting.Builder()
        .name("watch-public-chat")
        .description("Watch public player chat for triggers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> watchPrivateMessages = sgTriggers.add(new BoolSetting.Builder()
        .name("watch-private-messages")
        .description("Watch private messages for triggers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> watchSystem = sgTriggers.add(new BoolSetting.Builder()
        .name("watch-system")
        .description("Watch system and server messages for triggers. Off by default: they are noisy.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> rules = sgTriggers.add(new StringListSetting.Builder()
        .name("rules")
        .description("Trigger rules, one per line: pattern;sender=;reply=;mode=;cooldown=;limit=;sound=;remember=;enabled=")
        .defaultValue(TriggerRule.preconfigured().stream().map(TriggerRule::encode).toList())
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> rememberConversations = sgMemory.add(new BoolSetting.Builder()
        .name("remember-conversations")
        .description("Remember recent exchanges in memory, so !history and {fact:...} stay useful.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<TriggerRule.ReplyMode> memoryReplyMode =
        sgMemory.add(new EnumSetting.Builder<TriggerRule.ReplyMode>()
            .name("memory-reply-mode")
            .description("How the assistant answers memory commands: chat, notify, or silent.")
            .defaultValue(TriggerRule.ReplyMode.NOTIFY)
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<Boolean> persist = sgMemory.add(new BoolSetting.Builder()
        .name("persist")
        .description("Save memory to disk so it survives a restart.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rateLimit = sgLimits.add(new IntSetting.Builder()
        .name("rate-limit-per-minute")
        .description("Maximum assistant replies per minute. 0 disables the global limit.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 120)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Integer> maxReplyLength = sgLimits.add(new IntSetting.Builder()
        .name("max-reply-length")
        .description("Longest reply the assistant will send, in characters.")
        .defaultValue(200)
        .min(16)
        .sliderRange(16, 400)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<ReplyLayout> replyFormat = sgLimits.add(new EnumSetting.Builder<ReplyLayout>()
        .name("reply-format")
        .description("How multi-field replies are laid out. Auto keeps the layout each reply was written for.")
        .defaultValue(ReplyLayout.AUTO)
        .build()
    );

    private final Setting<Integer> replyWidth = sgLimits.add(new IntSetting.Builder()
        .name("reply-width")
        .description("Widest line a formatted reply may use, in characters.")
        .defaultValue(ReplyFormat.DEFAULT_WIDTH)
        .min(20)
        .sliderRange(20, 100)
        .build()
    );

    private final Setting<Boolean> matchFeedback = sgLimits.add(new BoolSetting.Builder()
        .name("match-feedback")
        .description("Print a short note when a trigger matches or the assistant starts.")
        .defaultValue(false)
        .build()
    );

    // Greetings. Off by default so an existing user's chat is unchanged until they ask for it.

    private final Setting<Boolean> greetingsEnabled = sgGreetings.add(new BoolSetting.Builder()
        .name("greetings")
        .description("Greet the local player when you join a world, using a greeting that suits the time of day.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> morningGreetings = sgGreetings.add(new StringSetting.Builder()
        .name("morning")
        .description("Greeting variants for 05:00-11:59, separated by |.")
        .defaultValue(GreetingPools.poolFor(null, "morning"))
        .visible(greetingsEnabled::get)
        .build()
    );

    private final Setting<String> afternoonGreetings = sgGreetings.add(new StringSetting.Builder()
        .name("afternoon")
        .description("Greeting variants for 12:00-16:59, separated by |.")
        .defaultValue(GreetingPools.poolFor(null, "afternoon"))
        .visible(greetingsEnabled::get)
        .build()
    );

    private final Setting<String> eveningGreetings = sgGreetings.add(new StringSetting.Builder()
        .name("evening")
        .description("Greeting variants for 17:00-21:59, separated by |.")
        .defaultValue(GreetingPools.poolFor(null, "evening"))
        .visible(greetingsEnabled::get)
        .build()
    );

    private final Setting<String> nightGreetings = sgGreetings.add(new StringSetting.Builder()
        .name("night")
        .description("Greeting variants for 22:00-04:59, separated by |.")
        .defaultValue(GreetingPools.poolFor(null, "night"))
        .visible(greetingsEnabled::get)
        .build()
    );

    private final Setting<String> goodGameReply = sgGreetings.add(new StringSetting.Builder()
        .name("good-game-reply")
        .description("What the good-game command says. {player} expands to the speaker.")
        .defaultValue(AssistantBrain.DEFAULT_GOOD_GAME_REPLY)
        .build()
    );

    // Permissions. Off by default: enabling it changes who can talk to the assistant, so it must be
    // a deliberate choice. With it off every command behaves exactly as it did before.

    private final Setting<Boolean> permissionsEnabled = sgPermissions.add(new BoolSetting.Builder()
        .name("permissions")
        .description("Restrict who may run each assistant command. Off leaves every command open.")
        .defaultValue(false)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<PermissionSet.DefaultPolicy> defaultPolicy =
        sgPermissions.add(new EnumSetting.Builder<PermissionSet.DefaultPolicy>()
            .name("unlisted-commands")
            .description("How to treat a command with no rule: open to everyone, or local-only.")
            .defaultValue(PermissionSet.DefaultPolicy.OPEN)
            .visible(permissionsEnabled::get)
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<List<String>> commandPermissions =
        sgPermissions.add(new StringListSetting.Builder()
            .name("command-permissions")
            .description("One per line: command;level=everyone|whitelist|administrators|local-only;users=Name,Name=uuid")
            .defaultValue(List.of())
            .visible(permissionsEnabled::get)
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<List<String>> administrators = sgPermissions.add(new StringListSetting.Builder()
        .name("administrators")
        .description("Players treated as administrators: a name, or name=uuid to pin the account.")
        .defaultValue(List.of())
        .visible(permissionsEnabled::get)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> requireVerifiedNames = sgPermissions.add(new BoolSetting.Builder()
        .name("require-verified-names")
        .description("A name-only entry must match a sender the client could pin to an account. Stricter, but refuses offline-mode players.")
        .defaultValue(false)
        .visible(permissionsEnabled::get)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> duplicateFilter = sgLimits.add(new BoolSetting.Builder()
        .name("duplicate-filter")
        .description("Ignore a message the assistant already answered in the last few seconds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> helpCommand = sgLimits.add(new BoolSetting.Builder()
        .name("help-command")
        .description("Answer the help command. On by default; off makes the assistant silent about itself.")
        .defaultValue(true)
        .build()
    );

    private final AssistantService service = AssistantService.get();
    private final AssistantRuntime runtime;
    private TriggerSet parsed = TriggerSet.empty();
    private AssistantConfig config = AssistantConfig.DEFAULTS;
    private String ownName = "";

    /** How often each guarded chat step has failed, so the log is not flooded by one fault. */
    private final java.util.Map<String, Integer> failureCounts =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The assistant's own recent replies, so it never answers itself. */
    /** Recognises echoes of the assistant's own replies; the single self-reply guard. */
    private final SelfReplyGuard selfReplies = new SelfReplyGuard();

    private long lastSaveAt;
    private boolean memoryDirty;
    /** When the current session began, for the stats uptime figure. */
    private long sessionStart = System.currentTimeMillis();
    private final SpeedTracker speed = new SpeedTracker();

    /**
     * The macro table, rebuilt from the settings.
     *
     * <p>Separate from {@link #parsed} on purpose: a macro is a shortcut the local player types, and a
     * trigger is a pattern matched against incoming chat. Sharing one table is how a macro would end up
     * being answered as if a player had said it, so they are never mixed.
     */
    private MacroSet macros = MacroSet.EMPTY;

    /**
     * Set while this module is itself sending a line.
     *
     * <p>Every send this module makes goes through the same network handler the interception listens
     * on, so without a guard a reply that happened to start with the prefix would be read back as a
     * fresh command. The flag makes the re-entry impossible rather than merely unlikely.
     */
    private boolean sendingMacro;

    /**
     * Holds reply lines that must not all leave in one tick.
     *
     * <p>Rebuilt when the pacing settings change. A block is a dozen lines and a server kicks for
     * that burst, so the lines are released a few at a time from the tick handler instead.
     */
    private SendPacer pacer = SendPacer.defaults();

    /** Decides whether a stranger may run one of this client's shared macros. */
    private RemoteMacroGate remoteGate = RemoteMacroGate.disabled();

    public AssistantModule() {
        super("assistant", "A configurable chat assistant with keyword triggers and persistent memory.");
        runtime = new AssistantRuntime(service.memory());
    }

    @Override
    public void onActivate() {
        service.loadAsync(this::rebuild);
        rebuild();
        ownName = localPlayerName();
        lastSaveAt = System.currentTimeMillis();
        info("%s enabled (%s).", config.name(), Messages.plural(parsed.rules().size(), "trigger"));
    }

    @Override
    public void onDeactivate() {
        flushMemory();
        info("%s disabled.", config.name());
    }

    @Override
    protected void onWorldJoin() {
        ownName = localPlayerName();
        sessionStart = System.currentTimeMillis();
        runtime.resetSession();
        if (greetingsEnabled.get()) greet();
    }

    /**
     * Greets the local player with a greeting that suits the time of day.
     *
     * <p>Delivered through the normal decision path so the greeting obeys the same reply mode, the
     * same length clamp and the same self-reply guard as everything else.
     */
    public void greet() {
        long now = System.currentTimeMillis();
        AssistantBrain.Decision decision = runtime.brain().greeting(
            identify(ownName), greetingPools(), config,
            java.time.LocalTime.now(), now);
        if (decision.acts()) deliver(decision, ownName);
    }

    @Override
    protected void onWorldLeave() {
        runtime.resetSession();
        selfReplies.reset();
        // A backlog would arrive in the next world out of context, and its recipients are no longer
        // verifiable there.
        pacer.clear();
        flushMemory();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player != null) {
            speed.sample(mc.player.getX(), mc.player.getZ(), System.currentTimeMillis());
        }
        drainPacer();
        // Persist on a slow cadence rather than on every memory write, so a busy chat cannot cause
        // a disk write storm. The dirty flag makes this a no-op when nothing changed.
        if (System.currentTimeMillis() - lastSaveAt < 30_000L) return;
        if (memoryDirty && persist.get()) flushMemory();
    }

    /**
     * Releases at most one queued reply line, when the pacer allows it.
     *
     * <p>Driven from the tick so the pacing survives a tick that renders no lines: the queue is a
     * property of the module, not of the reply that filled it.
     */
    private void drainPacer() {
        if (pacer.isEmpty() || mc.player == null || mc.player.networkHandler == null) return;
        long now = System.currentTimeMillis();
        for (SendPacer.Line line : pacer.ready(now)) {
            if (line.isWhisper()) {
                whisper(line.verb(), line.recipient(), line.text());
            } else {
                speak(line.text());
            }
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        // Guarded because this runs inside Meteor's event dispatch: an exception here would leave
        // the chat pipeline rather than being reported, and one malformed line would take the whole
        // assistant down until the game restarted.
        guard("receive", () -> receiveStep(event));
    }

    private void receiveStep(ReceiveMessageEvent event) {
        if (!ready() || event.getMessage() == null) return;

        boolean fromPlayer = event.getIndicator() != null;
        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return;

        ChatClassifier.Result result = ChatClassifier.classify(fromPlayer, content);
        if (!watched(result.type())) return;

        String sender = result.sender().isBlank() ? "" : result.sender();
        if (isOwnMessage(sender, content)) return;

        long now = System.currentTimeMillis();
        // A shared macro is answered before triggers: the sender typed a command, and a trigger
        // rule that happened to match the same words must not answer it instead.
        if (tryRemoteMacro(sender, content, now)) return;

        SenderIdentity identity = identify(sender);
        AssistantBrain.Decision decision = runtime.handle(identity, content, now,
            McServerStats.snapshot(mc, now, sessionStart, speed.blocksPerSecond()),
            statusSettings(), requesterPing(identity, sender));
        if (!decision.acts()) return;
        deliver(decision, sender);
    }

    // ------------------------------------------------------------------ remote macros

    /**
     * Runs a shared macro on behalf of another player, when the gate allows it.
     *
     * <p>The command still leaves as the operator, so the gate is the only thing standing between a
     * stranger and the operator's account. Every refusal is silent to the sender — a reply would
     * confirm which shortcuts exist — and is only reported locally, where the operator can see that
     * someone tried.
     *
     * @return true when the line was a shared macro and has been handled, whether or not it ran
     */
    private boolean tryRemoteMacro(String sender, String content, long now) {
        if (!macrosEnabled.get() || !macros.anyRemote()) return false;
        if (!macros.hasPrefix(content)) return false;
        java.util.Optional<MacroSet.Call> found = macros.resolveRemote(content);
        if (found.isEmpty()) return false;

        MacroSet.Call call = found.get();
        ChatMacro macro = call.macro();
        RemoteMacroGate.Verdict verdict = remoteGate.check(sender, macro, now);
        if (!verdict.allowed()) {
            if (matchFeedback.get()) {
                info("Remote macro %s from %s refused: %s.", macro.shortcut(),
                    Messages.player(sender), verdict.reason());
            }
            return true;
        }

        ChatMacro.Outcome outcome = macro.run(call.input());
        if (!outcome.ok()) {
            if (macroFeedback.get()) {
                info("Remote macro %s from %s was malformed: %s", macro.shortcut(),
                    Messages.player(sender), outcome.problem());
            }
            return true;
        }
        if (macros.wouldRecurse(macro, outcome.command())) return true;

        remoteGate.record(sender, now);
        if (macroFeedback.get()) {
            info("Running macro %s for %s.", macro.shortcut(), Messages.player(sender));
        }
        postMacroCard(macro, sender, outcome.command(), true);
        sendMacroCommand(outcome.command(), macro);
        return true;
    }

    // ------------------------------------------------------------------ reports

    /**
     * Mirrors a macro run to the macros webhook, when the user opted in.
     *
     * <p>Off by default: a macro is a command the user typed, so most people do not want every one
     * of them posted to a channel. The card exists for the case where it is useful — an operator
     * letting others run shared macros wants a record of who ran what.
     */
    private void postMacroCard(ChatMacro macro, String sender, String command, boolean remote) {
        WebhookRelayModule relay = Modules.get().get(WebhookRelayModule.class);
        if (relay == null || !relay.macroCardsAllowed()) return;
        relay.sendMacroRun(macro.shortcut(), sender, command, remote, (success, message) -> {
            if (!success) {
                info("Macro card could not be delivered: %s", message);
            }
        });
    }

    /**
     * Posts a report to the webhook, pinging the moderator role.
     *
     * <p>The decision carries {@code kind|reporter|reason}; splitting it here keeps the brain free of
     * the webhook settings while still letting the permission gate run before anything is sent.
     */
    private boolean sendReport(String encoded, String fallbackSender) {
        String[] parts = encoded.split("\\|", 3);
        if (parts.length < 3) return false;
        String kind = parts[0];
        String reporter = parts[1].isBlank() ? fallbackSender : parts[1];
        String reason = parts[2];

        WebhookRelayModule relay = Modules.get().get(WebhookRelayModule.class);
        if (relay == null || !relay.reportsAllowed()) return false;

        if ("summary".equalsIgnoreCase(kind)) {
            // Hard local-only gate, independent of the permissions setting, for the same reason as
            // modcall: with permissions switched off - the default - a LOCAL_ONLY default is not
            // enforced, and anyone in public chat could post the operator's chat archive to the
            // operator's webhook under the operator's name.
            if (!isLocalSpeaker(reporter)) return false;
            if (!relay.configured()) return false;
            relay.sendSummary(reporter, reason);
            return true;
        }

        return relay.sendReport(reporter, reason, kind, (success, message) -> {
            if (!success) {
                notifyUser(NotificationType.IMPORTANT_CHAT, config.name(),
                    "Report could not be delivered: " + message);
            }
        });
    }

    /**
     * Mirrors a local {@code !status} readout to the admin webhook, when one is configured.
     *
     * <p>Only the overview is pushed, and only when it was answered locally: a command already sent
     * to public chat has been seen by everyone there, so posting it again adds nothing. The card goes
     * to the admin webhook specifically because it carries the player's own figures.
     */
    private void pushStatusCard(AssistantBrain.Decision decision) {
        WebhookRelayModule relay = Modules.get().get(WebhookRelayModule.class);
        if (relay == null || !relay.adminStatusAllowed()) return;
        relay.sendAdminStatus(McServerStats.snapshot(mc, System.currentTimeMillis(),
            com.logvex.addon.LogClient.sessionStartMs()), null);
    }

    // ------------------------------------------------------------------ delivery

    private void deliver(AssistantBrain.Decision decision, String sender) {
        // A report replaces the brain's optimistic confirmation with what actually happened: the
        // brain cannot know whether a webhook is configured, and "Report sent" when nothing was
        // queued would be a lie the reporter cannot check.
        boolean reported = !decision.hasReport() || sendReport(decision.report(), sender);
        if (decision.hasReport() && !reported) {
            notifyUser(NotificationType.IMPORTANT_CHAT, config.name(),
                "Report not sent: the webhook is not configured. Tell a moderator another way.");
            if (decision.sound()) playAlert();
            return;
        }
        if (statusWebhook.get() && decision.hasStatusCard()) {
            pushStatusCard(decision);
        }
        switch (decision.delivery()) {
            case CHAT -> speakAll(decision);
            case PRIVATE -> whisperAll(decision, sender);
            case NOTIFY -> {
                // A notification body can hold newlines, so a block keeps its shape here too.
                String body = decision.hasEmbed()
                    ? String.join("\n", decision.lines(replyWidth.get(), replyFormat.get().style()))
                    : decision.text();
                // A notification is rendered into the same chat box as an incoming line, so it is
                // recorded as ours too; otherwise a notification that quotes a trigger would be
                // read back as chat and answered.
                rememberOwnReply(decision.text());
                notifyUser(NotificationType.IMPORTANT_CHAT, config.name(), body);
            }
            case SILENT -> {
                // Nothing visible; memory bookkeeping already happened in the brain. Recorded
                // anyway so the guard covers every mode rather than only the speaking one.
                rememberOwnReply(decision.text());
            }
            case NONE -> {
                return;
            }
        }
        if (decision.sound()) playAlert();
        if (decision.remember()) memoryDirty = true;
        if (matchFeedback.get() && decision.rule() != null) {
            info("Trigger '%s' matched for %s.", decision.rule().pattern(), Messages.player(sender));
        }
    }

    /**
     * Sends a decision as one or more lines.
     *
     * <p>A block (such as {@code !help}) is rendered to several lines and each is sent in turn, so
     * the values line up instead of being crammed into one wrapping sentence. The cap is deliberate:
     * a long list must not turn into a wall of chat.
     */
    private void speakAll(AssistantBrain.Decision decision) {
        java.util.List<String> lines = decision.linesCapped(
            replyWidth.get(), replyFormat.get().style(), MAX_EMBED_LINES);
        if (lines.size() <= 1) {
            for (String line : lines) speak(line);
            return;
        }
        // A block is a dozen lines; sending them in one tick is what a spam filter kicks for. They
        // are queued instead and released a line at a time by drainPacer().
        java.util.List<SendPacer.Line> queued = new java.util.ArrayList<>(lines.size());
        for (String line : lines) queued.add(new SendPacer.Line("", "", line));
        pacer.enqueue(queued);
    }

    /**
     * Sends a decision to the speaker alone, through the server's private-message command.
     *
     * <p>Used by {@link TriggerRule.ReplyMode#PRIVATE}: the answer goes only to whoever asked, so a
     * public chat full of people does not see the reply. The verb comes from the rule (default
     * {@code msg}), and the recipient is the name this client could verify, never one the message
     * claimed — otherwise a stranger could have their own words delivered to someone else.
     */
    private void whisperAll(AssistantBrain.Decision decision, String sender) {
        if (sender == null || sender.isBlank()) {
            // No verifiable name means no safe recipient. Falling back to public chat would leak a
            // reply the rule asked to keep private, so nothing is sent.
            return;
        }
        String verb = decision.rule() == null ? TriggerRule.DEFAULT_WHISPER
            : decision.rule().whisperCommand();
        java.util.List<String> lines = decision.linesCapped(
            replyWidth.get(), replyFormat.get().style(), MAX_EMBED_LINES);
        if (lines.size() <= 1) {
            for (String line : lines) whisper(verb, sender, line);
            return;
        }
        // This is the path !cmds takes: an 11-line block became 11 /msg commands in one tick, which
        // is a kick on any server with a flood filter. Queued and paced instead.
        java.util.List<SendPacer.Line> queued = new java.util.ArrayList<>(lines.size());
        for (String line : lines) queued.add(new SendPacer.Line(sender, verb, line));
        pacer.enqueue(queued);
    }

    /** Sends one line as {@code /<verb> <player> <text>}. */
    private void whisper(String verb, String player, String text) {
        if (text == null || text.isBlank()) return;
        if (mc.player == null || mc.player.networkHandler == null) return;
        String line = "/" + verb + " " + player + " " + text.strip();
        // Recorded before sending for the same reason as speak(): the echo comes back through the
        // chat pipeline and must be recognised as ours rather than answered.
        rememberOwnReply(line);
        sendingMacro = true;
        try {
            ChatUtils.sendPlayerMsg(line);
        } finally {
            sendingMacro = false;
        }
    }

    /** Sends {@code text} as a real chat message, guarding against self-reply loops. */
    private void speak(String text) {
        if (text == null || text.isBlank()) return;
        if (mc.player == null || mc.player.networkHandler == null) return;
        String trimmed = text.strip();
        // Recorded before sending: the echo of this line must be recognised as ours when it
        // arrives, whatever its delivery mode.
        rememberOwnReply(trimmed);
        // Held across the send so the module never reads its own line back as a typed command.
        sendingMacro = true;
        try {
            ChatUtils.sendPlayerMsg(trimmed);
        } finally {
            sendingMacro = false;
        }
    }

    /**
     * Marks {@code text} as a line the assistant produced.
     *
     * <p>Every reply goes through here, in every mode, so the guard covers public chat, a local
     * notification rendered into the chat box, and a silent reply that is only logged. The set is
     * bounded and short-lived in effect: it holds the most recent replies, which is all that is
     * needed to catch an echo.
     */
    private void rememberOwnReply(String text) {
        selfReplies.record(text);
    }

    private void playAlert() {
        if (mc.player == null) return;
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 1.5f);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs one chat-path step so a failure is reported instead of thrown.
     *
     * <p>The assistant is a listener on two of the game's busiest events. Anything thrown from a
     * listener is not contained by the caller, so a single unexpected value — an odd item stack, a
     * server that sends a strange player list, a hand-edited setting — would surface as a crash on
     * the next chat line. Catching here keeps that failure to one dropped reply, and the log names
     * the step so the cause is findable.
     *
     * <p>Repeats are counted rather than logged: a fault that fires on every message would otherwise
     * fill the log and cost more than it reports.
     */
    private void guard(String where, Runnable step) {
        try {
            step.run();
        } catch (Throwable failure) {
            int seen = failureCounts.merge(where, 1, Integer::sum);
            if (seen <= 3) {
                info("Assistant %s step failed (%s). This is a bug; chat keeps working.", where,
                    failure);
                if (seen == 3) info("Further %s failures will be counted silently.", where);
            }
        }
    }

    private boolean watched(MessageType type) {
        return switch (type) {
            case PUBLIC_CHAT -> watchPublicChat.get();
            case PLAYER_MESSAGE -> watchPrivateMessages.get();
            case SYSTEM, ANNOUNCEMENT, GAME, JOIN, LEAVE, DEATH -> watchSystem.get();
            case OTHER -> false;
        };
    }

    /**
     * True when this line must not be answered.
     *
     * <p>Central on purpose: the check runs once, before trigger matching, before the command
     * parser and before the AI, so no individual path can forget it. That is what makes recursion
     * impossible — an assistant reply is recorded here, so its echo is refused at the top of the
     * pipeline and can never reach a trigger that would answer it again.
     */
    private boolean isOwnMessage(String sender, String content) {
        return selfReplies.shouldIgnore(sender, ownName, content);
    }

    /**
     * The sender as the client can verify them.
     *
     * <p>The name comes from the message, because that is all a chat line carries. The UUID comes
     * from the client's player list, which the server populated, so it is the one part of the
     * identity that cannot be typed into chat. When the name is not in the player list the identity
     * stays unverified, and a UUID-pinned whitelist entry will refuse it — which is the correct
     * outcome, because in that case the client genuinely cannot prove who spoke.
     */
    public SenderIdentity identify(String sender) {
        if (sender == null || sender.isBlank()) return SenderIdentity.unverified("");
        String name = sender.trim();

        if (!ownName.isBlank() && name.equalsIgnoreCase(ownName)) {
            return SenderIdentity.local(name, ownUuid());
        }
        java.util.UUID uuid = lookupUuid(name);
        return uuid == null ? SenderIdentity.unverified(name) : new SenderIdentity(name, uuid, false);
    }

    /** The local player's own account UUID, or null when there is no player yet. */
    private java.util.UUID ownUuid() {
        if (mc.player == null) return null;
        var profile = mc.player.getGameProfile();
        return profile == null ? null : profile.getId();
    }

    /** Resolves {@code name} through the client's player list, or null when it is not there. */
    private java.util.UUID lookupUuid(String name) {
        if (mc.getNetworkHandler() == null) return null;
        var entry = mc.getNetworkHandler().getPlayerListEntry(name);
        if (entry == null) {
            // The list is keyed by UUID, so a case-insensitive fallback keeps "notch" working for
            // "Notch" without ever inventing an account.
            for (var candidate : mc.getNetworkHandler().getPlayerList()) {
                var profile = candidate.getProfile();
                if (profile != null && name.equalsIgnoreCase(profile.getName())) {
                    entry = candidate;
                    break;
                }
            }
        }
        if (entry == null) return null;
        var profile = entry.getProfile();
        return profile == null ? null : profile.getId();
    }

    /**
     * The speaker's latency, as the tab list reports it.
     *
     * <p>Every visible player's latency is in the tab list, so this answers for whoever asked. The
     * local player is read directly, because their own entry is the one the client always has.
     * Returns a negative value when the figure is not available, which the command reports as such
     * rather than substituting someone else's number.
     */
    private int requesterPing(SenderIdentity identity, String sender) {
        if (identity != null && identity.local()) {
            if (mc.player == null) return -1;
            var own = mc.getNetworkHandler() == null ? null
                : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            return own == null ? -1 : own.getLatency();
        }
        if (sender == null || sender.isBlank() || mc.getNetworkHandler() == null) return -1;
        var entry = mc.getNetworkHandler().getPlayerListEntry(sender);
        if (entry == null) {
            for (var candidate : mc.getNetworkHandler().getPlayerList()) {
                var profile = candidate.getProfile();
                if (profile != null && sender.equalsIgnoreCase(profile.getName())) {
                    entry = candidate;
                    break;
                }
            }
        }
        return entry == null ? -1 : entry.getLatency();
    }

    /** True when {@code sender} is this client's own player, so an action is really theirs. */
    private boolean isLocalSpeaker(String sender) {
        String mine = localPlayerName();
        if (mine == null || mine.isBlank()) return false;
        if (sender == null || sender.isBlank()) return false;
        return mine.equalsIgnoreCase(sender.strip());
    }

    private String localPlayerName() {
        if (mc.player == null) return "";
        var profile = mc.player.getGameProfile();
        return profile == null || profile.getName() == null ? "" : profile.getName();
    }

    /** The live status settings as the core reads them. */
    private StatusCommands.Settings statusSettings() {
        if (!statusEnabled.get()) return new StatusCommands.Settings(false, null);
        return new StatusCommands.Settings(true, StatusOutput.decodeAll(statusLines.get()));
    }

    /** The shipped status table, as configuration lines. */
    private static List<String> defaultStatusLines() {
        return StatusOutput.encodeAll(StatusOutput.defaultsAll());
    }

    /** Rebuilds the runtime's rules, config and permissions from the current settings. */
    public void rebuild() {
        parsed = TriggerSet.of(parseRules());
        rebuildPacer();
        rebuildRemoteGate();
        config = new AssistantConfig(
            assistantName.get(),
            respondToTriggers.get(),
            memoryCommands.get(),
            commandPrefix.get(),
            memoryReplyMode.get(),
            rememberConversations.get(),
            rateLimit.get(),
            maxReplyLength.get()
        );
        runtime.setTriggers(parsed);
        runtime.brain().setTriggerSet(parsed);
        runtime.setConfig(config);
        runtime.setPermissions(buildPermissions());
        runtime.setDuplicateFilter(duplicateFilter.get());
        runtime.setHelpEnabled(helpCommand.get());
        runtime.setCommandDefaults(CommandDefaults.parseAll(commandLines.get()));
        runtime.setGreetingPools(greetingPools());
        runtime.setGoodGameReply(goodGameReply.get());
        macros = macrosEnabled.get()
            ? MacroSet.decode(macroLines.get(), commandPrefix.get())
            : MacroSet.EMPTY;
        runtime.brain().setMacros(macros);
        // A supplier, so !reports reads the relay's live log rather than a snapshot taken at
        // rebuild time; the relay may be reloaded after this runs.
        runtime.brain().setReportLogSource(() -> {
            WebhookRelayModule relay = Modules.get().get(WebhookRelayModule.class);
            return relay == null ? null : relay.reportLog();
        });
    }

    /** The default macro table, as configuration lines. */
    private static List<String> defaultMacroLines() {
        return ChatMacro.defaults().stream().map(ChatMacro::encode).toList();
    }

    /** Rebuilds the send pacer from the pacing settings. */
    private void rebuildPacer() {
        if (!pacingEnabled.get()) {
            pacer = SendPacer.immediate();
            return;
        }
        pacer = new SendPacer(pacingGapMs.get(), pacingBurst.get(), pacingBurstWindowMs.get(),
            PACER_CAPACITY);
    }

    /** Rebuilds the remote-macro gate from its settings. */
    private void rebuildRemoteGate() {
        remoteGate = new RemoteMacroGate(macrosEnabled.get() && macrosRemote.get(),
            macrosAllowList.get(), macrosRemoteCooldownMs.get());
    }

    /** The widest line a formatted reply may use, so other commands can match the assistant. */
    public int replyWidth() {
        return replyWidth.get();
    }

    /**
     * The layout the user chose for multi-field replies, resolved to a concrete style.
     *
     * <p>{@code AUTO} keeps whatever each reply was written for, so it resolves to the box here:
     * that is what a caller building its own block wants when it has no layout of its own.
     */
    public com.logvex.addon.assistant.core.ReplyFormat.Style replyStyle() {
        com.logvex.addon.assistant.core.ReplyFormat.Style style = replyFormat.get().style();
        return style == null ? com.logvex.addon.assistant.core.ReplyFormat.Style.BOX : style;
    }

    public MacroSet macroSet() {
        return macros;
    }

    /** The raw macro configuration lines, as the setting holds them. */
    public Setting<List<String>> macroLinesSetting() {
        return macroLines;
    }

    /** The live status table, as the setting holds it. */
    public Setting<List<String>> statusLinesSetting() {
        return statusLines;
    }

    /** True when the status commands may answer at all. */
    public boolean statusEnabled() {
        return statusEnabled.get();
    }

    /** Adds a macro from one configuration line. Returns false when the line is not a macro. */
    public boolean addMacro(String line) {
        ChatMacro macro = ChatMacro.decode(line);
        if (macro == null) return false;
        List<String> updated = new ArrayList<>(macroLines.get());
        updated.add(macro.encode());
        macroLines.set(updated);
        rebuild();
        return true;
    }

    /**
     * Replaces one line, leaving the rest in place.
     *
     * @return false when the line is not a macro or the index is out of range, so the caller can say
     *         so rather than silently dropping the edit
     */
    public boolean replaceMacro(int index, String line) {
        List<String> updated = new ArrayList<>(macroLines.get());
        if (index < 0 || index >= updated.size()) return false;
        ChatMacro macro = ChatMacro.decode(line);
        if (macro == null) return false;
        updated.set(index, macro.encode());
        macroLines.set(updated);
        rebuild();
        return true;
    }

    public void removeMacro(int index) {
        List<String> updated = new ArrayList<>(macroLines.get());
        if (index < 0 || index >= updated.size()) return;
        updated.remove(index);
        macroLines.set(updated);
        rebuild();
    }

    /** Restores the shipped macro table, discarding whatever the user had. */
    public void restoreDefaultMacros() {
        macroLines.set(defaultMacroLines());
        rebuild();
    }

    /** Every spelling claimed by more than one macro, so the GUI can flag an ambiguous table. */
    public List<String> macroDuplicates() {
        return macros.duplicates();
    }

    // ------------------------------------------------------------------ chat command macros

    /**
     * Turns a shortcut the local player typed into the command it stands for.
     *
     * <p>This listens for the local player's own outgoing message and cancels it, then sends the
     * expanded command instead. Cancelling is what keeps the shortcut out of chat: without it the
     * literal {@code !spawn} would be posted and the server would see it as ordinary chat.
     *
     * <p>The handler is deliberately separate from {@link #onReceiveMessage}: that one reacts to other
     * players, this one rewrites what the local player is about to say. Nothing here can match an
     * incoming message, so a macro can never behave like an automatic response.
     */
    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        // Same reason as the receive path, and it matters more here: this runs on every message the
        // player sends, so an unguarded throw would break ordinary chatting, not just the assistant.
        guard("send", () -> sendStep(event));
    }

    private void sendStep(SendMessageEvent event) {
        if (!isActive() || event == null) return;
        if (!ready() || sendingMacro) return;
        // ModCallModule also handles this command, and it cancels the event as it files. Whichever
        // listener Meteor dispatches first claims the report; the other must not file it again.
        if (event.isCancelled()) return;

        String message = event.message;
        if (message == null) return;

        // An assistant command typed by the local player has to be handled here: the receive path
        // ignores the player's own messages, so `!mod`, `!macros` and the rest would otherwise never
        // be answered. Commands win over macros, so a macro can never shadow `!mod`.
        if (handleLocalCommand(event, message)) return;

        if (!macrosEnabled.get()) return;
        java.util.Optional<MacroSet.Call> found = macros.resolve(message);
        if (found.isEmpty()) return;

        // The shortcut must never reach the server, whether or not the command turns out to be
        // sendable. Cancelling first means a failure cannot leak the raw text into chat.
        event.cancel();

        MacroSet.Call call = found.get();
        ChatMacro macro = call.macro();
        ChatMacro.Outcome outcome = macro.run(call.input());
        if (!outcome.ok()) {
            if (macroFeedback.get()) info("%s", outcome.problem());
            return;
        }
        if (macros.wouldRecurse(macro, outcome.command())) {
            if (macroFeedback.get()) {
                info("Macro %s would send itself, so nothing was sent.", macro.shortcut());
            }
            return;
        }
        postMacroCard(macro, ownName, outcome.command(), false);
        sendMacroCommand(outcome.command(), macro);
    }

    /**
     * Runs an assistant command the local player typed, and cancels it so the shortcut is not sent.
     *
     * <p>Only a line the assistant actually answers is cancelled: an ordinary message, or a server
     * command such as {@code /spawn}, passes straight through untouched.
     *
     * @return true when the message was an assistant command and has been consumed
     */
    private boolean handleLocalCommand(SendMessageEvent event, String message) {
        if (!config.memoryCommands() || !message.startsWith(config.commandPrefix())) return false;
        if (message.strip().equals(config.commandPrefix())) return false;

        long now = System.currentTimeMillis();
        SenderIdentity identity = mc.player == null
            ? SenderIdentity.unverified(ownName)
            : SenderIdentity.local(mc.player.getGameProfile().getName(), mc.player.getUuid());
        AssistantBrain.Decision decision = runtime.handle(identity, message, now,
            McServerStats.snapshot(mc, now, sessionStart, speed.blocksPerSecond()), statusSettings(), -1);
        if (decision == null || !decision.acts()) return false;

        event.cancel();
        deliver(decision, identity.name());
        return true;
    }

    /** Sends an expanded command, with the guard set so it cannot be read as another macro. */
    private void sendMacroCommand(String command, ChatMacro macro) {
        sendingMacro = true;
        try {
            if (macroEchoCommand.get()) {
                ChatUtils.sendPlayerMsg(command);
            } else {
                // Sent without being echoed, so the command does not appear in local chat.
                if (mc.getNetworkHandler() != null) {
                    String bare = command.startsWith("/") ? command.substring(1) : command;
                    mc.getNetworkHandler().sendChatCommand(bare);
                }
            }
            if (macroFeedback.get()) info("%s -> %s", macro.shortcut(), command);
        } catch (RuntimeException failure) {
            if (macroFeedback.get()) info("Could not send %s.", macro.shortcut());
        } finally {
            sendingMacro = false;
        }
    }

    /** The permission model as the current settings describe it. */
    public PermissionSet buildPermissions() {
        return PermissionSet.of(
            permissionsEnabled.get(),
            administrators.get(),
            commandPermissions.get(),
            defaultPolicy.get(),
            !requireVerifiedNames.get()
        );
    }

    /** The four greeting pools, keyed by bucket id. */
    public java.util.Map<String, String> greetingPools() {
        java.util.Map<String, String> pools = new java.util.LinkedHashMap<>();
        pools.put("morning", morningGreetings.get());
        pools.put("afternoon", afternoonGreetings.get());
        pools.put("evening", eveningGreetings.get());
        pools.put("night", nightGreetings.get());
        return java.util.Map.copyOf(pools);
    }

    private List<TriggerRule> parseRules() {
        List<TriggerRule> out = new ArrayList<>();
        for (String encoded : rules.get()) {
            TriggerRule rule = TriggerRule.decode(encoded);
            if (rule != null) out.add(rule);
        }
        return out;
    }

    private void flushMemory() {
        if (!persist.get()) {
            memoryDirty = false;
            return;
        }
        service.saveAsync();
        memoryDirty = false;
        lastSaveAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ public API

    public AssistantRuntime runtime() {
        return runtime;
    }

    public AssistantMemory memory() {
        return service.memory();
    }

    /** The current server and player figures, as the chat commands and placeholders see them. */
    public ServerStats liveStats() {
        return McServerStats.snapshot(mc, System.currentTimeMillis(), sessionStart,
            speed.blocksPerSecond());
    }

    public TriggerSet triggers() {
        return parsed;
    }

    public AssistantConfig config() {
        return config;
    }

    /** The permission model currently in force. */
    public PermissionSet permissions() {
        return runtime.permissions();
    }

    /** This session's chat activity, for {@code !activity} and the status tab. */
    public ChatActivity activity() {
        return runtime.activity();
    }

    /**
     * What the assistant can and cannot do right now, derived from live state.
     *
     * <p>Read on demand rather than cached: the answer depends on the connection, the settings and
     * whether a reports channel exists, all of which change without the module being rebuilt.
     */
    public com.logvex.addon.assistant.core.Capabilities capabilities() {
        WebhookRelayModule relay = Modules.get().get(WebhookRelayModule.class);
        boolean reports = relay != null && relay.reportsAllowed();
        return com.logvex.addon.assistant.core.Capabilities.of(
            McServerStats.snapshot(mc, System.currentTimeMillis(),
                com.logvex.addon.LogClient.sessionStartMs()),
            config(), reports, permissionsEnabled(),
            (int) triggers().rules().stream()
                .filter(com.logvex.addon.assistant.core.TriggerRule::enabled).count(),
            memory().facts().size());
    }

    public boolean permissionsEnabled() {
        return permissionsEnabled.get();
    }

    /** The local player's name, or an empty string when there is no player yet. */
    public String selfName() {
        return ownName;
    }

    /** The encoded permission rules, for the permissions screen to edit in place. */
    @SuppressWarnings("unchecked")
    public Setting<List<String>> permissionsSetting() {
        return (Setting<List<String>>) settings.get("command-permissions");
    }

    @SuppressWarnings("unchecked")
    public Setting<List<String>> administratorsSetting() {
        return (Setting<List<String>>) settings.get("administrators");
    }

    /**
     * Replaces the rule for {@code command}, or adds one when it has none.
     *
     * <p>Every edit goes through here so a command can never end up with two rules: the older one
     * is removed first, which keeps the "last one wins" read order in {@link PermissionSet#of}
     * irrelevant for anything written by the GUI.
     */
    public boolean setPermission(CommandPermission rule) {
        if (rule == null || rule.command().isBlank()) return false;
        List<String> updated = new ArrayList<>();
        for (String encoded : permissionsSetting().get()) {
            CommandPermission existing = CommandPermission.decode(encoded);
            if (existing != null && existing.covers(rule.command())) continue;
            updated.add(encoded);
        }
        updated.add(rule.encode());
        permissionsSetting().set(updated);
        rebuild();
        return true;
    }

    /** Removes the rule for {@code command}, returning it to the configured default. */
    public boolean clearPermission(String command) {
        if (command == null) return false;
        List<String> updated = new ArrayList<>();
        boolean removed = false;
        for (String encoded : permissionsSetting().get()) {
            CommandPermission existing = CommandPermission.decode(encoded);
            if (existing != null && existing.covers(command)) {
                removed = true;
                continue;
            }
            updated.add(encoded);
        }
        if (!removed) return false;
        permissionsSetting().set(updated);
        rebuild();
        return true;
    }

    /** The rule configured for {@code command}, or empty when it has none. */
    public java.util.Optional<CommandPermission> permissionFor(String command) {
        return permissions().rule(command);
    }

    /** Adds {@code entry} to the whitelist of {@code command}, creating the rule if needed. */
    public boolean addWhitelisted(String command, String entry) {
        String cleaned = CommandPermission.cleanEntry(entry);
        if (cleaned == null) return false;
        CommandPermission rule = permissionFor(command)
            .orElse(CommandPermission.open(command));
        List<String> users = new ArrayList<>(rule.users());
        for (String existing : users) {
            if (existing.equalsIgnoreCase(cleaned)) return false;
        }
        users.add(cleaned);
        return setPermission(rule.withLevel(PermissionLevel.WHITELIST).withUsers(users));
    }

    /** Removes {@code entry} from the whitelist of {@code command}. */
    public boolean removeWhitelisted(String command, String entry) {
        CommandPermission rule = permissionFor(command).orElse(null);
        if (rule == null || entry == null) return false;
        List<String> users = new ArrayList<>();
        boolean removed = false;
        for (String existing : rule.users()) {
            if (CommandPermission.entryMatchesName(existing, entry)
                || existing.equalsIgnoreCase(entry.trim())) {
                removed = true;
                continue;
            }
            users.add(existing);
        }
        if (!removed) return false;
        return setPermission(rule.withUsers(users));
    }

    /** Adds {@code entry} to the administrator list. */
    public boolean addAdministrator(String entry) {
        String cleaned = CommandPermission.cleanEntry(entry);
        if (cleaned == null) return false;
        List<String> updated = new ArrayList<>(administratorsSetting().get());
        for (String existing : updated) {
            if (existing.equalsIgnoreCase(cleaned)) return false;
        }
        updated.add(cleaned);
        administratorsSetting().set(updated);
        rebuild();
        return true;
    }

    /** Removes {@code entry} from the administrator list. */
    public boolean removeAdministrator(String entry) {
        if (entry == null) return false;
        List<String> updated = new ArrayList<>();
        boolean removed = false;
        for (String existing : administratorsSetting().get()) {
            if (CommandPermission.entryMatchesName(existing, entry)
                || existing.equalsIgnoreCase(entry.trim())) {
                removed = true;
                continue;
            }
            updated.add(existing);
        }
        if (!removed) return false;
        administratorsSetting().set(updated);
        rebuild();
        return true;
    }

    /** The encoded rule list, for the GUI to edit in place. */
    @SuppressWarnings("unchecked")
    public Setting<List<String>> rulesSetting() {
        return (Setting<List<String>>) settings.get("rules");
    }

    /** Adds a rule and rebuilds. Returns false when the pattern was blank. */
    public boolean addRule(TriggerRule rule) {
        if (rule == null || rule.pattern().isBlank()) return false;
        List<String> updated = new ArrayList<>(rules.get());
        updated.add(rule.encode());
        rules.set(updated);
        rebuild();
        return true;
    }

    public boolean removeRule(int index) {
        List<String> updated = new ArrayList<>(rules.get());
        if (index < 0 || index >= updated.size()) return false;
        updated.remove(index);
        rules.set(updated);
        rebuild();
        return true;
    }

    public boolean replaceRule(int index, String encoded) {
        List<String> updated = new ArrayList<>(rules.get());
        if (index < 0 || index >= updated.size()) return false;
        TriggerRule rule = TriggerRule.decode(encoded);
        updated.set(index, rule == null ? encoded : rule.encode());
        rules.set(updated);
        rebuild();
        return true;
    }

    public void restoreDefaultRules() {
        rules.set(TriggerRule.defaults().stream().map(TriggerRule::encode).toList());
        rebuild();
    }

    /**
     * Runs {@code text} as if it arrived from {@code sender}; used by the test command.
     *
     * <p>The sender is identified through the player list like a real message, so a test exercises
     * the same permission decisions a live line would.
     */
    public AssistantBrain.Decision simulate(String sender, String content) {
        long now = System.currentTimeMillis();
        SenderIdentity identity = identify(sender);
        // Deliberately does not deliver: this is the settings screen's preview, and a preview that
        // spoke in chat would send a real message every time a user pressed the button.
        return runtime.handle(identity, content, now,
            McServerStats.snapshot(mc, now, sessionStart, speed.blocksPerSecond()),
            statusSettings(), requesterPing(identity, sender));
    }

    /** Persists memory immediately (GUI and command use this). */
    public void saveMemory() {
        flushMemory();
    }

    public void clearMemory() {
        service.clear();
        runtime.resetSession();
        info("Memory cleared.");
    }

    public String statusLine() {
        return runtime.statusLine();
    }

    // ------------------------------------------------------------------ widget

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Assistant Manager...")).expandX().widget();
        open.action = () -> mc.setScreen(new AssistantScreen(this));

        WButton save = list.add(theme.button("Save Memory")).expandX().widget();
        save.action = this::saveMemory;

        WButton permissions = list.add(theme.button("Assistant Permissions...")).expandX().widget();
        permissions.action = () -> mc.setScreen(
            new com.logvex.addon.assistant.gui.PermissionsScreen(this));

        WButton defaults = list.add(theme.button("Restore Default Triggers")).expandX().widget();
        defaults.action = () -> {
            restoreDefaultRules();
            info("Restored the default triggers.");
        };
        return list;
    }
}
