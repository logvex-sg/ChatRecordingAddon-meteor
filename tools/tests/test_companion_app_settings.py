#!/usr/bin/env python3
"""Tests for the companion app's module-settings editor and log search.

The editor is the part of the app that can change a client's configuration, so the behaviour that
matters is not only that the controls appear but that each one sends the right request and that a
refused write puts the control back. These tests load the real ``companion/app.html`` in jsdom, stub
only the network, and drive the real functions. Nothing about the app's own logic is faked.

jsdom is not a project dependency, so the suite skips when Node or jsdom is absent.
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


def _node_env() -> dict:
    import os

    env = dict(os.environ)
    env.setdefault("NODE_PATH", "/usr/local/lib/node_modules:/usr/lib/node_modules")
    return env


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


HARNESS = r"""
const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');

const HTML = fs.readFileSync(process.argv[2], 'utf8');

const SETTINGS = [
  { name: 'log-chat', title: 'Log chat', description: 'Whether chat is logged.', group: 'General',
    type: 'bool', value: true, defaultValue: true, visible: true },
  { name: 'max-lines', title: 'Max lines', description: 'How many to keep.', group: 'General',
    type: 'int', value: 8, defaultValue: 8, min: 1, max: 64, visible: true },
  { name: 'speed', title: 'Speed', description: 'How fast.', group: 'General',
    type: 'double', value: 1.5, defaultValue: 1.5, min: 0, max: 10, visible: true },
  { name: 'mode', title: 'Mode', description: 'Which mode.', group: 'Advanced',
    type: 'enum', value: 'FAST', defaultValue: 'FAST', options: ['FAST', 'SLOW'], visible: true },
  { name: 'label', title: 'Label', description: 'A label.', group: 'Advanced',
    type: 'string', value: 'hello', defaultValue: 'hello', visible: true },
  { name: 'names', title: 'Names', description: 'Names.', group: 'Advanced',
    type: 'string-list', value: ['a', 'b'], defaultValue: ['a', 'b'], visible: true },
  { name: 'gated', title: 'Gated', description: 'Hidden for now.', group: 'Advanced',
    type: 'int', value: 5, defaultValue: 5, min: 0, max: 10, visible: false }
];

const SNAPSHOT = {
  node: { addonVersion: '18.24.0', inWorld: true, uptimeMs: 1000 },
  stats: { players: 1, messages: 1, joins: 0, leaves: 0, deaths: 0 },
  bot: { state: 'IDLE', completed: 0, failed: 0, queued: 0, recentEvents: [] },
  storage: { bytes: 10, segments: 1, autoCleanup: false, capBytes: 0 },
  chat: [{ timestamp: Date.now(), type: 'CHAT', sender: 'Steve', message: 'hi' }],
  players: [{ name: 'Steve', online: true, ping: 5, messages: 1, lastSeen: Date.now() }],
  modules: [{ name: 'chat-logger', title: 'Chat Logger', category: 'Chat', description: 'Logs chat.', active: true }],
  tasks: []
};

const errors = [];
const requests = [];
const console_ = new VirtualConsole();
console_.on('jsdomError', e => errors.push(e.message));

// Writes are refused for one setting name, so a test can prove a refusal puts the control back.
const REFUSED = 'max-lines';

const dom = new JSDOM(HTML, {
  url: 'http://127.0.0.1:6767/?token=tok',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: console_,
  beforeParse(window) {
    // jsdom has the <dialog> element but not its modal methods. The app already relies on showModal
    // for the Clients dialog, so the gap is the test environment's, not the app's.
    if (window.HTMLDialogElement) {
      window.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
      window.HTMLDialogElement.prototype.show = function () { this.setAttribute('open', ''); };
      window.HTMLDialogElement.prototype.close = function () {
        this.removeAttribute('open');
        this.dispatchEvent(new window.Event('close'));
      };
    }
    window.fetch = async (url, init = {}) => {
      const text = String(url);
      const method = (init.method || 'GET').toUpperCase();
      let body = null;
      try { body = init.body ? JSON.parse(init.body) : null; } catch { /* not json */ }
      requests.push({ url: text, method, body });
      const reply = obj => ({ ok: true, status: 200, text: async () => JSON.stringify(obj) });

      if (text.includes('/modules/') && text.includes('/settings')) {
        if (method === 'GET') return reply({ ok: true, module: 'chat-logger', settings: SETTINGS });
        if (body && body.setting === REFUSED) {
          return { ok: false, status: 400, text: async () => JSON.stringify({ ok: false, message: 'That value is out of range.' }) };
        }
        return reply({ ok: true, message: 'Setting updated.' });
      }
      if (text.includes('/query/messages')) {
        return reply({ entries: [
          { ts: Date.now(), sender: 'Steve', content: 'found a diamond' },
          { ts: Date.now(), sender: 'Alex', content: 'nice find' }
        ], count: 2 });
      }
      if (text.includes('/snapshot')) return reply(SNAPSHOT);
      return reply({ ok: true });
    };
    window.EventSource = class { close() {} };
  }
});

const w = dom.window;
const d = w.document;
const wait = ms => new Promise(r => setTimeout(r, ms));
const tab = name => [...d.querySelectorAll('#tabs button')].find(b => b.textContent.startsWith(name)).click();
const results = {};
const check = (name, condition, detail) => { results[name] = { pass: !!condition, detail }; };
const settingRow = name => [...d.querySelectorAll('#settings-list .setting')]
  .find(row => row.querySelector('.name') && row.querySelector('.name').textContent === name);
const findButton = (root, text) => [...root.querySelectorAll('button')].find(b => b.textContent === text);
const lastWrite = () => [...requests].reverse().find(r => r.method === 'POST' && r.url.includes('/settings'));

(async () => {
  await wait(250);

  // ---- the Modules tab offers a way into a module's settings.
  tab('Modules');
  await wait(150);
  const settingsButton = findButton(d.getElementById('view-modules'), 'Settings');
  check('module-has-settings-button', !!settingsButton);
  settingsButton.click();
  await wait(200);

  const dialog = d.getElementById('settings-dialog');
  check('dialog-opens', dialog.hasAttribute('open'), 'open=' + dialog.hasAttribute('open'));
  check('dialog-titled-with-module', d.getElementById('settings-title').textContent === 'Chat Logger',
    d.getElementById('settings-title').textContent);
  check('settings-fetched', requests.some(r => r.url.includes('/api/v1/modules/chat-logger/settings')));

  // ---- every setting is drawn, with the control its type calls for.
  check('all-settings-drawn', d.querySelectorAll('#settings-list .setting').length === SETTINGS.length,
    String(d.querySelectorAll('#settings-list .setting').length));
  check('groups-in-order', [...d.querySelectorAll('#settings-list .settings-group-head')]
    .map(h => h.textContent).join(',') === 'General,Advanced',
    [...d.querySelectorAll('#settings-list .settings-group-head')].map(h => h.textContent).join(','));
  check('bool-is-checkbox', !!settingRow('log-chat').querySelector('input[type="checkbox"]'));
  check('int-is-slider', !!settingRow('max-lines').querySelector('input[type="range"]'));
  check('double-is-slider', !!settingRow('speed').querySelector('input[type="range"]'));
  check('enum-is-select', !!settingRow('mode').querySelector('select'));
  check('enum-options', [...settingRow('mode').querySelectorAll('option')].map(o => o.value).join(',') === 'FAST,SLOW');
  check('string-is-text', !!settingRow('label').querySelector('input[type="text"]'));
  check('list-is-textarea', !!settingRow('names').querySelector('textarea'));
  check('list-one-per-line', settingRow('names').querySelector('textarea').value === 'a\nb',
    JSON.stringify(settingRow('names').querySelector('textarea').value));
  check('hidden-setting-marked', settingRow('gated').classList.contains('hidden-setting'));
  check('description-shown', !!settingRow('max-lines').querySelector('.desc'));

  // ---- each control sends the right request.
  const bool = settingRow('log-chat').querySelector('input[type="checkbox"]');
  bool.checked = false;
  bool.dispatchEvent(new w.Event('change', { bubbles: true }));
  await wait(150);
  let write = lastWrite();
  check('bool-sends-boolean', write && write.body.setting === 'log-chat' && write.body.value === false,
    JSON.stringify(write && write.body));

  const range = settingRow('speed').querySelector('input[type="range"]');
  range.value = '4.5';
  range.dispatchEvent(new w.Event('change', { bubbles: true }));
  await wait(150);
  write = lastWrite();
  check('double-sends-number', write && write.body.setting === 'speed' && write.body.value === 4.5,
    JSON.stringify(write && write.body));

  const select = settingRow('mode').querySelector('select');
  select.value = 'SLOW';
  select.dispatchEvent(new w.Event('change', { bubbles: true }));
  await wait(150);
  write = lastWrite();
  check('enum-sends-name', write && write.body.setting === 'mode' && write.body.value === 'SLOW',
    JSON.stringify(write && write.body));

  const text = settingRow('label').querySelector('input[type="text"]');
  text.value = 'changed';
  text.dispatchEvent(new w.Event('change', { bubbles: true }));
  await wait(150);
  write = lastWrite();
  check('string-sends-text', write && write.body.setting === 'label' && write.body.value === 'changed',
    JSON.stringify(write && write.body));

  const area = settingRow('names').querySelector('textarea');
  area.value = 'x\ny\nz';
  area.dispatchEvent(new w.Event('change', { bubbles: true }));
  await wait(150);
  write = lastWrite();
  check('list-sends-array',
    write && write.body.setting === 'names' && Array.isArray(write.body.value)
      && write.body.value.join(',') === 'x,y,z',
    JSON.stringify(write && write.body));

  // ---- a refused write puts the control back where it was.
  const intBox = settingRow('max-lines').querySelector('input[type="number"]');
  intBox.value = '999';
  intBox.dispatchEvent(new w.Event('change', { bubbles: true }));
  await wait(200);
  check('refused-write-restores-number', intBox.value === '8', 'box=' + intBox.value);
  check('refused-write-reports', [...d.querySelectorAll('#toast .item')].some(i => /out of range/.test(i.textContent)),
    [...d.querySelectorAll('#toast .item')].map(i => i.textContent).join(' | '));

  // ---- closing clears the marker so a late reply cannot repaint a closed dialog.
  d.getElementById('settings-close').click();
  await wait(80);
  // openSettings is declared with let at script top level, so it is a global lexical binding rather
  // than a property of window; eval in the page's own scope is how a test can read it.
  check('close-clears-marker', w.eval('openSettings === null'));
  check('close-shuts-dialog', !dialog.hasAttribute('open'));

  // ---- search asks the client for the stored log, not the snapshot.
  tab('Search');
  await wait(150);
  const searchInputs = [...d.querySelectorAll('#view-search input')];
  searchInputs[0].value = 'diamond';
  findButton(d.getElementById('view-search'), 'Search').click();
  await wait(200);
  check('search-called-query', requests.some(r => r.url.includes('/api/v1/query/messages') && r.url.includes('q=diamond')));
  const hits = [...d.querySelectorAll('#view-search .search-hit')];
  check('search-shows-hits', hits.length === 2, 'hits=' + hits.length);
  check('search-hit-text', hits.length > 0 && /Steve: found a diamond/.test(hits[0].textContent),
    hits.length > 0 ? hits[0].textContent : '');

  // ---- the search survives the three-second rebuild.
  await w.refreshAll();
  await wait(150);
  check('search-results-survive-refresh', d.querySelectorAll('#view-search .search-hit').length === 2,
    String(d.querySelectorAll('#view-search .search-hit').length));

  // ---- notify is reachable and sends a message.
  findButton(d.querySelector('header'), 'Notify').click();
  await wait(120);
  const dialog2 = [...d.querySelectorAll('dialog')].find(x => x.hasAttribute('open') && /Notify/.test(x.textContent));
  check('notify-dialog-opens', !!dialog2);
  if (dialog2) {
    const box = dialog2.querySelector('input');
    box.value = 'come look';
    findButton(dialog2, 'Send').click();
    await wait(150);
    const notify = [...requests].reverse().find(r => r.url.includes('/api/v1/notify'));
    check('notify-sends-message', notify && notify.body.message === 'come look', JSON.stringify(notify && notify.body));
  } else {
    check('notify-sends-message', false, 'no dialog');
  }

  process.stdout.write(JSON.stringify({ results, errors }));
  process.exit(0);
})();
"""


@unittest.skipUnless(NODE, "node is not installed")
@unittest.skipUnless(_jsdom_available(), "jsdom is not installed (npm install jsdom)")
class CompanionAppSettingsTest(unittest.TestCase):
    """The settings editor and search must drive the real API correctly."""

    @classmethod
    def setUpClass(cls):
        harness = ROOT / "tools" / "tests" / "_app_settings_harness.js"
        harness.write_text(HARNESS, encoding="utf-8")
        try:
            proc = subprocess.run(
                [NODE, str(harness), str(APP)],
                capture_output=True,
                text=True,
                timeout=180,
                env=_node_env(),
            )
        finally:
            harness.unlink(missing_ok=True)
        if proc.returncode != 0:
            raise AssertionError(f"harness failed:\n{proc.stdout}\n{proc.stderr}")
        cls.report = json.loads(proc.stdout)

    def test_no_javascript_errors(self):
        self.assertEqual([], self.report["errors"])

    def test_modules_tab_offers_settings(self):
        self._passes("module-has-settings-button")

    def test_dialog_opens_and_names_the_module(self):
        self._passes("dialog-opens")
        self._passes("dialog-titled-with-module")
        self._passes("settings-fetched")

    def test_every_setting_is_drawn(self):
        self._passes("all-settings-drawn")
        self._passes("groups-in-order")
        self._passes("description-shown")

    def test_controls_match_their_types(self):
        for name in ("bool-is-checkbox", "int-is-slider", "double-is-slider",
                     "enum-is-select", "enum-options", "string-is-text",
                     "list-is-textarea", "list-one-per-line"):
            self._passes(name)

    def test_a_hidden_setting_is_shown_as_hidden(self):
        self._passes("hidden-setting-marked")

    def test_each_control_sends_the_right_value(self):
        for name in ("bool-sends-boolean", "double-sends-number", "enum-sends-name",
                     "string-sends-text", "list-sends-array"):
            self._passes(name)

    def test_a_refused_write_restores_the_control(self):
        self._passes("refused-write-restores-number")
        self._passes("refused-write-reports")

    def test_closing_clears_the_open_module(self):
        self._passes("close-clears-marker")
        self._passes("close-shuts-dialog")

    def test_search_uses_the_stored_log(self):
        self._passes("search-called-query")
        self._passes("search-shows-hits")
        self._passes("search-hit-text")

    def test_search_results_survive_a_refresh(self):
        self._passes("search-results-survive-refresh")

    def test_notify_sends_a_message(self):
        self._passes("notify-dialog-opens")
        self._passes("notify-sends-message")

    def _passes(self, name: str) -> None:
        entry = self.report["results"].get(name)
        self.assertIsNotNone(entry, f"the harness did not report {name}")
        self.assertTrue(entry["pass"], f"{name} failed ({entry.get('detail', '')})")


if __name__ == "__main__":
    unittest.main()
