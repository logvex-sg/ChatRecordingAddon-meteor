#!/usr/bin/env python3
"""Tests for the companion app's form state across its periodic refresh.

The app rebuilds every view from scratch every three seconds. Before this was covered, that rebuild
silently discarded user input: text typed in one box vanished when focus moved to another, the two
dropdowns on the Players tab shared an identity so restoring one overwrote the other, and the Live
feed's Pause button reset itself on every tick.

These tests load the real ``companion/app.html`` in jsdom, stub only the network, and drive the real
``refreshAll()``. Nothing about the app's own logic is faked. jsdom is not a project dependency, so
the suite skips when Node or jsdom is absent rather than failing on a machine without them.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src" / "main" / "resources" / "companion" / "app.html"

NODE = shutil.which("node")


def _jsdom_available() -> bool:
    if not NODE:
        return False
    probe = subprocess.run(
        [NODE, "-e", "require('jsdom')"],
        capture_output=True,
        text=True,
        env=_node_env(),
    )
    return probe.returncode == 0


def _node_env() -> dict:
    import os

    env = dict(os.environ)
    # A jsdom installed anywhere on the machine is enough; the harness asks Node to resolve it.
    env.setdefault("NODE_PATH", "/usr/local/lib/node_modules:/usr/lib/node_modules")
    return env


HARNESS = r"""
const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');

const HTML = fs.readFileSync(process.argv[2], 'utf8');
const SNAPSHOT = {
  node: { addonVersion: '18.23.0', inWorld: true, uptimeMs: 1000 },
  stats: { players: 1, messages: 1, joins: 0, leaves: 0, deaths: 0 },
  bot: { state: 'IDLE', completed: 0, failed: 0, queued: 0, recentEvents: [] },
  storage: { bytes: 10, segments: 1, autoCleanup: false, capBytes: 0 },
  chat: [{ timestamp: Date.now(), type: 'CHAT', sender: 'Steve', message: 'hi' }],
  players: [{ name: 'Steve', online: true, ping: 5, messages: 1, lastSeen: Date.now() }],
  modules: [{ name: 'M', active: true }],
  tasks: []
};

const errors = [];
const console_ = new VirtualConsole();
console_.on('jsdomError', e => errors.push(e.message));

const dom = new JSDOM(HTML, {
  url: 'http://127.0.0.1:6767/?token=tok',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: console_,
  beforeParse(window) {
    window.fetch = async url => ({
      ok: true,
      status: 200,
      text: async () => JSON.stringify(String(url).includes('/snapshot') ? SNAPSHOT : { ok: true })
    });
    window.EventSource = class { close() {} };
  }
});

const w = dom.window;
const d = w.document;
const wait = ms => new Promise(r => setTimeout(r, ms));
const tab = name => [...d.querySelectorAll('#tabs button')].find(b => b.textContent.startsWith(name)).click();
const results = {};
const check = (name, condition, detail) => { results[name] = { pass: !!condition, detail }; };

(async () => {
  await wait(250);

  tab('Players');
  await wait(120);
  let selects = [...d.querySelectorAll('#view-players select')];
  const clientId = selects[0].value;
  selects[1].focus();
  selects[1].value = 'messages';
  selects[1].dispatchEvent(new w.Event('change', { bubbles: true }));
  await w.refreshAll();
  await wait(120);
  selects = [...d.querySelectorAll('#view-players select')];
  check('client-dropdown-survives', selects[0].value === clientId, 'node=' + selects[0].value);
  check('sort-dropdown-survives', selects[1].value === 'messages', 'sort=' + selects[1].value);

  tab('Live');
  await wait(120);
  let pause = [...d.querySelectorAll('#view-events button')].find(b => b.textContent === 'Pause');
  pause.click();
  await wait(120);
  await w.refreshAll();
  await wait(120);
  pause = [...d.querySelectorAll('#view-events button')].find(b => /Pause|Resume/.test(b.textContent));
  check('pause-survives-refresh', pause.textContent === 'Resume', 'reads ' + pause.textContent);

  tab('Bot');
  await wait(120);
  let inputs = [...d.querySelectorAll('#view-bot input')];
  inputs[0].value = 'mine oak_log 64';
  inputs[2].focus();
  inputs[2].value = '/tpa Steve';
  await w.refreshAll();
  await wait(120);
  inputs = [...d.querySelectorAll('#view-bot input')];
  check('unfocused-text-survives', inputs[0].value === 'mine oak_log 64', JSON.stringify(inputs[0].value));
  check('focused-text-survives', inputs[2].value === '/tpa Steve', JSON.stringify(inputs[2].value));

  tab('Chat');
  await wait(120);
  let filter = d.querySelector('#view-chat input');
  filter.focus();
  filter.value = 'zzz-nothing';
  filter.dispatchEvent(new w.Event('input', { bubbles: true }));
  await wait(80);
  const empties = d.querySelectorAll('#view-chat .empty').length;
  await w.refreshAll();
  await wait(120);
  filter = d.querySelector('#view-chat input');
  check('chat-filter-text-survives', filter.value === 'zzz-nothing', JSON.stringify(filter.value));
  check('chat-filter-still-applied', d.querySelectorAll('#view-chat .empty').length === empties);

  process.stdout.write(JSON.stringify({ results, errors }));
  process.exit(0);
})();
"""


@unittest.skipUnless(NODE, "node is not installed")
@unittest.skipUnless(_jsdom_available(), "jsdom is not installed (npm install jsdom)")
class CompanionAppFieldStateTest(unittest.TestCase):
    """The companion app must not lose what the user typed when its view rebuilds."""

    @classmethod
    def setUpClass(cls):
        harness = ROOT / "tools" / "tests" / "_app_field_harness.js"
        harness.write_text(HARNESS, encoding="utf-8")
        try:
            proc = subprocess.run(
                [NODE, str(harness), str(APP)],
                capture_output=True,
                text=True,
                timeout=120,
                env=_node_env(),
            )
        finally:
            harness.unlink(missing_ok=True)
        if proc.returncode != 0:
            raise AssertionError(f"harness failed:\n{proc.stdout}\n{proc.stderr}")
        cls.report = json.loads(proc.stdout)

    def test_no_javascript_errors(self):
        self.assertEqual([], self.report["errors"])

    def test_client_dropdown_keeps_its_selection(self):
        self._passes("client-dropdown-survives")

    def test_sort_dropdown_keeps_its_choice(self):
        self._passes("sort-dropdown-survives")

    def test_pause_survives_the_refresh(self):
        self._passes("pause-survives-refresh")

    def test_text_in_an_unfocused_box_survives(self):
        self._passes("unfocused-text-survives")

    def test_text_in_the_focused_box_survives(self):
        self._passes("focused-text-survives")

    def test_chat_filter_text_survives(self):
        self._passes("chat-filter-text-survives")

    def test_chat_filter_is_still_applied(self):
        self._passes("chat-filter-still-applied")

    def _passes(self, name: str) -> None:
        entry = self.report["results"].get(name)
        self.assertIsNotNone(entry, f"the harness did not report {name}")
        self.assertTrue(entry["pass"], f"{name} failed ({entry.get('detail', '')})")


if __name__ == "__main__":
    unittest.main()
