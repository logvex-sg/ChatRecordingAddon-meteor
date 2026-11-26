"""Tests for the dataset generator.

The generator reads Java source with regexes and a hand-written bracket scanner, which is exactly
the kind of code that fails quietly: a bad pattern yields fewer records instead of an error. These
tests pin the counts and the shapes so a regression shows up as a failure.

Run with:  python3 -m unittest discover -s tools/tests -v
"""

import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_MODULE_PATH = os.path.join(os.path.dirname(_HERE), "build_datasets.py")
_spec = importlib.util.spec_from_file_location("build_datasets", _MODULE_PATH)
bd = importlib.util.module_from_spec(_spec)
sys.modules["build_datasets"] = bd
_spec.loader.exec_module(bd)


class ParseModulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.modules = bd.parse_modules()
        cls.by_name = {m.name: m for m in cls.modules}

    def test_finds_every_concrete_module(self):
        # 22 concrete modules plus one abstract base (AutomationModule) that must be excluded.
        self.assertEqual(22, len(self.modules))

    def test_abstract_base_class_is_excluded(self):
        self.assertNotIn("AutomationModule", [m.class_name for m in self.modules])

    def test_every_module_has_a_name_and_description(self):
        for module in self.modules:
            with self.subTest(module=module.name):
                self.assertTrue(module.name)
                self.assertTrue(module.description,
                                f"{module.name} has no description; the super() call was missed")
                self.assertTrue(module.category,
                                f"{module.name} has no category; the constant was not resolved")

    def test_category_constant_is_resolved(self):
        # Modules pass LogClient.CATEGORY, not a string literal.
        for module in self.modules:
            self.assertNotIn("CATEGORY", module.category)
        self.assertEqual("Log Client", self.modules[0].category)

    def test_known_module_is_parsed(self):
        relay = self.by_name["webhook-relay"]
        self.assertIn("webhook", relay.description.lower())
        self.assertGreaterEqual(len(relay.settings), 15)

    def test_module_with_comments_before_super_is_parsed(self):
        # ChatTimestampsModule has a comment before its super() call. A search that mixed raw and
        # comment-stripped offsets lost it entirely.
        timestamps = self.by_name["chat-timestamps"]
        self.assertTrue(timestamps.description)
        self.assertGreaterEqual(len(timestamps.settings), 2)


class ParseSettingsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.modules = bd.parse_modules()
        cls.settings = [s for m in cls.modules for s in m.settings]

    def test_settings_are_found(self):
        self.assertGreater(len(self.settings), 180)

    def test_nested_generic_types_are_parsed(self):
        # Setting<List<String>> used to be dropped by a regex that stopped at the first '>'.
        list_settings = [s for s in self.settings if s.type.startswith("List<")]
        self.assertTrue(list_settings, "List<String> settings were not parsed")

    def test_every_setting_has_a_name_and_description(self):
        for setting in self.settings:
            with self.subTest(setting=f"{setting.module}.{setting.name}"):
                self.assertTrue(setting.name)
                self.assertTrue(setting.description)

    def test_no_raw_java_in_defaults(self):
        for setting in self.settings:
            with self.subTest(setting=f"{setting.module}.{setting.name}"):
                for artifact in (".stream()", "::", "->", "Builder", "defaultValue"):
                    self.assertNotIn(artifact, setting.default)

    def test_boolean_default_is_readable(self):
        values = {s.default for s in self.settings if s.type == "Boolean"}
        self.assertTrue(values <= {"true", "false"}, f"unexpected boolean defaults: {values}")

    def test_map_of_settings_is_not_a_setting(self):
        # NotificationModule has Map<NotificationType, Setting<Boolean>>, not a user setting.
        names = {s.name for s in self.settings if s.module == "notifications"}
        self.assertNotIn("toggle", names)


class ParseCommandsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.commands = bd.parse_commands()

    def test_commands_are_found(self):
        self.assertGreaterEqual(len(self.commands), 30)

    def test_every_command_has_usage_and_description(self):
        for command in self.commands:
            with self.subTest(usage=command.usage):
                self.assertTrue(command.usage)
                self.assertTrue(command.description)
                self.assertNotIn('"', command.usage)

    def test_known_command_is_parsed(self):
        usages = {c.usage for c in self.commands}
        self.assertIn("log search [terms]", usages)


class ParseEndpointsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.endpoints = bd.parse_endpoints()

    def test_endpoints_are_found(self):
        self.assertGreaterEqual(len(self.endpoints), 15)

    def test_every_endpoint_has_a_description(self):
        # An undescribed route is noise in a training set, so they are dropped, not kept empty.
        for endpoint in self.endpoints:
            self.assertTrue(endpoint.description, f"{endpoint.method} {endpoint.path}")

    def test_chat_endpoint_is_present(self):
        paths = {(e.method, e.path) for e in self.endpoints}
        self.assertIn(("POST", "/api/v1/chat"), paths)


class DatasetShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.modules = bd.parse_modules()
        cls.commands = bd.parse_commands()
        cls.endpoints = bd.parse_endpoints()
        cls.dictionary = bd.build_dictionary(cls.modules, cls.commands, cls.endpoints)
        cls.qa = bd.build_qa(cls.modules, cls.commands, cls.endpoints)
        cls.conversations = bd.build_conversations(cls.modules, cls.commands, cls.endpoints)

    def test_dictionary_ids_are_unique(self):
        ids = [record["id"] for record in self.dictionary]
        self.assertEqual(len(ids), len(set(ids)))

    def test_every_dictionary_record_has_a_definition(self):
        for record in self.dictionary:
            with self.subTest(id=record["id"]):
                self.assertTrue(record["definition"])
                self.assertTrue(record["term"])

    def test_qa_pairs_are_user_then_assistant(self):
        for record in self.qa:
            self.assertEqual(["user", "assistant"],
                             [m["role"] for m in record["messages"]])
            self.assertTrue(record["messages"][0]["content"].strip())
            self.assertTrue(record["messages"][1]["content"].strip())

    def test_qa_questions_are_unique(self):
        questions = [r["messages"][0]["content"] for r in self.qa]
        duplicates = {q for q in questions if questions.count(q) > 1}
        self.assertEqual(set(), duplicates)

    def test_conversations_alternate_strictly(self):
        for record in self.conversations:
            roles = [m["role"] for m in record["messages"]]
            self.assertEqual(0, len(roles) % 2, record["title"])
            self.assertEqual(["user", "assistant"] * (len(roles) // 2), roles, record["title"])

    def test_conversations_are_multi_turn(self):
        for record in self.conversations:
            self.assertGreaterEqual(len(record["messages"]), 4, record["title"])

    def test_all_datasets_serialise(self):
        for dataset in (self.dictionary, self.qa, self.conversations):
            for record in dataset:
                json.dumps(record, ensure_ascii=False)


def run_quietly(directory):
    """Run the generator without letting its report reach the test output."""
    with contextlib.redirect_stdout(io.StringIO()):
        return bd.main(["--out", directory])


class OutputTest(unittest.TestCase):
    def test_generator_writes_all_files(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(0, run_quietly(directory))
            for filename in ("dictionary.jsonl", "qa.jsonl", "conversations.jsonl",
                             "dictionary.md"):
                path = os.path.join(directory, filename)
                self.assertTrue(os.path.exists(path), filename)
                self.assertGreater(os.path.getsize(path), 1000, filename)

    def test_written_jsonl_is_parseable(self):
        with tempfile.TemporaryDirectory() as directory:
            run_quietly(directory)
            for filename in ("dictionary.jsonl", "qa.jsonl", "conversations.jsonl"):
                with open(os.path.join(directory, filename), encoding="utf-8") as handle:
                    for line in handle:
                        json.loads(line)


if __name__ == "__main__":
    unittest.main(verbosity=2)
