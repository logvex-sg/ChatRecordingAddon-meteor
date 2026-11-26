# Anonymous usage counting

The assistant bot can count how it is used. This page says exactly what is recorded, and the
design goal behind it: **the collection cannot leak what you asked**, because it never stores a
question.

## It is off until you turn it on

```bash
python3 tools/assistant_bot.py --opt-in            # turn it on
python3 tools/assistant_bot.py --opt-out           # turn it off and erase everything
python3 tools/assistant_bot.py --preview-telemetry # see the exact payload first
```

Nothing is written while counting is off. `--opt-out` does not just stop collection, it deletes the
stored counts.

## What is recorded

| Recorded | Why |
|---|---|
| Which dataset record ids were answered, and how many times | Shows which parts of the documentation people actually need |
| How many questions were not answered at all | Shows where the data has gaps |
| Which module names an unanswered question mentioned | Narrows a gap to a module without storing the question |
| The number of records in the dataset, and the date to the day | Makes a count meaningful without identifying anyone |

## What is not recorded

- The text of any question, answered or not
- Your name, hostname, or operating system username
- File paths
- Server addresses or IPs
- Player names from chat
- Any timestamp finer than a day
- Any identifier that links two sessions together

There is no device id and no random session id. Two runs cannot be correlated into a history of one
person, because there is nothing to correlate on.

## Why the record ids are safe

The ids look like `module:chat-logger` and `setting:webhook-relay.webhook-url`. They are generated
from this repository's source and are identical for every user, so they describe the documentation,
not the person asking. They are the same strings you can read in `datasets/dictionary.jsonl`.

## The gap this leaves, stated honestly

Because question text is never stored, this data cannot tell you *how* people phrase things. If
someone asks "how do I pipe chat into my discord" and gets no answer, the count tells you the
`webhook` area has a gap but not what wording would have found it.

That is a real cost, and it is the deliberate trade. The alternative — storing questions — collects
whatever players type into Minecraft chat, which routinely contains names, server addresses and
private conversation. A dataset that useful is not worth building from people who did not agree to
it.

If you want phrasing data, the honest route is an explicit opt-in survey that says what it will
store, not a silent log.

## Where it is stored

`~/.local/share/logclient/usage.json`, or `$XDG_DATA_HOME/logclient/usage.json` if you set that.
Delete the file at any time; the assistant recreates it empty.

## Sharing the counts

`--preview-telemetry` prints the payload. If you choose to share it, you paste that JSON yourself.
The tool never sends anything over the network, and it has no code that could: there is no HTTP
client in it at all.

## For contributors

`TelemetryPrivacyTest` in `tools/tests/test_assistant_bot.py` feeds the counter a name, a server
address, a path, a password and a chat line, then asserts none of it reaches disk. It was checked by
mutation: storing the raw question, ignoring the opt-in gate, and storing every word instead of
module names each make it fail. If you change the telemetry code and those tests still pass, the
change is safe; if you weaken a test to make it pass, you have broken the guarantee.
