"""The Discord bridge must point at the port the companion actually listens on.

These two defaults live in different languages, so nothing catches a drift between them until a
user runs the bridge and it cannot connect. This test reads both sources directly.
"""

import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "src/main/java/com/logvex/addon/companion")
BRIDGE = os.path.join(ROOT, "tools/discord_bridge.py")
DOCS = os.path.join(ROOT, "docs/discord-bridge.md")


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def companion_port():
    match = re.search(r"private int port = (\d+);", read(os.path.join(JAVA, "core/CompanionConfig.java")))
    assert match, "could not find the companion default port"
    return int(match.group(1))


def companion_setting_port():
    text = read(os.path.join(JAVA, "mc/CompanionModule.java"))
    match = re.search(r'name\("port"\)[\s\S]{0,200}?defaultValue\((\d+)\)', text)
    assert match, "could not find the companion port setting default"
    return int(match.group(1))


class BridgeConfigTest(unittest.TestCase):
    def test_bridge_default_matches_companion_port(self):
        port = companion_port()
        text = read(BRIDGE)
        found = set(re.findall(r"127\.0\.0\.1:(\d+)", text))
        self.assertTrue(found, "the bridge declares no default companion URL")
        self.assertEqual(found, {str(port)},
                         f"bridge defaults to {found} but the companion listens on {port}")

    def test_module_setting_matches_the_config_default(self):
        self.assertEqual(companion_port(), companion_setting_port(),
                         "the module's port setting and the config default disagree")

    def test_docs_use_the_real_port(self):
        port = str(companion_port())
        text = read(DOCS)
        for found in set(re.findall(r"127\.0\.0\.1:(\d+)", text)):
            self.assertEqual(found, port, f"docs mention port {found}, companion uses {port}")


if __name__ == "__main__":
    unittest.main()
