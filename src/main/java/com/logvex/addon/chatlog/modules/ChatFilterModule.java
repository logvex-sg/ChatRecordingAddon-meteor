package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ChatFilterRule;
import com.logvex.addon.chatlog.core.ChatFilterRuleSet;
import com.logvex.addon.chatlog.mc.ChatFilterService;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.systems.modules.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * Suppresses chat noise with an ordered rule list.
 *
 * <p>Rules are evaluated top to bottom and the first match wins, so a specific {@code ALLOW} can
 * override a broad {@code HIDE} placed above it. The module is a thin settings holder over
 * {@link ChatFilterService}, which does the actual suppression in the chat mixin — filtering here
 * rather than merely hiding the message in a HUD is what actually removes it from chat.
 */
public class ChatFilterModule extends Module {
    private final SettingGroup sgRules = settings.getDefaultGroup();
    private final SettingGroup sgDefaults = settings.createGroup("Default Action");

    private final Setting<List<String>> rules = sgRules.add(new StringListSetting.Builder()
        .name("rules")
        .description("Filter rules, one per line, applied top to bottom. First match wins.")
        .defaultValue(defaultRules())
        .wide()
        .build()
    );

    private final Setting<ChatFilterRule.Action> defaultAction =
        sgDefaults.add(new EnumSetting.Builder<ChatFilterRule.Action>()
            .name("default-action")
            .description("What to do with a message no rule matches.")
            .defaultValue(ChatFilterRule.Action.ALLOW)
            .build()
        );

    private final Setting<Boolean> announceHides = sgRules.add(new BoolSetting.Builder()
        .name("announce-hides")
        .description("Print a chat note the first time a rule hides a message.")
        .defaultValue(false)
        .build()
    );

    private final ChatFilterService service = ChatFilterService.get();

    private List<ChatFilterRule> parsed = List.of();
    private long announcedHides = -1;

    public ChatFilterModule() {
        super(LogClient.CATEGORY, "chat-filter",
            "Suppresses chat noise with ordered hide/allow rules.");
    }

    @Override
    public void onActivate() {
        parsed = parseRules();
        service.setRules(ChatFilterRuleSet.of(parsed, defaultAction.get()));
        service.setEnabled(true);
        announcedHides = -1;
    }

    @Override
    public void onDeactivate() {
        service.setEnabled(false);
        service.setRules(ChatFilterRuleSet.empty());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Rebuild only when the encoded rule list actually changed, so a tick costs one list compare.
        List<ChatFilterRule> current = parseRules();
        if (!current.equals(parsed) || service.rules().defaultAction() != defaultAction.get()) {
            parsed = current;
            service.setRules(ChatFilterRuleSet.of(parsed, defaultAction.get()));
        }

        if (announceHides.get() && service.hiddenCount() != announcedHides) {
            announcedHides = service.hiddenCount();
            info("Filtered %s. Latest: %s",
                com.logvex.addon.common.Messages.plural(service.hiddenCount(), "message"),
                service.lastHidden());
        }
    }

    private List<ChatFilterRule> parseRules() {
        List<ChatFilterRule> out = new ArrayList<>();
        for (String encoded : rules.get()) {
            ChatFilterRule rule = ChatFilterRule.decode(encoded);
            if (rule != null) out.add(rule);
        }
        return out;
    }

    /** The rules currently in force, for the GUI and the command. */
    public List<ChatFilterRule> currentRules() {
        return parsed;
    }

    public ChatFilterService service() {
        return service;
    }

    private static List<String> defaultRules() {
        return List.of(
            ChatFilterRule.hide("wts").encode(),
            ChatFilterRule.hide("wtb").encode(),
            new ChatFilterRule("", "", com.logvex.addon.chatlog.core.MessageType.ANNOUNCEMENT,
                ChatFilterRule.Action.ALLOW, false, false, true).encode()
        );
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton clear = list.add(theme.button("Reset Filter Stats")).expandX().widget();
        clear.action = () -> {
            service.resetStats();
            info("Filter statistics reset.");
        };
        WButton dashboard = list.add(theme.button("Edit Rules")).expandX().widget();
        dashboard.action = () -> mc.setScreen(new com.logvex.addon.gui.ModuleSettingsScreen(this));
        return list;
    }
}
