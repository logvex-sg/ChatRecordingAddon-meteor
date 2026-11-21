package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportEmbed;
import com.logvex.addon.chatlog.core.WebhookPayload;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discord rejects an embed whose visible text exceeds 6000 characters across all its parts.
 *
 * <p>{@link DiscordEmbed} clips each part to its own limit as it is added, which stops one runaway
 * field. It does not stop the sum: a description filled to its 4096 limit plus a long footer is
 * already over budget, and the API answers with a 400. Nothing checked the total, so a busy server
 * plus a long footer silently lost the whole card.
 */
class EmbedBudgetTest {

    private static final int BUDGET = DiscordEmbed.TOTAL_LIMIT;

    @Test
    @DisplayName("a full description plus a long footer stays within Discord's total budget")
    void descriptionPlusFooterStaysWithinBudget() {
        // A footer is a user setting, so this length is reachable by typing.
        String footer = "footer-text-".repeat(200);
        WebhookEmbeds.Style style = new WebhookEmbeds.Style("blurple", footer, true, true, "", true);

        String message = "x".repeat(300);
        List<ChatEntry> entries = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            entries.add(new ChatEntry(System.currentTimeMillis(), MessageType.PUBLIC_CHAT,
                "<player" + i + ">", message, "srv", "session"));
        }

        DiscordEmbed embed = WebhookEmbeds.summary(style, "srv", entries);
        assertTrue(embed.length() <= BUDGET,
            "embed is " + embed.length() + " chars, over the " + BUDGET + " budget; Discord rejects it");
    }

    @Test
    @DisplayName("a card with many long fields stays within the total budget")
    void manyLongFieldsStayWithinBudget() {
        DiscordEmbed embed = new DiscordEmbed().title("t");
        for (int i = 0; i < DiscordEmbed.MAX_FIELDS; i++) {
            embed.field("field" + i, "v".repeat(DiscordEmbed.FIELD_VALUE_LIMIT), false);
        }
        assertTrue(embed.length() <= BUDGET,
            "embed is " + embed.length() + " chars, over the " + BUDGET + " budget");
    }

    @Test
    @DisplayName("a plain-text report mention is bounded to the content limit")
    void plainTextMentionIsBounded() {
        // The role field accepts free text as a fallback, so it can be arbitrarily long. Discord
        // rejects a body over 2000 chars, and that rejection is not retryable.
        String mention = ReportEmbed.mention("m".repeat(5000));
        assertTrue(mention.length() <= WebhookPayload.CONTENT_LIMIT,
            "mention is " + mention.length() + " chars, over the " + WebhookPayload.CONTENT_LIMIT + " limit");
    }
}
