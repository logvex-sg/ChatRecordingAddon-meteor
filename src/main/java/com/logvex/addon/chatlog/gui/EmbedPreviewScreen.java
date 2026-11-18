package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.modules.WebhookRelayModule;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;
import java.util.Map;

/**
 * Renders the embeds the relay would send, so the appearance can be checked without a webhook.
 *
 * <p>Each card is drawn with its accent stripe, title and fields in the order Discord shows them,
 * using sample data drawn from the live buffer where possible. A "Copy JSON" button puts the exact
 * payload on the clipboard so it can be pasted into a Discord webhook tester.
 */
public class EmbedPreviewScreen extends WindowScreen {
    private enum Card {
        ALERT("Keyword alert"),
        SUMMARY("Chat summary"),
        DIGEST("Chat digest"),
        SESSION("Session summary"),
        RENAME("Player rename"),
        TEST("Webhook test");

        private final String label;

        Card(String label) {
            this.label = label;
        }
    }

    private final WebhookRelayModule relay;
    private final LogService service = LogService.get();
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private Card card = Card.ALERT;

    public EmbedPreviewScreen(WebhookRelayModule relay) {
        super(GuiThemes.get(), "Embed Preview");
        this.relay = relay;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        WHorizontalList tabs = add(theme.horizontalList()).expandX().widget();
        for (Card value : Card.values()) {
            WButton button = tabs.add(theme.button(value == card ? "[" + value.label + "]" : value.label))
                .expandX().widget();
            button.action = () -> {
                card = value;
                render();
            };
        }

        DiscordEmbed embed = sample();

        // The accent stripe mirrors Discord's left border.
        WVerticalList frame = add(theme.verticalList()).expandX().widget();
        frame.add(theme.label("\u2588 " + (embed == null ? "" : accentLabel(embed)))).expandX().widget()
            .color(accentColor(embed));

        if (embed == null) {
            add(theme.label("No data for this card yet.")).expandX().widget().color(GuiKit.MUTED);
        } else {
            renderEmbed(embed);
        }

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton copy = actions.add(theme.button("Copy JSON")).expandX().widget();
        copy.action = () -> {
            String json = payloadJson();
            mc.keyboard.setClipboard(json);
            com.logvex.addon.framework.utils.player.ChatUtils.info(
                "Copied the %s payload to the clipboard.", card.label);
        };
        WButton send = actions.add(theme.button("Send Test Embed")).expandX().widget();
        send.action = () -> relay.test();
    }

    private void renderEmbed(DiscordEmbed embed) {
        Map<String, Object> map = embed.toMap();
        Object title = map.get("title");
        if (title != null) {
            add(theme.label(String.valueOf(title))).expandX().widget().color(GuiKit.ACCENT);
        }
        Object description = map.get("description");
        if (description != null) {
            for (String line : String.valueOf(description).split("\n")) {
                add(theme.label(GuiKit.truncate(line, 90))).expandX().widget();
            }
        }
        Object fields = map.get("fields");
        if (fields instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> field)) continue;
                WHorizontalList row = add(theme.horizontalList()).expandX().widget();
                row.add(theme.label(String.valueOf(field.get("name")) + ":")).widget().color(GuiKit.MUTED);
                row.add(theme.label(GuiKit.truncate(String.valueOf(field.get("value")), 70)))
                    .expandX().widget();
            }
        }
        Object footer = map.get("footer");
        if (footer instanceof Map<?, ?> footerMap) {
            add(theme.label(String.valueOf(footerMap.get("text")))).expandX().widget().color(GuiKit.MUTED);
        }
    }

    private DiscordEmbed sample() {
        WebhookEmbeds.Style style = relay.style();
        long now = System.currentTimeMillis();
        return switch (card) {
            case ALERT -> WebhookEmbeds.alert(style, com.logvex.addon.chatlog.core.KeywordRule.of("wts"),
                sampleEntry(MessageType.PUBLIC_CHAT, "Steve", "WTS diamonds 64 for 5 emeralds"));
            case SUMMARY -> {
                List<ChatEntry> entries = service.recent(8);
                if (entries.isEmpty()) entries = List.of(
                    sampleEntry(MessageType.PUBLIC_CHAT, "Steve", "hello"),
                    sampleEntry(MessageType.JOIN, "Alex", "Alex joined the game"));
                yield WebhookEmbeds.summary(style, service.serverName(), entries);
            }
            case DIGEST -> WebhookEmbeds.digest(style, ChatDigest.summarise(service.recent(200), 5), service.serverName());
            case SESSION -> WebhookEmbeds.session(style,
                new SessionRecord("s-preview", service.serverName(), now - 3_600_000, now,
                    248, 12, 9, 7, 3, 4), now);
            case RENAME -> WebhookEmbeds.rename(style, "OldName", "NewName", now);
            case TEST -> WebhookEmbeds.test(style, "preview");
        };
    }

    private ChatEntry sampleEntry(MessageType type, String sender, String content) {
        return new ChatEntry(System.currentTimeMillis(), type, sender, content,
            service.serverName(), service.session() == null ? "" : service.session().id());
    }

    private String payloadJson() {
        String username = relay.username();
        DiscordEmbed embed = sample();
        if (embed == null) return "{}";
        return WebhookEmbeds.payload(username, embed);
    }

    private Color accentColor(DiscordEmbed embed) {
        Object color = embed.toMap().get("color");
        if (color instanceof Number number) {
            int rgb = number.intValue();
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
        return GuiKit.ACCENT;
    }

    private String accentLabel(DiscordEmbed embed) {
        Object color = embed.toMap().get("color");
        if (color instanceof Number number) {
            return com.logvex.addon.chatlog.core.EmbedColor.toHex(number.intValue());
        }
        return "";
    }

    @Override
    public boolean toClipboard() {
        return false;
    }

    @Override
    public boolean fromClipboard() {
        return false;
    }
}
