#!/usr/bin/env python3
"""Generate training datasets from the Log Client source.

Everything here is parsed out of the Java source rather than typed by hand, so the data cannot
drift from the code. Re-run after changing a module, a setting or a command:

    python3 tools/build_datasets.py

Three datasets are produced, each aimed at a different training task:

  dictionary.jsonl   one record per module, setting and command. A lookup table: what exists,
                     what it is called, what it does, where it is defined. Good for retrieval,
                     for building an index, or as grounding context for a RAG pipeline.
  qa.jsonl           question/answer pairs in chat form. Good for instruction tuning, because
                     the answers are short and factual.
  conversations.jsonl  multi-turn dialogues where a user works through a real task and the
                     assistant answers using the other two datasets. Good for teaching an
                     assistant to hold a thread instead of answering one question at a time.

Output goes to tools/datasets/. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field, asdict
from typing import Any, Iterable, Iterator, Optional

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "datasets")

VERSION_HINT = "Log Client"


# --------------------------------------------------------------------------- source parsing


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source: str) -> str:
    """Remove Java comments so descriptions in comments are not mistaken for code."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//[^\n]*", "", source)
    return source


def balanced(text: str, start: int, open_char: str = "(", close_char: str = ")") -> str:
    """Return the contents of the balanced pair starting at ``start`` (which holds open_char)."""
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == open_char:
            depth += 1
        elif char == close_char:
            depth -= 1
            if depth == 0:
                return text[start + 1 : index]
    return ""


def java_string_literals(expression: str) -> list[str]:
    """Concatenate the string literals in a Java expression, honouring '+' and escapes."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', expression)
    out = []
    for part in parts:
        out.append(part.encode("utf-8").decode("unicode_escape").replace('\\"', '"'))
    return out


def joined_string(expression: str) -> str:
    return "".join(java_string_literals(expression)).strip()


@dataclass
class SettingFact:
    name: str
    type: str
    description: str
    default: str
    module: str
    group: str


@dataclass
class ModuleFact:
    name: str
    class_name: str
    description: str
    category: str
    aliases: list[str] = field(default_factory=list)
    settings: list[SettingFact] = field(default_factory=list)
    source: str = ""


@dataclass
class CommandFact:
    usage: str
    description: str
    group: str
    source: str = ""


@dataclass
class EndpointFact:
    method: str
    path: str
    description: str


# --------------------------------------------------------------------------- modules


# Concrete modules only. Abstract bases such as AutomationModule also extend AddonModule but are
# not something a user can turn on, so they must not appear as entries.
MODULE_RE = re.compile(r"public\s+(?!abstract\s)class\s+(\w+)\s+extends\s+AddonModule")
SUPER_RE = re.compile(r"super\s*\(", re.S)


def parse_modules() -> list[ModuleFact]:
    modules: list[ModuleFact] = []
    for directory, _dirs, files in os.walk(JAVA_ROOT):
        for filename in sorted(files):
            if not filename.endswith(".java"):
                continue
            path = os.path.join(directory, filename)
            raw = read(path)
            if "extends AddonModule" not in raw:
                continue
            match = MODULE_RE.search(raw)
            if not match:
                continue
            body = strip_comments(raw)
            # Re-find the class in the stripped body: offsets from the raw source are invalid here.
            stripped_match = MODULE_RE.search(body)
            if not stripped_match:
                continue
            class_name = stripped_match.group(1)

            # The super(...) call carries category, name, description and aliases.
            super_match = SUPER_RE.search(body, stripped_match.end())
            category = ""
            name = ""
            description = ""
            aliases: list[str] = []
            if super_match:
                args = balanced(body, super_match.end() - 1)
                pieces = split_top_level(args)
                if pieces:
                    category = resolve_category(pieces[0].strip())
                if len(pieces) > 1:
                    name = joined_string(pieces[1])
                if len(pieces) > 2:
                    description = joined_string(pieces[2])
                for extra in pieces[3:]:
                    value = joined_string(extra)
                    if value:
                        aliases.append(value)

            module = ModuleFact(
                name=name or class_name,
                class_name=class_name,
                description=description,
                category=category,
                aliases=aliases,
                source=os.path.relpath(path, ROOT),
            )
            module.settings = parse_settings(body, module.name)
            modules.append(module)
    modules.sort(key=lambda m: (m.category, m.name))
    return modules


CATEGORY_CONSTANTS = {
    "LogClient.CATEGORY": "Log Client",
}


def resolve_category(expression: str) -> str:
    """Turn a category argument into readable text.

    Modules pass either a string literal or a constant such as LogClient.CATEGORY, and the
    constant is what almost every module uses, so leaving it unresolved loses the category.
    """
    if not expression:
        return ""
    if expression.startswith('"'):
        return joined_string(expression)
    name = expression.split(".")[-1].strip()
    if expression in CATEGORY_CONSTANTS:
        return CATEGORY_CONSTANTS[expression]
    if name == "CATEGORY":
        return "Log Client"
    return name


def split_top_level(text: str) -> list[str]:
    """Split on commas that are not inside brackets, braces or string literals."""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    in_string = False
    escaped = False
    for char in text:
        if in_string:
            current.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            current.append(char)
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if current:
        parts.append("".join(current))
    return [p for p in (part.strip() for part in parts) if p]


SETTING_DECL_RE = re.compile(r"\bSetting\s*<")
DECL_NAME_RE = re.compile(r"\s*(?:\[\s*\])?\s*(\w+)\s*=")
GROUP_RE = re.compile(r"SettingGroup\s+(\w+)\s*=\s*settings\.(?:getDefaultGroup|createGroup)\(\s*\"?([^\")]*)\"?")


def parse_settings(body: str, module_name: str) -> list[SettingFact]:
    """Find every Setting<...> declaration and the builder chain that configures it."""
    groups = {}
    for match in GROUP_RE.finditer(body):
        variable, label = match.group(1), match.group(2)
        groups[variable] = label or "General"

    settings: list[SettingFact] = []
    for match in SETTING_DECL_RE.finditer(body):
        # Read the generic type by balancing angle brackets, so List<String> and deeper nesting
        # are handled. A plain regex stops at the first '>' and loses those declarations.
        generic_start = body.index("<", match.start())
        setting_type = balanced(body, generic_start, "<", ">").strip()
        after = body[generic_start + len(setting_type) + 2:]
        name_match = DECL_NAME_RE.match(after)
        if not name_match:
            continue
        variable = name_match.group(1)

        # The builder chain runs until the statement ends.
        tail = body[generic_start + len(setting_type) + 2 + name_match.end():]
        end = tail.find(");")
        chain = tail[: end + 1] if end != -1 else tail[:800]

        name = _builder_string(chain, "name")
        description = _builder_string(chain, "description")
        default = _builder_value(chain, "defaultValue")

        # A Map<K, Setting<V>> field such as the notification type toggles is not a setting a user
        # can edit, so require the builder chain to name it. Every real setting does.
        if ".name(" not in chain:
            continue

        # Which group the declaration is added to: sgX.add(new ...) or settings.getDefaultGroup().
        prefix = body[max(0, match.start() - 120): match.start()]
        group_name = "General"
        group_match = re.search(r"(\w+)\.add\(", prefix)
        if group_match and group_match.group(1) in groups:
            group_name = groups[group_match.group(1)]

        settings.append(SettingFact(
            name=name or variable,
            type=setting_type,
            description=description,
            default=default,
            module=module_name,
            group=group_name,
        ))
    return settings


def _builder_string(chain: str, method: str) -> str:
    match = re.search(rf"\.{method}\(", chain)
    if not match:
        return ""
    args = balanced(chain, match.end() - 1)
    return joined_string(args)


KNOWN_CONSTANTS = {
    "DEFAULT_CAPACITY": "a built-in default size",
}


def _builder_value(chain: str, method: str) -> str:
    """Describe a setting's default in plain words.

    A default can be a literal or an arbitrary Java expression. Pasting the expression into a
    dataset puts code in front of a model as if it were prose, so known constants are resolved
    and anything computed is described rather than quoted.
    """
    match = re.search(rf"\.{method}\(", chain)
    if not match:
        return ""
    args = balanced(chain, match.end() - 1).strip()
    if not args:
        return ""

    if args.lstrip().startswith('"'):
        return "".join(java_string_literals(args))

    # A bare constant, possibly qualified: SessionTimeline.DEFAULT_CAPACITY.
    if re.fullmatch(r"[\w.]*[A-Z][A-Z0-9_]*", args):
        name = args.split(".")[-1]
        return KNOWN_CONSTANTS.get(name, "a built-in default")

    # A method call or builder chain: describe it instead of quoting Java.
    if "(" in args or "->" in args or "::" in args:
        if "toList()" in args or ".stream()" in args:
            return "a built-in list of defaults"
        return "computed at startup"
    return args[:120]


# --------------------------------------------------------------------------- commands


COMMAND_ENTRY_RE = re.compile(r"new Entry\(\s*Group\.(\w+)\s*,(.*?)\)\s*,?\s*(?=new Entry\(|\)\s*;|$)", re.S)


def parse_commands() -> list[CommandFact]:
    path = os.path.join(JAVA_ROOT, "com", "logvex", "addon", "common", "CommandCatalog.java")
    if not os.path.exists(path):
        return []
    body = strip_comments(read(path))
    commands: list[CommandFact] = []
    for match in COMMAND_ENTRY_RE.finditer(body):
        group = match.group(1)
        args = split_top_level(match.group(2))
        if len(args) < 2:
            continue
        usage = joined_string(args[0])
        description = joined_string(args[1])
        if not usage:
            continue
        commands.append(CommandFact(usage=usage, description=description, group=group,
                                    source="src/main/java/com/logvex/addon/common/CommandCatalog.java"))
    return commands


# --------------------------------------------------------------------------- endpoints


ROUTE_RE = re.compile(r'route\(\s*"(\w+)"\s*,\s*"([^"]+)"')


def parse_endpoints() -> list[EndpointFact]:
    path = os.path.join(JAVA_ROOT, "com", "logvex", "addon", "companion", "core", "CompanionRouter.java")
    if not os.path.exists(path):
        return []
    body = strip_comments(read(path))
    descriptions = {
        "/api/v1/snapshot": "Everything the dashboard needs in one response: modules, tasks, players, chat, bot, storage.",
        "/api/v1/modules": "Module list with each module's on/off state.",
        "/api/v1/tasks": "The bot task queue.",
        "/api/v1/players": "Known players and their observed activity.",
        "/api/v1/chat": "Recent chat lines.",
        "/api/v1/bot": "The bot's state and current objective.",
        "/api/v1/storage": "Storage usage for the chat archive.",
        "/api/v1/queries": "The query names the query endpoint accepts.",
        "/api/v1/events": "Server-sent events: chat, bot and session updates, with replay from Last-Event-ID.",
        "/api/v1/bot/start": "Start the autonomous bot.",
        "/api/v1/bot/pause": "Pause the bot.",
        "/api/v1/bot/resume": "Resume a paused bot.",
        "/api/v1/bot/stop": "Stop the bot.",
        "/api/v1/bot/emergency-stop": "Emergency stop: halt everything immediately.",
        "/api/v1/tasks": "Enqueue a task with an objective.",
        "/api/v1/modules/{name}": "Turn a module on or off.",
        "/api/v1/chat": "Send a chat line to the server.",
        "/api/v1/command": "Run a command, as if typed by the player.",
        "/api/v1/notify": "Raise a notification inside the client.",
        "/api/v1/query/{name}": "Run a named query with parameters.",
        "/api/v1/tasks/{id}": "Cancel a queued task.",
    }
    endpoints: list[EndpointFact] = []
    seen = set()
    for match in ROUTE_RE.finditer(body):
        method, path = match.group(1), match.group(2)
        key = (method, path)
        if key in seen:
            continue
        seen.add(key)
        description = descriptions.get(path, "")
        if not description:
            continue
        endpoints.append(EndpointFact(method=method, path=path, description=description))
    endpoints.sort(key=lambda e: e.path)
    return endpoints


# --------------------------------------------------------------------------- dataset: dictionary


def build_dictionary(modules: list[ModuleFact], commands: list[CommandFact],
                     endpoints: list[EndpointFact]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []

    for module in modules:
        records.append({
            "kind": "module",
            "id": f"module:{module.name}",
            "term": module.name,
            "aliases": module.aliases,
            "category": module.category,
            "definition": module.description,
            "class": module.class_name,
            "source": module.source,
            "settings_count": len(module.settings),
        })
        for setting in module.settings:
            records.append({
                "kind": "setting",
                "id": f"setting:{module.name}.{setting.name}",
                "term": f"{module.name}.{setting.name}",
                "module": module.name,
                "group": setting.group,
                "type": setting.type,
                "definition": setting.description,
                "default": setting.default,
                "source": module.source,
            })

    for command in commands:
        records.append({
            "kind": "command",
            "id": f"command:{command.usage}",
            "term": command.usage,
            "group": command.group,
            "definition": command.description,
            "source": command.source,
        })

    for endpoint in endpoints:
        records.append({
            "kind": "endpoint",
            "id": f"endpoint:{endpoint.method} {endpoint.path}",
            "term": f"{endpoint.method} {endpoint.path}",
            "definition": endpoint.description,
            "source": "src/main/java/com/logvex/addon/companion/core/CompanionRouter.java",
        })

    # Concepts that are not a single class or command but come up constantly in support.
    for concept in CONCEPTS:
        records.append(concept)
    return records


CONCEPTS: list[dict[str, Any]] = [
    {
        "kind": "concept",
        "id": "concept:companion-token",
        "term": "companion access token",
        "aliases": ["access token", "companion token"],
        "definition": "The shared secret the companion server requires. Sent as an Authorization "
                      "Bearer header or a token query parameter. The server binds to loopback by "
                      "default and refuses a remote bind without an explicit opt-in.",
        "source": "src/main/java/com/logvex/addon/companion/core/CompanionConfig.java",
    },
    {
        "kind": "concept",
        "id": "concept:webhook",
        "term": "webhook relay",
        "aliases": ["discord webhook", "webhook"],
        "definition": "One-way delivery of chat events to Discord as rich embeds. Opt-in: nothing "
                      "is sent until a URL is configured. A failed delivery is retried for "
                      "transient errors (network, 5xx, 429) and dropped for a 4xx, because a "
                      "rejected payload will not become valid on a retry.",
        "source": "src/main/java/com/logvex/addon/chatlog/core/WebhookQueue.java",
    },
    {
        "kind": "concept",
        "id": "concept:embed-budget",
        "term": "embed character budget",
        "aliases": ["6000 characters", "embed limit"],
        "definition": "Discord rejects an embed whose visible text exceeds 6000 characters across "
                      "all its parts. Each part is clipped to its own limit and to the remaining "
                      "total budget as it is added, so the sum cannot exceed the limit.",
        "source": "src/main/java/com/logvex/addon/chatlog/core/DiscordEmbed.java",
    },
    {
        "kind": "concept",
        "id": "concept:slash-rule",
        "term": "leading slash rule",
        "aliases": ["slash command", "sendChat"],
        "definition": "The companion chat endpoint forwards a message starting with '/' to the "
                      "command path, which runs it as the player. Any external input must have "
                      "leading slashes stripped first, or it becomes remote command execution.",
        "source": "src/main/java/com/logvex/addon/companion/mc/MinecraftCompanionApi.java",
    },
    {
        "kind": "concept",
        "id": "concept:chat-event-keys",
        "term": "chat event field names",
        "aliases": ["content vs message"],
        "definition": "The companion event stream names a chat line's text 'content', following "
                      "ChatEntry.toMap(). The snapshot names the same field 'message', following "
                      "ChatEntryView. A consumer must accept both.",
        "source": "src/main/java/com/logvex/addon/companion/core/CompanionSnapshot.java",
    },
    {
        "kind": "concept",
        "id": "concept:discord-bridge",
        "term": "Discord bridge",
        "aliases": ["discord bot", "bridge", "two-way chat", "talk to minecraft from discord",
                    "send chat to discord"],
        "definition": "tools/discord_bridge.py mirrors a Discord channel and Minecraft chat in "
                      "both directions, on top of the companion API. It is a separate Python "
                      "process, not part of the mod, because a bot token must not ship inside a "
                      "client mod and a bot has to stay online when the game is closed.",
        "source": "tools/discord_bridge.py",
    },
    {
        "kind": "concept",
        "id": "concept:bridge-echo-suppression",
        "term": "bridge echo suppression",
        "aliases": ["feedback loop", "duplicate messages", "bridge loop"],
        "definition": "A Discord message becomes a Minecraft chat line, which the bridge would "
                      "read back and post to Discord again, forever. Injected lines are "
                      "remembered and consumed, so one injection produces exactly one echo, and a "
                      "player genuinely repeating the same text later still reaches Discord.",
        "source": "tools/discord_bridge.py",
    },
    {
        "kind": "concept",
        "id": "concept:anonymous-usage-counting",
        "term": "anonymous usage counting",
        "aliases": ["telemetry", "usage data", "anonymous data collection", "opt-in"],
        "definition": "Off by default. When enabled it records only which public record ids were "
                      "answered, how many questions went unanswered, and which module names those "
                      "questions mentioned. It never stores question text, file paths, hostnames "
                      "or usernames, and --preview-telemetry shows the exact payload first.",
        "source": "tools/assistant_bot.py",
    },
    {
        "kind": "concept",
        "id": "concept:assistant-bot",
        "term": "offline assistant bot",
        "aliases": ["assistant_bot", "retrieval bot", "ask a question"],
        "definition": "tools/assistant_bot.py answers questions about Log Client by searching the "
                      "generated datasets. It is retrieval, not a language model: it returns the "
                      "best matching record and says when nothing matches, so it cannot invent a "
                      "setting that does not exist. No API key and no network needed.",
        "source": "tools/assistant_bot.py",
    },
    {
        "kind": "concept",
        "id": "concept:keyword-routing",
        "term": "keyword webhook routing",
        "aliases": ["keyword route", "per-rule webhook"],
        "definition": "A keyword rule can name its own webhook URL. Precedence is the chat-logger "
                      "keyword URL, then the relay's keywords= route, then the shared webhook. A "
                      "route on its own is enough; a shared URL is not required.",
        "source": "src/main/java/com/logvex/addon/chatlog/mc/LogService.java",
    },
]


# --------------------------------------------------------------------------- dataset: qa


def build_qa(modules: list[ModuleFact], commands: list[CommandFact],
             endpoints: list[EndpointFact]) -> list[dict[str, Any]]:
    pairs: list[dict[str, Any]] = []

    def add(question: str, answer: str, topic: str, source: str) -> None:
        pairs.append({"messages": [
            {"role": "user", "content": question},
            {"role": "assistant", "content": answer},
        ], "topic": topic, "source": source})

    for module in modules:
        if not module.description:
            continue
        add(f"What does the {module.name} module do?",
            f"The {module.name} module — {module.description} "
            f"It is in the {module.category} category and has {len(module.settings)} settings.",
            "module", module.source)
        add(f"Which category is {module.name} in?",
            f"{module.name} is in the {module.category} category.",
            "module", module.source)
        if module.aliases:
            add(f"What are the aliases for {module.name}?",
                f"{module.name} can also be reached as: {', '.join(module.aliases)}.",
                "module", module.source)
        for setting in module.settings:
            if not setting.description:
                continue
            default = f" Its default is {setting.default}." if setting.default else ""
            add(f"What does the {module.name} setting {setting.name} do?",
                f"{setting.name} in {module.name} ({setting.type}) — {setting.description}{default}",
                "setting", module.source)

    for command in commands:
        if not command.description:
            continue
        add(f"What does the command `{command.usage}` do?",
            f"`{command.usage}` — {command.description}",
            "command", command.source)

    for endpoint in endpoints:
        if not endpoint.description:
            continue
        add(f"What is the companion endpoint {endpoint.method} {endpoint.path} for?",
            f"{endpoint.method} {endpoint.path} — {endpoint.description}",
            "api", "src/main/java/com/logvex/addon/companion/core/CompanionRouter.java")

    for concept in CONCEPTS:
        add(f"What is the {concept['term']}?",
            concept["definition"], "concept", concept["source"])

    # Cross-cutting questions a real user asks, answered from the same facts.
    categories: dict[str, list[str]] = {}
    for module in modules:
        categories.setdefault(module.category, []).append(module.name)
    for category, names in sorted(categories.items()):
        add(f"Which modules are in the {category} category?",
            f"{category} has {len(names)}: {', '.join(names)}.",
            "category", "src/main/java/com/logvex/addon/framework/systems/modules/")

    groups: dict[str, list[str]] = {}
    for command in commands:
        groups.setdefault(command.group, []).append(command.usage)
    for group, usages in sorted(groups.items()):
        add(f"Which commands are in the {group} group?",
            f"{group} has {len(usages)}: " + ", ".join(f"`{u}`" for u in usages) + ".",
            "command-group", "src/main/java/com/logvex/addon/common/CommandCatalog.java")

    add("How many modules does Log Client have?",
        f"{len(modules)} modules across {len(categories)} categories.",
        "overview", "src/main/java/")
    add("How many settings are there in total?",
        f"{sum(len(m.settings) for m in modules)} settings across {len(modules)} modules.",
        "overview", "src/main/java/")
    add("How do I enable a module?",
        "Open the Log Client window with `!log gui`, find the module in the list, and flip its "
        "switch. Modules can also be toggled from the companion API with POST /api/v1/modules/{name}.",
        "usage", "README.md")
    add("How do I search recorded chat?",
        "Use `log search [terms]`. The archive is also readable from the companion API through "
        "GET /api/v1/chat and the query endpoint.",
        "usage", "src/main/java/com/logvex/addon/common/CommandCatalog.java")
    return pairs


def lower_first(text: str) -> str:
    if not text:
        return text
    return text[0].lower() + text[1:]


# --------------------------------------------------------------------------- dataset: conversations


def build_conversations(modules: list[ModuleFact], commands: list[CommandFact],
                        endpoints: list[EndpointFact]) -> list[dict[str, Any]]:
    by_name = {m.name: m for m in modules}
    conversations: list[dict[str, Any]] = []

    def add(title: str, turns: list[tuple[str, str]], topic: str) -> None:
        messages = []
        for user, assistant in turns:
            messages.append({"role": "user", "content": user})
            messages.append({"role": "assistant", "content": assistant})
        conversations.append({"title": title, "topic": topic, "messages": messages})

    # One conversation per module: what is it, how is it tuned, what are the pitfalls.
    for module in modules:
        if not module.description:
            continue
        turns = [
            (f"I want to use {module.name}. What is it?",
             f"The {module.name} module — {module.description} "
             f"It is in the {module.category} category and has {len(module.settings)} settings."),
        ]
        interesting = [s for s in module.settings if s.description][:3]
        if interesting:
            names = ", ".join(f"`{s.name}`" for s in interesting)
            turns.append((
                "Which settings should I look at first?",
                f"The ones with the most effect are {names}. "
                + " ".join(f"`{s.name}` — {s.description}" for s in interesting)))
        turns.append((
            "How do I turn it on?",
            f"Open the window with `!log gui` and switch on {module.name}, or send "
            f"POST /api/v1/modules/{module.name} with {{\"active\": true}} to the companion API. "
            f"Run `log modules` to confirm it is on."))
        add(f"Getting started with {module.name}", turns, "module")

    # Webhook setup, the most common real task.
    relay = by_name.get("Webhook Relay") or by_name.get("webhook-relay")
    turns = [
        ("I want my chat to show up in Discord. Where do I start?",
         "Use the webhook relay. Create a webhook in the Discord channel, copy its URL, then paste "
         "it into the relay's webhook-url setting. Nothing is sent until that URL is set."),
        ("What happens if Discord is down when a message is sent?",
         "The delivery is queued and retried. Network errors, 5xx and 429 are retried with a "
         "backoff; a 4xx is not, because a rejected payload will not become valid on a retry."),
        ("How do I check it works?",
         "Run `log webhook test`. It sends a test card and reports the result. `log webhook "
         "preview` shows the card in game first, without sending anything."),
        ("Can different keywords go to different channels?",
         "Yes. A keyword rule can name its own webhook URL. Precedence is the chat-logger keyword "
         "URL, then the relay's keywords= route, then the shared webhook. A route on its own is "
         "enough."),
    ]
    add("Sending chat to Discord", turns, "webhook")

    # The Discord bridge.
    add("Bridging Discord and Minecraft both ways", [
        ("Can I talk to Minecraft from Discord?",
         "Yes, with tools/discord_bridge.py. It is a separate Python process that talks to the "
         "companion API in both directions. It is not part of the mod, because a bot token must "
         "not ship inside a client mod and a bot has to stay online when the game is closed."),
        ("What do I need?",
         "A Discord bot token, the channel id, and the companion access token from the Companion "
         "module. Enable the Message Content intent in the developer portal, or messages arrive "
         "with empty text."),
        ("Is it safe? Someone in Discord could run commands, right?",
         "Not through the bridge. The companion chat endpoint forwards a message starting with "
         "'/' to the command path, which runs it as the player. The bridge strips leading slashes "
         "before sending, so `/op Steve` becomes the chat line `op Steve`. A slash mid-sentence is "
         "left alone."),
        ("And the other direction?",
         "Text from Minecraft is escaped before it reaches Discord: @everyone, @here and role "
         "mentions get a zero-width space, and every post pins allowed_mentions to an empty parse "
         "list. A player cannot ping a server by typing it in chat."),
        ("What stops it from looping forever?",
         "Echo suppression. A Discord message becomes a chat line, which the bridge would "
         "otherwise read back and post to Discord again. Injected lines are remembered and "
         "consumed, so one injection produces one echo, and a player genuinely repeating the text "
         "later still reaches Discord."),
    ], "bridge")

    # Chat recording and search.
    add("Recording and searching chat", [
        ("Does Log Client record chat by default?",
         "Yes. Recording is on out of the box, so the archive is already there when you first "
         "search it. `log record off` stops it."),
        ("How do I find something someone said last week?",
         "`log search <terms>`. It searches the recorded archive. `log digest` summarises recent "
         "activity and `log timeline` shows joins, leaves and deaths."),
        ("Where is the archive stored?",
         "Under the game directory in the Log Client folder. `log status` reports the storage "
         "usage, and `log sessions` lists past recording sessions."),
    ], "chatlog")

    # Companion API.
    add("Using the companion API", [
        ("What is the companion server?",
         "A small HTTP server inside the client that serves a dashboard and a JSON API, so a phone "
         "or another machine can watch and control the bots. It is off by default."),
        ("How do I authenticate?",
         "Every request under /api needs the access token, sent as an Authorization Bearer header "
         "or a token query parameter. /health and the app shell are open so a browser can load the "
         "page."),
        ("How do I get live updates?",
         "Subscribe to GET /api/v1/events. It is a server-sent event stream carrying chat, bot and "
         "session events. Send Last-Event-ID on reconnect and the server replays what you missed."),
        ("Is it safe to expose to my network?",
         "Only if you mean to. It binds to loopback by default and refuses a remote bind without "
         "an explicit opt-in, because anyone who can reach the port and has the token can send "
         "chat and run commands as you."),
    ], "api")

    # Embed formatting.
    add("Formatting the Discord cards", [
        ("What do the Discord messages look like?",
         "They are rich embeds: a coloured card with a title, a description and up to 25 fields, "
         "not a wall of text."),
        ("My long footer made the card stop appearing. Why?",
         "Discord rejects an embed over 6000 characters across all its parts, and a 400 is not "
         "retryable, so the card is dropped rather than delayed. Each part is now clipped against "
         "the remaining budget, so the card still sends with the text shortened."),
        ("Can I see the card before sending it?",
         "Yes. `log webhook preview` renders it in game, and the embed preview screen shows "
         "characters used and remaining against the 6000 budget."),
        ("How do I change the colour?",
         "Set the accent setting on the webhook relay. Auto colours each card by its type, or pick "
         "a fixed colour."),
    ], "embed")

    # Troubleshooting.
    add("When nothing shows up", [
        ("Nothing reaches Discord.",
         "Check the webhook URL is set and starts with https://discord.com/api/webhooks/. Run `log "
         "webhook test` and read the result. `log alerts` shows recent delivery failures."),
        ("The bridge connects but Discord messages do nothing.",
         "The Message Content intent is almost certainly off. Without it Discord delivers messages "
         "with empty text, so the bridge filters everything out. Enable it in the developer "
         "portal and restart the bot."),
        ("The bridge says the game did not respond.",
         "That is a 504 from the companion server: the game did not answer within its timeout. It "
         "usually means the client is loading, or the main thread is blocked. Try again once you "
         "are in a world."),
        ("I get 401 from the companion API.",
         "The access token does not match. Copy it again from the Companion module. The token is "
         "never logged, so it will not appear in the client log."),
    ], "troubleshooting")

    return conversations


# --------------------------------------------------------------------------- output


def write_jsonl(path: str, records: Iterable[dict[str, Any]]) -> int:
    count = 0
    with open(path, "w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=False) + "\n")
            count += 1
    return count


def write_dictionary_markdown(path: str, records: list[dict[str, Any]]) -> int:
    """A human-readable companion to the JSONL, for reading in a browser or a wiki."""
    lines = ["# Log Client dictionary", "",
             "Generated from the source by `tools/build_datasets.py`. Do not edit by hand.", ""]
    for kind, title in (("concept", "Concepts"), ("module", "Modules"), ("setting", "Settings"),
                        ("command", "Commands"), ("endpoint", "Companion API")):
        subset = [r for r in records if r["kind"] == kind]
        if not subset:
            continue
        lines.append(f"## {title} ({len(subset)})")
        lines.append("")
        for record in subset:
            term = record.get("term", "")
            definition = record.get("definition", "")
            extra = []
            if record.get("category"):
                extra.append(record["category"])
            if record.get("default"):
                extra.append(f"default `{record['default']}`")
            suffix = f" — _{', '.join(extra)}_" if extra else ""
            lines.append(f"- **{term}** — {definition}{suffix}")
        lines.append("")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    return len(records)


def summarise(records: list[dict[str, Any]], label: str) -> str:
    counts: dict[str, int] = {}
    for record in records:
        key = record.get("kind") or record.get("topic") or "item"
        counts[key] = counts.get(key, 0) + 1
    detail = ", ".join(f"{count} {kind}" for kind, count in sorted(counts.items()))
    return f"{label}: {len(records)} records ({detail})"


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Generate training datasets from the source.")
    parser.add_argument("--out", default=OUT_DIR, help="Output directory")
    args = parser.parse_args(argv)

    modules = parse_modules()
    commands = parse_commands()
    endpoints = parse_endpoints()

    if not modules:
        print("error: no modules found; is the working directory right?", file=sys.stderr)
        return 1

    os.makedirs(args.out, exist_ok=True)

    dictionary = build_dictionary(modules, commands, endpoints)
    qa = build_qa(modules, commands, endpoints)
    conversations = build_conversations(modules, commands, endpoints)

    written = []
    written.append((os.path.join(args.out, "dictionary.jsonl"),
                    write_jsonl(os.path.join(args.out, "dictionary.jsonl"), dictionary),
                    summarise(dictionary, "dictionary.jsonl")))
    written.append((os.path.join(args.out, "qa.jsonl"),
                    write_jsonl(os.path.join(args.out, "qa.jsonl"), qa),
                    summarise(qa, "qa.jsonl")))
    written.append((os.path.join(args.out, "conversations.jsonl"),
                    write_jsonl(os.path.join(args.out, "conversations.jsonl"), conversations),
                    summarise(conversations, "conversations.jsonl")))
    markdown_path = os.path.join(args.out, "dictionary.md")
    write_dictionary_markdown(markdown_path, dictionary)

    for path, _count, line in written:
        size = os.path.getsize(path)
        print(f"{line}, {size:,} bytes")
    print(f"dictionary.md: {os.path.getsize(markdown_path):,} bytes")
    print(f"\nWritten to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
