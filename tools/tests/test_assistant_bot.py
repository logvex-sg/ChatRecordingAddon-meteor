"""Tests for the assistant bot and its usage counting.

The telemetry tests are adversarial on purpose. A privacy claim that is only checked by reading
the code is not checked at all, so these feed it the exact things a user would worry about —
their name, a server address, a path, a chat line — and assert none of it survives into the
stored payload.

Run with:  python3 -m unittest discover -s tools/tests -v
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_MODULE_PATH = os.path.join(os.path.dirname(_HERE), "assistant_bot.py")
_spec = importlib.util.spec_from_file_location("assistant_bot", _MODULE_PATH)
ab = importlib.util.module_from_spec(_spec)
sys.modules["assistant_bot"] = ab
_spec.loader.exec_module(ab)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def read_json(path):
    return json.loads(read(path))


def make_assistant():
    return ab.make_assistant()


class TokenizeTest(unittest.TestCase):
    def test_lowercases(self):
        self.assertEqual(["chat", "logger"], ab.tokenize("Chat Logger"))

    def test_keeps_compound_names_whole(self):
        self.assertIn("chat-logger", ab.tokenize("chat-logger"))

    def test_splits_compounds_for_indexing(self):
        # People type "chat logger", the source says "chat-logger"; both must match.
        tokens = ab.index_tokens("chat-logger")
        self.assertIn("chat-logger", tokens)
        self.assertIn("chat", tokens)
        self.assertIn("logger", tokens)

    def test_stopwords_are_dropped(self):
        self.assertEqual([], ab.content_tokens("what is the a an of"))

    def test_stemming_collapses_plurals(self):
        self.assertEqual(ab.stem("alerts"), ab.stem("alert"))
        self.assertEqual(ab.stem("recording"), ab.stem("record"))


class RetrievalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assistant = make_assistant()

    def ask(self, question):
        return self.assistant.answer(question)

    def test_module_question_finds_the_module(self):
        answer = self.ask("what does the chat logger do")
        self.assertTrue(answer.found)
        self.assertEqual("chat-logger", answer.record_id.split(":")[1])

    def test_module_question_is_not_hijacked_by_a_setting(self):
        # "chat logger" matches every chat-logger.* setting too; the module should win.
        answer = self.ask("tell me about keyword alerts")
        self.assertEqual("module", answer.kind)

    def test_setting_question_finds_the_setting(self):
        answer = self.ask("what does the webhook url setting do")
        self.assertTrue(answer.found)
        self.assertIn("webhook-url", answer.record_id)

    def test_command_question_finds_the_command(self):
        answer = self.ask("how do I search old chat")
        self.assertTrue(answer.found)
        self.assertEqual("command", answer.kind)

    def test_bridge_question_finds_the_bridge(self):
        answer = self.ask("how do I send chat to discord")
        self.assertEqual("concept:discord-bridge", answer.record_id)

    def test_unknown_question_is_refused(self):
        # Guessing here would be worse than admitting ignorance: the whole point is that it
        # cannot invent a setting.
        answer = self.ask("what is the best pvp kit for skywars")
        self.assertFalse(answer.found)
        self.assertIn("do not have", answer.text)

    def test_empty_question_is_refused(self):
        self.assertFalse(self.ask("").found)
        self.assertFalse(self.ask("   ").found)

    def test_answer_cites_its_source(self):
        answer = self.ask("what does the chat logger do")
        self.assertTrue(answer.source.startswith("src/") or answer.source.startswith("tools/"))
        self.assertIn(answer.source, answer.text)

    def test_setting_answer_points_at_neighbours(self):
        answer = self.ask("what does the webhook url setting do")
        self.assertIn("Related in this module", answer.text)


class EvalSetTest(unittest.TestCase):
    """A fixed set of questions with expected answers.

    Retrieval quality is easy to break while fixing one case, so this measures the whole set
    rather than testing single queries. Two numbers are reported: strict, where the exact record
    must be returned, and lenient, where a setting inside the expected module also counts. A
    setting inside the right module is a defensible answer to "how do I drop a schematic".
    """

    # (question, expected record id, or None for "must refuse")
    CASES = [
        ("what does the chat logger do", "module:chat-logger"),
        ("tell me about keyword alerts", "module:keyword-alerts"),
        ("how do I search old chat", "command"),
        ("how do I send chat to discord", "concept:discord-bridge"),
        ("webhook-relay", "module:webhook-relay"),
        ("what does the webhook url setting do", "setting"),
        ("chat-logger.recording", "setting:chat-logger.recording"),
        ("what does the chat filter do", "module:chat-filter"),
        ("show me the timeline", "module:session-timeline"),
        ("how do I export my logs", "command"),
        ("can I get alerts when someone says my name", "module:mention-alerts"),
        ("what is the companion token", "concept:companion-token"),
        ("what is the slash rule", "concept:slash-rule"),
        ("how many settings does auto-bot have", "module:auto-bot"),
        ("what does player notes do", "module:player-notes"),
        ("how do I drop a schematic", "module:schematic-dropper"),
        ("what are session stats", "module:session-stats"),
        ("how do I stop repeat spam", "module:repeat-collapse"),
        # Realistic questions carrying a player name or hostname must still be answered: the
        # identifiers are data, not unknown vocabulary.
        ("do I get webhook alerts", "setting"),
        ("does steve from play.example.com get webhook alerts", "setting"),
        # Out of scope. Answering these from a keyword would be confabulation.
        ("what is my server ip 1.2.3.4", None),
        ("whats the weather", None),
        ("best pvp kit skywars", None),
        ("how do i install optifine", None),
        ("who won the world cup", None),
        ("how do i make a sword", None),
        ("tell me a joke", None),
        ("does it leak my chat", None),
    ]

    @classmethod
    def setUpClass(cls):
        cls.assistant = make_assistant()

    def _strict(self, expected, answer):
        if expected is None:
            return answer.record_id is None
        if answer.record_id is None:
            return False
        if expected in ("command", "setting", "module", "concept"):
            return answer.kind == expected
        return answer.record_id == expected

    def _lenient(self, expected, answer):
        if self._strict(expected, answer):
            return True
        if expected is None or answer.record_id is None or ":" not in expected:
            return False
        kind, name = expected.split(":", 1)
        if kind == "module" and answer.kind == "setting":
            return answer.record_id.split(":")[1].startswith(name + ".")
        return False

    def test_strict_accuracy(self):
        hits = sum(self._strict(exp, self.assistant.answer(q)) for q, exp in self.CASES)
        ratio = hits / len(self.CASES)
        self.assertGreaterEqual(ratio, 0.84, f"strict accuracy fell to {ratio:.0%}")

    def test_lenient_accuracy(self):
        hits = sum(self._lenient(exp, self.assistant.answer(q)) for q, exp in self.CASES)
        ratio = hits / len(self.CASES)
        self.assertGreaterEqual(ratio, 0.92, f"lenient accuracy fell to {ratio:.0%}")

    def test_every_out_of_scope_question_is_refused(self):
        # This is the guarantee that matters most: the bot must not answer from a stray keyword.
        for question, expected in self.CASES:
            if expected is not None:
                continue
            with self.subTest(question=question):
                answer = self.assistant.answer(question)
                self.assertIsNone(answer.record_id,
                                  f"answered {answer.record_id!r} instead of refusing")


class IndexTest(unittest.TestCase):
    def test_missing_dataset_is_reported(self):
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(SystemExit):
                ab.load_documents(empty)

    def test_documents_load(self):
        documents = ab.load_documents()
        self.assertGreater(len(documents), 250)
        for document in documents:
            self.assertTrue(document.record_id)
            self.assertTrue(document.term)


class TelemetryOffByDefaultTest(unittest.TestCase):
    def test_off_unless_opted_in(self):
        with tempfile.TemporaryDirectory() as directory:
            usage = ab.UsageCounter(os.path.join(directory, "usage.json"))
            self.assertFalse(usage.opt_in)

    def test_nothing_is_recorded_when_off(self):
        with tempfile.TemporaryDirectory() as directory:
            usage = ab.UsageCounter(os.path.join(directory, "usage.json"))
            answer = ab.Answer(text="x", record_id="module:chat-logger", score=5.0, source="s")
            usage.record("what does the chat logger do", answer, ["chat-logger"])
            self.assertEqual({}, dict(usage.answered))
            self.assertEqual(0, usage.unanswered)

    def test_nothing_is_written_when_off(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "usage.json")
            usage = ab.UsageCounter(path)
            usage.record("hi", ab.Answer("x", None, 0.0, ""), [])
            usage.save()
            saved = read_json(path)
            self.assertFalse(saved["opt_in"])
            self.assertEqual({}, saved["answered"])


class TelemetryPrivacyTest(unittest.TestCase):
    """The claim is that question text never reaches disk. These try to break that."""

    SECRETS = [
        "my name is Steve and my server is play.example.com:25565",
        "/home/alice/.minecraft/config/sevennv/profiles.json",
        "the password is hunter2",
        "my ip is 192.168.1.42",
        "tell Bob that I am at the base near spawn",
        "chat-logger",  # a real module name, to prove the positive case still counts
    ]

    def _opt_in_usage(self, directory):
        usage = ab.UsageCounter(os.path.join(directory, "usage.json"))
        usage.opt_in = True
        return usage

    def test_question_text_never_reaches_the_file(self):
        needles = ("steve", "example.com", "25565", "alice", "hunter2",
                   "192.168", "bob", "spawn", "password")
        with tempfile.TemporaryDirectory() as directory:
            for secret in self.SECRETS:
                usage = self._opt_in_usage(directory)
                # Unanswered on purpose: that is the path where a naive implementation would
                # log the question in order to improve coverage.
                usage.record(secret, ab.Answer("no", None, 0.0, ""), ["chat-logger"])
                usage.save()
                raw = read(usage.path).lower()
                for needle in needles:
                    if needle in secret.lower():
                        self.assertNotIn(needle, raw,
                                         f"'{needle}' from {secret!r} leaked into the payload")

    def test_no_identifiers_are_stored(self):
        with tempfile.TemporaryDirectory() as directory:
            usage = self._opt_in_usage(directory)
            usage.record("hi", ab.Answer("x", "module:chat-logger", 5.0, "s"), [])
            payload = usage.preview()
            self.assertEqual(
                {"schema", "day", "dataset_records", "answered", "unanswered",
                 "unanswered_modules"},
                set(payload))

    def test_unanswered_counts_only_public_module_names(self):
        with tempfile.TemporaryDirectory() as directory:
            usage = self._opt_in_usage(directory)
            usage.record("my friend Steve asked about chat-logger",
                         ab.Answer("no", None, 0.0, ""), ["chat-logger", "webhook-relay"])
            self.assertEqual({"chat-logger": 1}, dict(usage.unanswered_modules))
            self.assertNotIn("Steve", json.dumps(usage.preview()))

    def test_answered_records_are_public_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            usage = self._opt_in_usage(directory)
            answer = ab.Answer("x", "module:chat-logger", 5.0, "s")
            usage.record("what does chat-logger do", answer, [])
            for record_id in usage.answered:
                self.assertTrue(
                    record_id.startswith(("module:", "setting:", "command:", "concept:",
                                          "endpoint:")),
                    f"{record_id} does not look like a public dataset id")

    def test_preview_matches_what_is_written(self):
        with tempfile.TemporaryDirectory() as directory:
            usage = self._opt_in_usage(directory)
            usage.record("hi", ab.Answer("x", "module:chat-logger", 5.0, "s"), [])
            preview = usage.preview(dataset_records=296)
            usage.save()
            saved = read_json(usage.path)
            # The file may omit the display-only fields, but must never contain more than the
            # preview showed.
            for key in saved:
                if key in preview:
                    self.assertEqual(preview[key], saved[key], key)
            self.assertLessEqual(set(saved), set(preview) | {"opt_in"})

    def test_opt_out_erases_everything(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "usage.json")
            usage = ab.UsageCounter(path)
            usage.opt_in = True
            usage.record("hi", ab.Answer("x", "module:chat-logger", 5.0, "s"), ["chat-logger"])
            usage.record("unknown thing", ab.Answer("no", None, 0.0, ""), ["chat-logger"])
            usage.save()

            reloaded = ab.UsageCounter(path)
            self.assertTrue(reloaded.answered)
            reloaded.opt_in = False
            reloaded.answered.clear()
            reloaded.unanswered = 0
            reloaded.unanswered_modules.clear()
            reloaded.save()

            saved = read_json(path)
            self.assertEqual({}, saved["answered"])
            self.assertEqual(0, saved["unanswered"])
            self.assertEqual({}, saved["unanswered_modules"])
            self.assertFalse(saved["opt_in"])


class CliTest(unittest.TestCase):
    def test_ask_returns_zero_when_answered(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as directory:
            with contextlib.redirect_stdout(io.StringIO()):
                code = ab.main(["--ask", "what does the chat logger do",
                                "--state", os.path.join(directory, "u.json")])
            self.assertEqual(0, code)

    def test_ask_returns_nonzero_when_unknown(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as directory:
            with contextlib.redirect_stdout(io.StringIO()):
                code = ab.main(["--ask", "zzzz qqqq wwww",
                                "--state", os.path.join(directory, "u.json")])
            self.assertEqual(1, code)

    def test_preview_shows_payload_without_opt_in(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as directory:
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                code = ab.main(["--preview-telemetry",
                                "--state", os.path.join(directory, "u.json")])
            self.assertEqual(0, code)
            payload = json.loads(buffer.getvalue())
            self.assertEqual(ab.TELEMETRY_SCHEMA, payload["schema"])
            self.assertEqual({}, payload["answered"])

    def test_opt_in_then_out_leaves_clean_file(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as directory:
            state = os.path.join(directory, "u.json")
            with contextlib.redirect_stdout(io.StringIO()):
                ab.main(["--opt-in", "--state", state])
                ab.main(["--ask", "what does the chat logger do", "--state", state])
                ab.main(["--opt-out", "--state", state])
            saved = read_json(state)
            self.assertFalse(saved["opt_in"])
            self.assertEqual({}, saved["answered"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
