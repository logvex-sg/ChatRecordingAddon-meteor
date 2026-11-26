# Training datasets

Three datasets describing Log Client, generated from the source by `tools/build_datasets.py`.

```bash
python3 tools/build_datasets.py      # regenerate
```

Nothing here is typed by hand, so the data cannot drift from the code. Change a setting and re-run
the generator; the dictionary, the Q&A pairs and the conversations all pick it up.

| File | Records | Size | Shape |
|---|---|---|---|
| `dictionary.jsonl` | 292 | 94 KB | one fact per line |
| `qa.jsonl` | 328 | 112 KB | single user/assistant pair |
| `conversations.jsonl` | 28 | 29 KB | multi-turn dialogue |
| `dictionary.md` | — | 32 KB | human-readable index |

## dictionary.jsonl

A lookup table: one record per module, setting, command, API endpoint and concept.

```json
{"kind": "setting", "id": "setting:keyword-alerts.cooldown-seconds",
 "term": "keyword-alerts.cooldown-seconds", "module": "keyword-alerts", "group": "General",
 "type": "Integer",
 "definition": "Shortest gap between two alerts for the same rule. Matches inside the gap are counted and reported with the next alert instead of printing a line each.",
 "default": "2", "source": "src/main/java/com/logvex/addon/chatlog/modules/KeywordAlertsModule.java"}
```

Use it for retrieval, for building a search index, or as grounding context in a RAG pipeline. The
`id` is stable and unique, and `source` points at the file the fact came from, so an answer can cite
its origin.

`kind` is one of `module`, `setting`, `command`, `endpoint`, `concept`.

## qa.jsonl

Question and answer pairs in chat form, ready for instruction tuning.

```json
{"messages": [
   {"role": "user", "content": "What does the chat-logger setting player-messages do?"},
   {"role": "assistant", "content": "player-messages in chat-logger (Boolean) — Record direct messages. Its default is true."}],
 "topic": "setting",
 "source": "src/main/java/com/logvex/addon/chatlog/modules/ChatLoggerModule.java"}
```

Answers are short and factual on purpose. The point is to teach what a setting does, not to produce
prose. `topic` lets you filter to one subject, and `source` again records provenance.

## conversations.jsonl

Multi-turn dialogues where a user works through a real task. This is the dataset to use if you want
an assistant that holds a thread instead of answering one question at a time.

```json
{"title": "Bridging Discord and Minecraft both ways", "topic": "bridge",
 "messages": [
   {"role": "user", "content": "Can I talk to Minecraft from Discord?"},
   {"role": "assistant", "content": "Yes, with tools/discord_bridge.py. ..."},
   {"role": "user", "content": "Is it safe? Someone in Discord could run commands, right?"},
   {"role": "assistant", "content": "Not through the bridge. The companion chat endpoint ..."}]}
```

Roles alternate strictly, every conversation starts with a user turn, and there is an even number of
messages. The topics are `module`, `webhook`, `bridge`, `chatlog`, `api`, `embed` and
`troubleshooting`.

## dictionary.md

The same facts as `dictionary.jsonl`, laid out for reading. Useful as a wiki page or for pasting
into a context window.

## Notes on the data

**No Java in the text.** A setting default can be an arbitrary expression, and pasting
`KeywordRule.defaults().stream().map(KeywordRule::encode).toList()` into a dataset puts code in
front of a model as if it were prose. Known constants are resolved and computed defaults are
described in words instead. A test enforces this.

**Abstract classes are excluded.** `AutomationModule` extends `AddonModule` but is a base class, not
something a user can turn on, so it is not an entry.

**Only real settings are listed.** `NotificationModule` holds a
`Map<NotificationType, Setting<Boolean>>`, which is a field of settings, not one setting. Every real
setting names itself with `.name(...)` in its builder chain, and that is the filter used.

**Small on purpose.** 292 dictionary records is a reference, not a corpus. It is meant to be
combined with real documentation, not to train a model from scratch. If you want more volume, the
honest way to get it is to add more source: the README sections, the per-module doc comments, and
the command help text are all still unextracted.

## Using the data: the assistant bot

`tools/assistant_bot.py` answers questions from these datasets. It is a retrieval bot, not a
language model: it returns the best matching record and says so when nothing matches.

```bash
python3 tools/assistant_bot.py                        # interactive
python3 tools/assistant_bot.py --ask "how do I send chat to discord"
python3 tools/assistant_bot.py --stats
python3 tools/assistant_bot.py --ask "companion port" --plain    # no box drawing
python3 tools/assistant_bot.py --ask "companion port" --width 72  # wider box
```

An answer is a bordered block, the same shape the in-game assistant and the Discord webhooks use:
the definition across the top, then `related`, `kind` and `source`, with the record id in the
footer. The box widens to fit a long source path, because a shortened path sends you to the wrong
file.

`--plain` drops the box for pipes, log files, or a terminal that cannot render the box-drawing
characters. The facts are identical; only the framing changes.

It needs no API key and no network. Because it can only return a record that exists, it cannot
invent a setting — the failure mode is "I do not have that" rather than a plausible wrong answer.
Measured on a fixed set of 28 questions: 89% return the exact record, 96% land in the right module,
and all 8 out-of-scope questions are refused. Those numbers are enforced by `EvalSetTest`, so a
change that makes retrieval worse fails the build.

It can also count its own usage, anonymously and off by default. See `TELEMETRY.md`.

## Extending it

`build_datasets.py` is organised by source. To add a dataset:

1. Write a `build_*` function that returns a list of dicts.
2. Add it to `main()`.
3. Add a shape test in `tools/tests/test_build_datasets.py`.

The parsers are deliberately conservative: anything they cannot parse confidently is dropped rather
than guessed at, because a wrong fact in a training set is worse than a missing one.
