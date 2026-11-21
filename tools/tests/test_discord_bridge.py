"""Tests for the Discord bridge. Standard library only, so no install is needed.

Run with:  python3 -m unittest discover -s tools/tests -v
"""

import importlib.util
import json
import os
import sys
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_MODULE_PATH = os.path.join(os.path.dirname(_HERE), "discord_bridge.py")
_spec = importlib.util.spec_from_file_location("discord_bridge", _MODULE_PATH)
bridge_module = importlib.util.module_from_spec(_spec)
sys.modules["discord_bridge"] = bridge_module
_spec.loader.exec_module(bridge_module)


class SanitizeForMinecraftTest(unittest.TestCase):
    """The slash rule is a privilege boundary: sendChat() routes '/' to sendCommand()."""

    def test_leading_slash_is_stripped(self):
        self.assertEqual("op Steve", bridge_module.sanitize_for_minecraft("/op Steve"))

    def test_multiple_leading_slashes_are_stripped(self):
        self.assertEqual("op Steve", bridge_module.sanitize_for_minecraft("///op Steve"))

    def test_slash_after_whitespace_is_stripped(self):
        # A user could pad with spaces to slip a slash past a naive startswith check.
        self.assertEqual("op Steve", bridge_module.sanitize_for_minecraft("   /op Steve"))

    def test_slash_in_the_middle_is_kept(self):
        # "gg / good fight" is normal chat and must not be mangled.
        self.assertEqual("gg / good fight", bridge_module.sanitize_for_minecraft("gg / good fight"))

    def test_newlines_collapse_to_one_line(self):
        self.assertEqual("first second", bridge_module.sanitize_for_minecraft("first\nsecond"))

    def test_empty_and_none(self):
        self.assertEqual("", bridge_module.sanitize_for_minecraft(""))
        self.assertEqual("", bridge_module.sanitize_for_minecraft(None))
        self.assertEqual("", bridge_module.sanitize_for_minecraft("   "))

    def test_only_slashes_becomes_empty(self):
        self.assertEqual("", bridge_module.sanitize_for_minecraft("////"))


class EscapeForDiscordTest(unittest.TestCase):
    def test_everyone_mention_is_broken(self):
        escaped = bridge_module.escape_for_discord("@everyone come here")
        self.assertNotIn("@everyone", escaped)
        self.assertIn("everyone", escaped, "the text should stay readable")

    def test_role_mention_is_broken(self):
        self.assertNotIn("@123456789", bridge_module.escape_for_discord("hey @123456789"))

    def test_backticks_are_neutralised(self):
        self.assertNotIn("`", bridge_module.escape_for_discord("break `out` of code"))

    def test_plain_text_is_unchanged(self):
        self.assertEqual("just some chat", bridge_module.escape_for_discord("just some chat"))


class ClampTest(unittest.TestCase):
    def test_short_text_is_untouched(self):
        self.assertEqual("hello", bridge_module.clamp("hello", 10))

    def test_long_text_is_trimmed_with_marker(self):
        result = bridge_module.clamp("x" * 100, 10)
        self.assertEqual(10, len(result))
        self.assertTrue(result.endswith("\u2026"))

    def test_zero_and_one_limits_do_not_raise(self):
        self.assertEqual("", bridge_module.clamp("abc", 0))
        self.assertEqual("a", bridge_module.clamp("abc", 1))


class FormatLineTest(unittest.TestCase):
    def test_minecraft_line_has_sender_and_text(self):
        line = bridge_module.format_minecraft_line("Steve", "hello")
        self.assertEqual("**Steve** hello", line)

    def test_minecraft_line_without_sender_uses_placeholder(self):
        self.assertIn("server", bridge_module.format_minecraft_line("", "hello"))

    def test_discord_line_is_clamped_to_chat_limit(self):
        line = bridge_module.format_discord_for_minecraft("Steve", "y" * 500)
        self.assertLessEqual(len(line), bridge_module.MINECRAFT_LINE_LIMIT)

    def test_discord_line_cannot_carry_a_command(self):
        line = bridge_module.format_discord_for_minecraft("Steve", "/give @p diamond")
        self.assertNotIn("/give", line)


class ParseSseTest(unittest.TestCase):
    def _events(self, text):
        return list(bridge_module.parse_sse_lines(text.splitlines(keepends=True)))

    def test_single_event(self):
        events = self._events("event: chat\ndata: {\"a\":1}\n\n")
        self.assertEqual(1, len(events))
        self.assertEqual("chat", events[0].event)
        self.assertEqual('{"a":1}', events[0].data)

    def test_comment_keepalive_is_ignored(self):
        events = self._events(": keep-alive\n\n: connected\n\n")
        self.assertEqual([], events, "keep-alives must not surface as events")

    def test_id_is_captured(self):
        events = self._events("id: 42\nevent: chat\ndata: {}\n\n")
        self.assertEqual("42", events[0].id)

    def test_multiple_events_in_order(self):
        events = self._events("event: chat\ndata: one\n\nevent: chat\ndata: two\n\n")
        self.assertEqual(["one", "two"], [e.data for e in events])

    def test_multiline_data_is_joined(self):
        events = self._events("data: line1\ndata: line2\n\n")
        self.assertEqual("line1\nline2", events[0].data)

    def test_no_leading_space_after_colon(self):
        events = self._events("data:no-space\n\n")
        self.assertEqual("no-space", events[0].data)

    def test_incomplete_event_is_not_emitted(self):
        # No trailing blank line means the event is still arriving.
        self.assertEqual([], self._events("event: chat\ndata: {}\n"))


class FakePoster:
    def __init__(self):
        self.posted = []

    def post(self, content, username=None):
        self.posted.append(content)
        return True


class FakeCompanion:
    def __init__(self, ok=True, message="Sent."):
        self.sent = []
        self.ok = ok
        self.message = message

    def send_chat(self, text):
        self.sent.append(text)
        return self.ok, self.message


def make_bridge(poster=None, companion=None, **overrides):
    config = bridge_module.Config(
        bot_token="token",
        channel_id="chan",
        companion_token="ct",
        minecraft_to_discord=overrides.get("minecraft_to_discord", True),
        discord_to_minecraft=overrides.get("discord_to_minecraft", True),
    )
    return bridge_module.Bridge(
        config,
        companion or FakeCompanion(),
        poster,
        log=lambda _message: None,
    )


def chat_event(content, sender="Steve", server="srv", use_content_key=True):
    payload = {"sender": sender, "server": server}
    if use_content_key:
        payload["content"] = content
    else:
        payload["message"] = content
    return bridge_module.SseEvent(event="chat", data=json.dumps(payload))


class MinecraftToDiscordTest(unittest.TestCase):
    def test_chat_event_is_posted(self):
        poster = FakePoster()
        bridge = make_bridge(poster)
        bridge.handle_companion_event(chat_event("hello world"))
        self.assertEqual(["**Steve** hello world  `srv`"], poster.posted)

    def test_snapshot_key_name_is_also_accepted(self):
        # The snapshot names the text "message", the event stream names it "content".
        poster = FakePoster()
        bridge = make_bridge(poster)
        bridge.handle_companion_event(chat_event("via message key", use_content_key=False))
        self.assertEqual(1, len(poster.posted), "the 'message' key must not be dropped")

    def test_non_chat_events_are_ignored(self):
        poster = FakePoster()
        bridge = make_bridge(poster)
        bridge.handle_companion_event(bridge_module.SseEvent(event="bot", data="{}"))
        self.assertEqual([], poster.posted)

    def test_malformed_json_does_not_raise(self):
        bridge = make_bridge(FakePoster())
        bridge.handle_companion_event(bridge_module.SseEvent(event="chat", data="{not json"))
        # Reaching here without an exception is the assertion.

    def test_direction_can_be_disabled(self):
        poster = FakePoster()
        bridge = make_bridge(poster, minecraft_to_discord=False)
        bridge.handle_companion_event(chat_event("hello"))
        self.assertEqual([], poster.posted)

    def test_no_poster_is_not_an_error(self):
        bridge = make_bridge(None)
        bridge.handle_companion_event(chat_event("hello"))


class DiscordToMinecraftTest(unittest.TestCase):
    def _message(self, content="hi", author_bot=False, channel="chan", mid="1"):
        return {
            "id": mid,
            "channel_id": channel,
            "content": content,
            "author": {"username": "Alice", "bot": author_bot},
        }

    def test_message_is_injected(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("hello minecraft"))
        self.assertEqual(["hello minecraft"], companion.sent)

    def test_slash_command_is_neutralised_before_injection(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("/op Steve"))
        self.assertEqual(["op Steve"], companion.sent)
        self.assertFalse(companion.sent[0].startswith("/"),
                         "a leading slash would be executed as a command")

    def test_other_channels_are_ignored(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("hi", channel="elsewhere"))
        self.assertEqual([], companion.sent)

    def test_bot_messages_are_ignored_by_default(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("from a bot", author_bot=True))
        self.assertEqual([], companion.sent)

    def test_webhook_messages_count_as_bots(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        message = self._message("from a webhook")
        message["webhook_id"] = "999"
        bridge.handle_discord_message(message)
        self.assertEqual([], companion.sent)

    def test_prefixed_messages_are_ignored(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("!help"))
        self.assertEqual([], companion.sent)

    def test_duplicate_message_id_is_ignored(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("once", mid="42"))
        bridge.handle_discord_message(self._message("once", mid="42"))
        self.assertEqual(1, len(companion.sent), "a gateway replay must not double-send")

    def test_empty_message_is_ignored(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion)
        bridge.handle_discord_message(self._message("   "))
        self.assertEqual([], companion.sent)

    def test_direction_can_be_disabled(self):
        companion = FakeCompanion()
        bridge = make_bridge(FakePoster(), companion, discord_to_minecraft=False)
        bridge.handle_discord_message(self._message("hi"))
        self.assertEqual([], companion.sent)


class EchoLoopTest(unittest.TestCase):
    """A bridge that echoes its own injections back fills the channel forever."""

    def test_own_injection_is_not_posted_back(self):
        poster = FakePoster()
        companion = FakeCompanion()
        bridge = make_bridge(poster, companion)

        bridge.handle_discord_message({
            "id": "1", "channel_id": "chan", "content": "hello there",
            "author": {"username": "Alice"},
        })
        self.assertEqual(["hello there"], companion.sent)

        # The game now reports that line as chat, which the bridge reads.
        bridge.handle_companion_event(chat_event("hello there", sender="Alice"))
        self.assertEqual([], poster.posted, "the echo must not go back to Discord")

    def test_genuine_repeat_from_a_player_still_posts(self):
        poster = FakePoster()
        companion = FakeCompanion()
        bridge = make_bridge(poster, companion)

        bridge.handle_discord_message({
            "id": "1", "channel_id": "chan", "content": "gg",
            "author": {"username": "Alice"},
        })
        bridge.handle_companion_event(chat_event("gg", sender="Alice"))  # the echo, consumed

        # A different player saying the same thing is real chat and must be posted.
        bridge.handle_companion_event(chat_event("gg", sender="Steve"))
        self.assertEqual(1, len(poster.posted))


class ShouldIgnoreTest(unittest.TestCase):
    def test_blank_is_ignored(self):
        self.assertTrue(bridge_module.should_ignore("", False, True, ("!",)))

    def test_bot_is_ignored_when_asked(self):
        self.assertTrue(bridge_module.should_ignore("hi", True, True, ()))
        self.assertFalse(bridge_module.should_ignore("hi", True, False, ()))

    def test_prefix_is_ignored(self):
        self.assertTrue(bridge_module.should_ignore("!ping", False, True, ("!",)))
        self.assertTrue(bridge_module.should_ignore("  !ping", False, True, ("!",)),
                        "leading whitespace must not bypass the prefix check")

    def test_normal_text_passes(self):
        self.assertFalse(bridge_module.should_ignore("hello", False, True, ("!",)))


class SeenIdsTest(unittest.TestCase):
    def test_add_reports_novelty(self):
        seen = bridge_module.SeenIds(size=3)
        self.assertTrue(seen.add("a"))
        self.assertFalse(seen.add("a"))

    def test_old_ids_are_evicted(self):
        seen = bridge_module.SeenIds(size=2)
        seen.add("a")
        seen.add("b")
        seen.add("c")
        self.assertTrue(seen.add("a"), "an evicted id is treated as new again")


class EchoSuppressorTest(unittest.TestCase):
    def test_remembered_text_is_an_echo_once(self):
        suppressor = bridge_module.EchoSuppressor()
        suppressor.remember("hello")
        self.assertTrue(suppressor.is_echo("hello"))
        self.assertFalse(suppressor.is_echo("hello"), "one injection, one echo")

    def test_unknown_text_is_not_an_echo(self):
        self.assertFalse(bridge_module.EchoSuppressor().is_echo("never seen"))


class ConfigValidationTest(unittest.TestCase):
    def _config(self, **overrides):
        base = dict(bot_token="t", channel_id="c", companion_token="k",
                    transport="gateway", ingress="gateway")
        base.update(overrides)
        return bridge_module.Config(**base)

    def test_valid_config_passes(self):
        self._config().validate()

    def test_missing_channel_fails(self):
        with self.assertRaises(bridge_module.BridgeError):
            self._config(channel_id="").validate()

    def test_missing_companion_token_fails(self):
        with self.assertRaises(bridge_module.BridgeError):
            self._config(companion_token="").validate()

    def test_gateway_ingress_needs_a_token(self):
        with self.assertRaises(bridge_module.BridgeError):
            self._config(bot_token="").validate()

    def test_poll_ingress_also_needs_a_token(self):
        # Polling reads the same bot API, so a missing token is a startup error either way.
        with self.assertRaises(bridge_module.BridgeError):
            self._config(bot_token="", ingress="poll").validate()

    def test_no_token_needed_when_discord_to_minecraft_is_off(self):
        self._config(bot_token="", discord_to_minecraft=False).validate()

    def test_webhook_transport_needs_a_url(self):
        with self.assertRaises(bridge_module.BridgeError):
            self._config(transport="webhook", webhook_url="").validate()

    def test_non_discord_webhook_is_refused(self):
        # Posting captured chat to an arbitrary host would leak it.
        with self.assertRaises(bridge_module.BridgeError):
            self._config(transport="webhook",
                         webhook_url="https://example.com/collect").validate()

    def test_both_directions_disabled_fails(self):
        with self.assertRaises(bridge_module.BridgeError):
            self._config(minecraft_to_discord=False, discord_to_minecraft=False).validate()


class BuildTest(unittest.TestCase):
    def test_gateway_transport_gets_a_bot_poster(self):
        config = bridge_module.Config(bot_token="t", channel_id="c", companion_token="k")
        bridge, gateway = bridge_module.build(config, log=lambda _m: None)
        self.assertIsNotNone(bridge.poster,
                             "without a poster, Minecraft -> Discord silently does nothing")
        self.assertIsInstance(bridge.poster, bridge_module.DiscordBotPoster)
        self.assertIsNotNone(gateway)

    def test_webhook_transport_gets_a_webhook_poster(self):
        config = bridge_module.Config(
            bot_token="t", channel_id="c", companion_token="k", transport="webhook",
            webhook_url="https://discord.com/api/webhooks/1/abc")
        bridge, _ = bridge_module.build(config, log=lambda _m: None)
        self.assertIsInstance(bridge.poster, bridge_module.DiscordPoster)

    def test_poll_ingress_has_no_gateway(self):
        config = bridge_module.Config(bot_token="t", channel_id="c", companion_token="k",
                                      ingress="poll")
        _, gateway = bridge_module.build(config, log=lambda _m: None)
        self.assertIsNone(gateway)


if __name__ == "__main__":
    unittest.main(verbosity=2)
