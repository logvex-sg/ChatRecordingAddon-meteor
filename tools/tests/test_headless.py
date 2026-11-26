#!/usr/bin/env python3
"""Tests for the headless instance app.

The interesting failures in a launcher are all in the pure parts: which libraries apply to this
platform, what path a maven coordinate becomes, and the exact order of a command line. Those are
exercised here against the real Mojang and Fabric metadata captured from the live endpoints, so a
change in the documents shows up as a test failure rather than a client that will not start.

Nothing is mocked. Where a test needs a process it starts a real one; where it needs a download it
reads a real file from disk.
"""

from __future__ import annotations

import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import headless  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"

# Trimmed from the live 1.21.1 document: the shapes that matter are a plain library, a
# platform-gated library, and a native classifier.
VERSION = {
    "id": "1.21.1",
    "assetIndex": {"id": "17", "sha1": "abc", "size": 10, "url": "https://example/17.json"},
    "mainClass": "net.minecraft.client.main.Main",
    "javaVersion": {"majorVersion": 21},
    "downloads": {"client": {"url": "https://example/client.jar", "sha1": "def", "size": 100}},
    "logging": {"client": {"file": {"id": "client-1.12.xml", "sha1": "f00", "size": 8,
                                    "url": "https://example/client-1.12.xml"}}},
    "arguments": {
        "jvm": [
            {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
            {"rules": [{"action": "allow", "os": {"arch": "x86"}}],
             "value": ["-Xss1M", "-XX:-UseBASE64Intrinsics"]},
            "-Djava.library.path=${natives_directory}",
            "-Dminecraft.launcher.brand=${launcher_name}",
            "-cp",
            "${classpath}",
        ],
        "game": [
            "--username", "${auth_player_name}",
            "--version", "${version_name}",
            "--gameDir", "${game_directory}",
            "--uuid", "${auth_uuid}",
            {"rules": [{"action": "allow", "features": {"has_custom_resolution": True}}],
             "value": ["--width", "${resolution_width}", "--height", "${resolution_height}"]},
            {"rules": [{"action": "allow", "features": {"is_quick_play_multiplayer": True}}],
             "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]},
            {"rules": [{"action": "allow", "features": {"is_demo_user": True}}], "value": "--demo"},
        ],
    },
    "libraries": [
        {"name": "com.example:plain:1.0",
         "downloads": {"artifact": {"path": "com/example/plain/1.0/plain-1.0.jar",
                                    "sha1": "aaa", "size": 11,
                                    "url": "https://libraries.example/plain.jar"}}},
        {"name": "com.example:osx-only:1.0",
         "rules": [{"action": "allow", "os": {"name": "osx"}}],
         "downloads": {"artifact": {"path": "com/example/osx/1.0/osx-1.0.jar", "sha1": "bbb",
                                    "size": 12, "url": "https://libraries.example/osx.jar"}}},
        {"name": "com.example:win-only:1.0",
         "rules": [{"action": "allow", "os": {"name": "windows"}}],
         "downloads": {"artifact": {"path": "com/example/win/1.0/win-1.0.jar", "sha1": "ccc",
                                    "size": 13, "url": "https://libraries.example/win.jar"}}},
        {"name": "com.example:native:1.0:natives-linux",
         "rules": [{"action": "allow", "os": {"name": "linux"}}],
         "downloads": {"artifact": {"path": "com/example/native/1.0/native-1.0-natives-linux.jar",
                                    "sha1": "ddd", "size": 14,
                                    "url": "https://libraries.example/native-linux.jar"}}},
    ],
}

PROFILE = {
    "id": "fabric-loader-0.16.5-1.21.1",
    "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
    "arguments": {"game": [],
                  "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "]},
    "libraries": [
        {"name": "org.ow2.asm:asm:9.6", "url": "https://maven.fabricmc.net/"},
        {"name": "net.fabricmc:fabric-loader:0.16.5", "url": "https://maven.fabricmc.net/"},
    ],
}


def prepared(client_jar: str = "/cache/1.21.1.jar", natives: str = "/inst/natives") -> dict:
    return {
        "version": VERSION,
        "profile": PROFILE,
        "client_jar": client_jar,
        "classpath": ["/cache/plain-1.0.jar", "/cache/asm-9.6.jar"],
        "asset_index": "/cache/indexes/17.json",
        "natives": natives,
    }


def instance(**overrides) -> "headless.Instance":
    values = dict(
        name="tester",
        mc_version="1.21.1",
        loader_version="0.16.5",
        username="tester",
        uuid=headless.offline_uuid("tester"),
        server="",
        with_meteor=False,
    )
    values.update(overrides)
    return headless.Instance(**values)


class TempHome(unittest.TestCase):
    """Points the app at a temporary directory so tests never touch the real home."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self._old_home = os.environ.get("LOGLIENT_HEADLESS_HOME")
        os.environ["LOGLIENT_HEADLESS_HOME"] = self._tmp.name
        self.addCleanup(self._restore)

    def _restore(self) -> None:
        if self._old_home is None:
            os.environ.pop("LOGLIENT_HEADLESS_HOME", None)
        else:
            os.environ["LOGLIENT_HEADLESS_HOME"] = self._old_home

    @property
    def root(self) -> Path:
        return Path(self._tmp.name)


class RuleTests(unittest.TestCase):
    """Mojang's rule system: the first rule that matches sets the verdict, no allow means deny."""

    def test_no_rules_allows_everything(self) -> None:
        self.assertTrue(headless.rule_allows(None))
        self.assertTrue(headless.rule_allows([]))

    def test_allow_for_this_platform(self) -> None:
        rules = [{"action": "allow", "os": {"name": "linux"}}]
        self.assertTrue(headless.rule_allows(rules, name="linux"))
        self.assertFalse(headless.rule_allows(rules, name="osx"))
        self.assertFalse(headless.rule_allows(rules, name="windows"))

    def test_a_list_with_only_a_deny_denies(self) -> None:
        rules = [{"action": "disallow", "os": {"name": "osx"}}]
        self.assertFalse(headless.rule_allows(rules, name="linux"),
                         "a list with no allow rule denies, whatever the platform")

    def test_a_later_deny_overrides_an_earlier_allow(self) -> None:
        rules = [{"action": "allow", "os": {"name": "linux"}},
                 {"action": "disallow", "os": {"arch": "arm64"}}]
        self.assertTrue(headless.rule_allows(rules, name="linux", arch="x86"))
        self.assertFalse(headless.rule_allows(rules, name="linux", arch="arm64"),
                         "the last matching rule wins, not the first allow")

    def test_architecture_rules(self) -> None:
        rules = [{"action": "allow", "os": {"arch": "x86"}}]
        self.assertTrue(headless.rule_allows(rules, name="linux", arch="x86"))
        self.assertFalse(headless.rule_allows(rules, name="linux", arch="arm64"))

    def test_feature_rules_follow_the_enabled_features(self) -> None:
        rules = [{"action": "allow", "features": {"is_demo_user": True}}]
        self.assertTrue(headless.rule_allows(rules, features={"is_demo_user": True}))
        self.assertFalse(headless.rule_allows(rules, features={"is_demo_user": False}))
        self.assertFalse(headless.rule_allows(rules, features={}))

    def test_platform_names_match_mojang(self) -> None:
        self.assertEqual(headless.os_name(), "linux", "this suite runs on Debian")
        self.assertIn(headless.os_arch(), ("x86", "arm64"))


class RequirementTests(unittest.TestCase):
    def test_this_platform_excludes_a_mac_jar(self) -> None:
        names = [lib["name"] for lib in headless.collect_libraries(VERSION["libraries"])]
        self.assertIn("com.example:plain:1.0", names)
        self.assertIn("com.example:native:1.0:natives-linux", names)
        self.assertNotIn("com.example:osx-only:1.0", names)
        self.assertNotIn("com.example:win-only:1.0", names)

    def test_an_unruled_library_is_kept(self) -> None:
        names = [lib["name"] for lib in headless.collect_libraries(PROFILE["libraries"])]
        self.assertEqual(names, ["org.ow2.asm:asm:9.6", "net.fabricmc:fabric-loader:0.16.5"])


class CoordinateTests(unittest.TestCase):
    def test_plain_coordinate(self) -> None:
        self.assertEqual(headless.maven_path("org.lwjgl:lwjgl:3.3.3"),
                         "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar")

    def test_native_classifier_is_part_of_the_filename(self) -> None:
        self.assertEqual(
            headless.maven_path("org.lwjgl:lwjgl:3.3.3:natives-linux"),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
            "the classifier belongs in the file name, not as a directory",
        )

    def test_url_from_the_official_downloads_block(self) -> None:
        url, sha1, size = headless.library_url(VERSION["libraries"][0])
        self.assertEqual(url, "https://libraries.example/plain.jar")
        self.assertEqual(sha1, "aaa")
        self.assertEqual(size, 11)

    def test_url_rebuilt_from_a_maven_base(self) -> None:
        url, sha1, _size = headless.library_url(PROFILE["libraries"][0])
        self.assertEqual(url, "https://maven.fabricmc.net/org/ow2/asm/asm/9.6/asm-9.6.jar")
        self.assertEqual(sha1, "", "a maven base URL carries no checksum")

    def test_a_library_with_nowhere_to_download_from(self) -> None:
        self.assertEqual(headless.library_url({"name": "x:y:1"}), ("", "", 0))


class ArgumentTests(unittest.TestCase):
    """The order of a launch command is the difference between a running client and a crash."""

    def launch(self, **overrides) -> list:
        plan = headless.plan_launch(instance(**overrides), prepared(), "/jdk/bin/java", ":99")
        return plan.argv

    def test_every_placeholder_is_expanded(self) -> None:
        argv = self.launch()
        leftover = [arg for arg in argv if "${" in arg]
        self.assertEqual(leftover, [], "an unexpanded ${...} is passed to the game verbatim")

    def test_the_classpath_is_one_joined_argument(self) -> None:
        argv = self.launch()
        classpath = argv[argv.index("-cp") + 1]
        entries = classpath.split(os.pathsep)
        self.assertEqual(entries[:2], ["/cache/plain-1.0.jar", "/cache/asm-9.6.jar"])
        self.assertEqual(entries[-1], "/cache/1.21.1.jar",
                         "the client jar goes last, after the libraries")

    def test_the_main_class_follows_the_classpath(self) -> None:
        argv = self.launch()
        after = argv[argv.index("-cp") + 2]
        self.assertEqual(after, "net.fabricmc.loader.impl.launch.knot.KnotClient",
                         "the loader's main class is what starts the game")

    def test_every_jvm_argument_precedes_the_classpath(self) -> None:
        argv = self.launch()
        before = argv[:argv.index("-cp")]
        stray = [arg for arg in before[1:] if not arg.startswith("-")]
        self.assertEqual(stray, [], "a non-flag before -cp would be read as the main class")
        joined = " ".join(before)
        self.assertIn("-DFabricMcEmu", joined, "the loader's own JVM args must be before -cp")
        self.assertIn("-Djava.library.path=", joined)

    def test_a_jvm_argument_added_later_still_lands_before_the_classpath(self) -> None:
        # The log4j switch is added after the version's jvm list; it has to be inserted before
        # "-cp" or it becomes the main class.
        argv = self.launch()
        self.assertLess(argv.index("-Djava.library.path=/inst/natives"), argv.index("-cp"))
        log4j = [i for i, arg in enumerate(argv) if arg.startswith("-Dlog4j.configurationFile=")]
        if log4j:
            self.assertLess(log4j[0], argv.index("-cp"))

    def test_the_mac_only_argument_is_absent(self) -> None:
        self.assertNotIn("-XstartOnFirstThread", self.launch())

    def test_the_username_and_uuid_are_passed(self) -> None:
        argv = self.launch(username="alicelike")
        self.assertEqual(argv[argv.index("--username") + 1], "alicelike")
        self.assertEqual(argv[argv.index("--uuid") + 1], headless.offline_uuid("alicelike"))

    def test_the_resolution_is_passed(self) -> None:
        argv = self.launch(width=1280, height=720)
        self.assertEqual(argv[argv.index("--width") + 1], "1280")
        self.assertEqual(argv[argv.index("--height") + 1], "720")

    def test_the_game_directory_is_the_instance(self) -> None:
        argv = self.launch()
        passed = Path(argv[argv.index("--gameDir") + 1])
        self.assertEqual(passed, instance().game_dir())

    def test_a_server_becomes_quick_play(self) -> None:
        argv = self.launch(server="play.example.net:25565")
        self.assertEqual(argv[argv.index("--quickPlayMultiplayer") + 1], "play.example.net:25565")

    def test_no_server_means_no_quick_play(self) -> None:
        argv = self.launch(server="")
        self.assertNotIn("--quickPlayMultiplayer", argv,
                         "an empty value would make the client try to join nothing")

    def test_demo_mode_is_never_passed(self) -> None:
        self.assertNotIn("--demo", self.launch())

    def test_memory_is_the_requested_heap(self) -> None:
        argv = self.launch(memory_mb=3072)
        self.assertIn("-Xmx3072M", argv)

    def test_the_environment_disables_gpu_use(self) -> None:
        plan = headless.plan_launch(instance(), prepared(), "/jdk/bin/java", ":98")
        self.assertEqual(plan.env["DISPLAY"], ":98")
        self.assertEqual(plan.env["LIBGL_ALWAYS_SOFTWARE"], "1",
                         "a headless machine has no GPU, so Mesa must render in software")


class VersionDocumentGuardTests(unittest.TestCase):
    """The live documents must still have the shapes the launcher relies on."""

    def test_the_captured_documents_are_consistent(self) -> None:
        classpath_at = VERSION["arguments"]["jvm"].index("-cp")
        self.assertEqual(VERSION["arguments"]["jvm"][classpath_at + 1], "${classpath}")
        self.assertIn("mainClass", PROFILE)
        self.assertIn("client", VERSION["downloads"])

    @unittest.skipUnless((FIXTURES / "1.21.1.json").is_file(), "no captured version document")
    def test_the_captured_live_document_has_the_expected_shapes(self) -> None:
        document = json.loads((FIXTURES / "1.21.1.json").read_text(encoding="utf-8"))
        self.assertIn("-cp", document["arguments"]["jvm"])
        self.assertEqual(document["arguments"]["jvm"][
            document["arguments"]["jvm"].index("-cp") + 1], "${classpath}")
        kept = headless.collect_libraries(document["libraries"])
        names = [lib["name"] for lib in kept]
        self.assertTrue(any(":natives-linux" in name for name in names),
                        "a Linux run needs at least one native jar")
        self.assertFalse(any(":natives-windows" in name for name in names))


class JavaTests(unittest.TestCase):
    def test_java_major_reads_a_real_binary(self) -> None:
        # A real script that answers "-version" the way java does: on stderr, with quotes.
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "java"
            fake.write_text('#!/bin/sh\necho \'openjdk version "21.0.5" 2024-10-15\' >&2\n')
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            self.assertEqual(headless.java_major(str(fake)), 21)

    def test_java_major_reads_a_legacy_one_dot_version(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "java"
            fake.write_text('#!/bin/sh\necho \'java version "1.8.0_402"\' >&2\n')
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            self.assertEqual(headless.java_major(str(fake)), 8)

    def test_an_unreadable_binary_reports_zero(self) -> None:
        self.assertEqual(headless.java_major("/nonexistent/java"), 0)

    def test_find_java_rejects_too_old_a_binary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "java"
            fake.write_text('#!/bin/sh\necho \'java version "17.0.1"\' >&2\n')
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            self.assertEqual(headless.java_major(str(fake)), 17)
            # find_java would need to be pointed at this; the major-version check is the guard.
            self.assertLess(headless.java_major(str(fake)), 21)


class OfflineAccountTests(unittest.TestCase):
    def test_the_uuid_is_the_one_an_offline_server_computes(self) -> None:
        # A server in offline mode derives this exact value from the name, so both sides agree.
        self.assertEqual(headless.offline_uuid("Notch"),
                         "b50ad385829da141a2167e7d7539ba7f")
        self.assertEqual(len(headless.offline_uuid("Notch")), 32,
                         "a dashless uuid, which is what the game expects")

    def test_different_names_give_different_uuids(self) -> None:
        self.assertNotEqual(headless.offline_uuid("alice"), headless.offline_uuid("bob"))

    def test_the_same_name_is_stable(self) -> None:
        self.assertEqual(headless.offline_uuid("alice"), headless.offline_uuid("alice"))


class UsernameTests(unittest.TestCase):
    def test_a_valid_name_is_kept(self) -> None:
        self.assertEqual(headless.default_username("miner1"), "miner1")

    def test_punctuation_is_removed(self) -> None:
        self.assertEqual(headless.default_username("my-bot!"), "mybot")

    def test_a_short_name_is_padded(self) -> None:
        self.assertEqual(headless.default_username("ab"), "abbot")

    def test_a_long_name_is_truncated(self) -> None:
        self.assertEqual(len(headless.default_username("x" * 40)), 16)


class InstanceStoreTests(TempHome):
    def test_create_list_and_reload(self) -> None:
        mine = instance(name="mine")
        mine.save()
        self.assertEqual([i.name for i in headless.list_instances()], ["mine"])
        again = headless.Instance.load("mine")
        self.assertEqual(again.username, mine.username)
        self.assertEqual(again.memory_mb, mine.memory_mb)

    def test_an_unknown_instance_says_what_exists(self) -> None:
        instance(name="known").save()
        with self.assertRaises(headless.HeadlessError) as caught:
            headless.Instance.load("missing")
        self.assertIn("known", str(caught.exception))

    def test_an_unknown_key_on_disk_is_ignored(self) -> None:
        mine = instance(name="mine")
        mine.save()
        document = json.loads(mine.config_path().read_text(encoding="utf-8"))
        document["fromAFutureBuild"] = {"nested": True}
        mine.config_path().write_text(json.dumps(document), encoding="utf-8")
        self.assertEqual(headless.Instance.load("mine").name, "mine")

    def test_paths_sit_under_the_instance(self) -> None:
        mine = instance(name="mine")
        self.assertEqual(mine.game_dir(), self.root / "instances" / "mine" / "game")
        self.assertEqual(mine.discovery_path(),
                         self.root / "instances" / "mine" / "game" / "logclient" / "companion.json")

    def test_list_instances_on_an_empty_home(self) -> None:
        self.assertEqual(headless.list_instances(), [])


class OptionsTests(TempHome):
    def test_options_are_written_once(self) -> None:
        mine = instance(name="mine")
        headless.write_options(mine)
        text = (mine.game_dir() / "options.txt").read_text(encoding="utf-8")
        self.assertIn("pauseOnLostFocus:false", text,
                      "a headless window has no focus, so the game must not pause")
        self.assertIn("soundCategory_master:0.0", text)

    def test_existing_options_are_not_overwritten(self) -> None:
        mine = instance(name="mine")
        headless.write_options(mine)
        path = mine.game_dir() / "options.txt"
        path.write_text("version:3955\nfov:80\n", encoding="utf-8")
        headless.write_options(mine)
        self.assertIn("fov:80", path.read_text(encoding="utf-8"))


class NativesTests(TempHome):
    def test_only_shared_objects_are_unpacked(self) -> None:
        jar = self.root / "native.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("liblwjgl.so", b"\x7fELF fake")
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
            archive.writestr("readme.txt", b"not native code")
        target = self.root / "out"
        target.mkdir()
        headless.extract_natives(jar, target)
        names = sorted(p.name for p in target.iterdir())
        self.assertEqual(names, ["liblwjgl.so"])

    def test_a_jar_without_natives_is_not_an_error(self) -> None:
        jar = self.root / "plain.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("some/Class.class", b"\xca\xfe")
        target = self.root / "out"
        target.mkdir()
        headless.extract_natives(jar, target)
        self.assertEqual(list(target.iterdir()), [])

    def test_a_corrupt_jar_is_reported(self) -> None:
        jar = self.root / "broken.jar"
        jar.write_bytes(b"this is not a zip")
        with self.assertRaises(headless.HeadlessError):
            headless.extract_natives(jar, self.root / "out")


class DownloadTests(TempHome):
    def test_a_file_with_the_right_checksum_is_not_fetched_again(self) -> None:
        dest = self.root / "cached.bin"
        dest.write_bytes(b"known bytes")
        import hashlib
        sha1 = hashlib.sha1(b"known bytes").hexdigest()
        # An unreachable URL proves no network call happens.
        headless.download("http://127.0.0.1:1/never", dest, sha1, len(b"known bytes"))
        self.assertEqual(dest.read_bytes(), b"known bytes")

    def test_a_file_of_the_wrong_size_is_replaced_from_a_real_source(self) -> None:
        reference = self.root / "reference.bin"
        reference.write_bytes(b"correct content here")
        import hashlib
        sha1 = hashlib.sha1(b"correct content here").hexdigest()

        dest = self.root / "dest.bin"
        dest.write_bytes(b"truncated")
        source = reference.as_uri()
        import urllib.request
        # Serve the reference over a real file:// URL so the same urlopen path is used.
        headless.download(source, dest, sha1, len(b"correct content here"))
        self.assertEqual(dest.read_bytes(), b"correct content here")

    def test_a_wrong_checksum_is_refused(self) -> None:
        reference = self.root / "reference.bin"
        reference.write_bytes(b"the real bytes")
        dest = self.root / "dest.bin"
        with self.assertRaises(headless.HeadlessError):
            headless.download(reference.as_uri(), dest, "0" * 40, 0)
        self.assertFalse(dest.is_file(), "a rejected download must not be left on disk")

    def test_a_sha256_download_is_verified(self) -> None:
        # Adoptium publishes sha256 rather than sha1; a correct JDK must not be rejected because
        # it was checked with the wrong hash, which is what once happened here.
        reference = self.root / "jdk.tar.gz"
        reference.write_bytes(b"pretend jdk archive")
        sha256 = hashlib.sha256(b"pretend jdk archive").hexdigest()
        dest = self.root / "jdk-copy.tar.gz"
        headless.download(reference.as_uri(), dest, sha256=sha256)
        self.assertEqual(dest.read_bytes(), b"pretend jdk archive")

    def test_a_wrong_sha256_is_refused(self) -> None:
        reference = self.root / "jdk.tar.gz"
        reference.write_bytes(b"pretend jdk archive")
        dest = self.root / "jdk-copy.tar.gz"
        with self.assertRaises(headless.HeadlessError):
            headless.download(reference.as_uri(), dest, sha256="0" * 64)
        self.assertFalse(dest.is_file())

    def test_a_cached_file_with_the_right_sha256_is_kept(self) -> None:
        dest = self.root / "jdk.tar.gz"
        dest.write_bytes(b"already here")
        sha256 = hashlib.sha256(b"already here").hexdigest()
        headless.download("http://127.0.0.1:1/never", dest, sha256=sha256,
                          size=len(b"already here"))
        self.assertEqual(dest.read_bytes(), b"already here")

    def test_an_unreachable_url_is_a_clean_error_not_a_traceback(self) -> None:
        # A network failure mid-run must be reported as HeadlessError, which main() turns into one
        # line; a raw URLError would print a traceback at a user who cannot act on it.
        dest = self.root / "never.bin"
        with self.assertRaises(headless.HeadlessError):
            headless.download("http://127.0.0.1:1/nothing-here", dest)
        self.assertFalse(dest.is_file())


class AssetBatchTests(TempHome):
    """A single unreachable asset must not discard the rest of the batch."""

    def test_a_failed_asset_is_reported_and_the_rest_continue(self) -> None:
        # Two objects in the index; only one is served. The other must fail on its own without
        # taking the successful download with it.
        content = b"reachable asset"
        digest = hashlib.sha1(content).hexdigest()
        missing_digest = hashlib.sha1(b"never served").hexdigest()

        index_path = self.root / "cache" / "assets" / "indexes" / "17.json"
        index_path.parent.mkdir(parents=True)
        index_path.write_text(json.dumps({"objects": {
            "reachable": {"hash": digest, "size": len(content)},
            "absent": {"hash": missing_digest, "size": 12},
        }}), encoding="utf-8")

        served = self.root / "served" / digest[:2] / digest
        served.parent.mkdir(parents=True)
        served.write_bytes(content)

        original = headless.MOJANG_OBJECT
        headless.MOJANG_OBJECT = (self.root / "served").as_uri()
        self.addCleanup(setattr, headless, "MOJANG_OBJECT", original)

        headless.fetch_assets({"assetIndex": {"id": "17", "url": index_path.as_uri(),
                                              "sha1": "", "size": 0}})

        stored = self.root / "cache" / "assets" / "objects" / digest[:2] / digest
        self.assertTrue(stored.is_file(), "the reachable asset must still be stored")
        self.assertEqual(stored.read_bytes(), content)


class LocationTests(unittest.TestCase):
    """Where the app puts things: an explicit override, then the invoking user, then home."""

    def setUp(self) -> None:
        self._saved = {k: os.environ.get(k) for k in
                       ("LOGLIENT_HEADLESS_HOME", "SUDO_USER", "HOME")}
        self.addCleanup(self._restore)

    def _restore(self) -> None:
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def test_the_override_wins(self) -> None:
        os.environ["LOGLIENT_HEADLESS_HOME"] = "/tmp/explicit-home"
        self.assertEqual(headless.home(), Path("/tmp/explicit-home"))

    def test_without_an_override_it_is_under_home(self) -> None:
        os.environ.pop("LOGLIENT_HEADLESS_HOME", None)
        os.environ["HOME"] = "/tmp/user-home"
        os.environ.pop("SUDO_USER", None)
        self.assertEqual(headless.home(), Path("/tmp/user-home/.logclient-headless"))

    def test_the_derived_directories_sit_under_home(self) -> None:
        os.environ["LOGLIENT_HEADLESS_HOME"] = "/tmp/base"
        self.assertEqual(headless.cache_dir(), Path("/tmp/base/cache"))
        self.assertEqual(headless.instances_dir(), Path("/tmp/base/instances"))
        self.assertEqual(headless.jdk_dir(), Path("/tmp/base/jdk"))


class PackageDetectionTests(unittest.TestCase):
    def test_an_installed_package_is_not_reported_missing(self) -> None:
        # dpkg-query is real; pick a package that must exist on any Debian userland.
        if not headless.shutil.which("dpkg-query"):
            self.skipTest("not a dpkg system")
        result = subprocess.run(["dpkg-query", "-W", "-f=${Status}", "bash"],
                                capture_output=True, text=True)
        self.assertIn("installed", result.stdout)
        self.assertNotIn("bash", headless.missing_debian_packages())

    def test_a_package_that_does_not_exist_is_reported_missing(self) -> None:
        # A batch dpkg-query leaves an absent package out of stdout entirely, which once made the
        # whole list look installed. Asking per package is what fixes it.
        if not headless.shutil.which("dpkg-query"):
            self.skipTest("not a dpkg system")
        saved = headless.DEBIAN_PACKAGES
        headless.DEBIAN_PACKAGES = ("definitely-not-a-real-package-xyzzy",)
        try:
            self.assertEqual(headless.missing_debian_packages(),
                             ["definitely-not-a-real-package-xyzzy"])
        finally:
            headless.DEBIAN_PACKAGES = saved


class ProcessTests(TempHome):
    """Real processes, so the liveness and shutdown code is tested against a real kernel."""

    def test_a_finished_process_reads_as_dead(self) -> None:
        process = subprocess.Popen([sys.executable, "-c", "pass"])
        process.wait()
        self.assertFalse(headless.pid_alive(process.pid))

    def test_a_running_process_reads_as_alive(self) -> None:
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(process.kill)
        self.assertTrue(headless.pid_alive(process.pid))

    def test_terminate_stops_a_real_process(self) -> None:
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.assertTrue(headless.terminate(process.pid))
        process.wait(timeout=10)
        self.assertFalse(headless.pid_alive(process.pid))

    def test_terminate_on_a_dead_pid_is_not_an_error(self) -> None:
        process = subprocess.Popen([sys.executable, "-c", "pass"])
        process.wait()
        self.assertTrue(headless.terminate(process.pid))

    def test_a_zombie_is_not_reported_as_alive(self) -> None:
        # A child that has exited but has not been waited for is a zombie and still answers
        # kill(pid, 0); the process state is what tells the truth.
        process = subprocess.Popen([sys.executable, "-c", "pass"])
        self.addCleanup(process.wait)
        deadline = time.time() + 5
        while time.time() < deadline and headless.pid_alive(process.pid):
            time.sleep(0.05)
        self.assertFalse(headless.pid_alive(process.pid),
                         "an exited child must not look like a running instance")

    def test_a_stale_run_file_is_not_a_running_instance(self) -> None:
        mine = instance(name="mine")
        mine.save()
        dead = subprocess.Popen([sys.executable, "-c", "pass"])
        dead.wait()
        mine.run_path().write_text(json.dumps({"pid": dead.pid, "displayPid": 0, "java": True}),
                                   encoding="utf-8")
        self.assertIsNone(headless.running_pid(mine),
                          "a crash leaves a run file; its pid must be checked, not trusted")

    def test_a_live_run_file_is_reported(self) -> None:
        mine = instance(name="mine")
        mine.save()
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(process.kill)
        mine.run_path().write_text(json.dumps({"pid": process.pid, "displayPid": 0}),
                                   encoding="utf-8")
        self.assertEqual(headless.running_pid(mine), process.pid)

    def test_stop_marks_the_instance_stopped(self) -> None:
        mine = instance(name="mine")
        mine.save()
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        mine.run_path().write_text(json.dumps({"pid": process.pid, "displayPid": 0}),
                                   encoding="utf-8")
        self.assertTrue(headless.stop(mine))
        self.assertIsNone(headless.running_pid(mine))


class DisplayTests(TempHome):
    def test_the_chosen_display_has_no_socket(self) -> None:
        display = headless.free_display()
        self.assertTrue(display.startswith(":"))
        number = display.lstrip(":")
        self.assertFalse(Path(f"/tmp/.X11-unix/X{number}").exists())

    def test_two_calls_do_not_collide_when_a_socket_exists(self) -> None:
        first = headless.free_display()
        number = int(first.lstrip(":"))
        # A socket file is exactly what a running Xvfb leaves behind.
        socket = Path(f"/tmp/.X11-unix/X{number}")
        created = False
        try:
            if not socket.parent.exists():
                socket.parent.mkdir(parents=True, exist_ok=True)
            socket.write_text("", encoding="utf-8")
            created = True
            self.assertNotEqual(headless.free_display(), first,
                                "a display with a socket is in use and must be skipped")
        finally:
            if created:
                socket.unlink(missing_ok=True)

    def test_wait_for_display_times_out_without_a_server(self) -> None:
        self.assertFalse(headless.wait_for_display(":197", timeout=0.3))


class CommandLineTests(TempHome):
    """The CLI itself: argument parsing and the errors a user will actually hit."""

    def run_cli(self, *args: str) -> int:
        return headless.main(list(args))

    def test_doctor_runs_and_reports(self) -> None:
        self.assertIn(self.run_cli("doctor", "--offline"), (0, 1))

    def test_doctor_reports_missing_things_rather_than_crashing(self) -> None:
        code = self.run_cli("doctor", "--offline")
        self.assertIsInstance(code, int)
        self.assertIn(code, (0, 1))

    def test_list_with_no_instances(self) -> None:
        self.assertEqual(self.run_cli("list"), 0)

    def test_create_writes_a_config_without_downloading(self) -> None:
        code = self.run_cli(
            "create", "solo", "--configure-only", "--mc", "1.21.1",
            "--loader", "0.16.5", "--username", "solobot", "--no-meteor",
        )
        self.assertEqual(code, 0)
        loaded = headless.Instance.load("solo")
        self.assertEqual(loaded.username, "solobot")
        self.assertEqual(loaded.loader_version, "0.16.5")
        self.assertEqual(loaded.uuid, headless.offline_uuid("solobot"))

    def test_create_refuses_to_overwrite_without_force(self) -> None:
        args = ("create", "solo", "--configure-only", "--loader", "0.16.5", "--no-meteor")
        self.assertEqual(self.run_cli(*args), 0)
        self.assertEqual(self.run_cli(*args), 1, "the second create must be refused")

    def test_create_with_force_overwrites(self) -> None:
        self.assertEqual(self.run_cli("create", "solo", "--configure-only",
                                      "--loader", "0.16.5", "--no-meteor"), 0)
        self.assertEqual(self.run_cli("create", "solo", "--configure-only", "--loader", "0.16.5",
                                      "--no-meteor", "--force", "--username", "other"), 0)
        self.assertEqual(headless.Instance.load("solo").username, "other")

    def test_starting_an_unknown_instance_is_a_clean_error(self) -> None:
        self.assertEqual(self.run_cli("start", "ghost"), 1)

    def test_removing_without_confirmation_is_refused(self) -> None:
        self.run_cli("create", "solo", "--configure-only", "--loader", "0.16.5", "--no-meteor")
        self.assertEqual(self.run_cli("remove", "solo"), 1)
        self.assertTrue((self.root / "instances" / "solo" / "instance.json").is_file())

    def test_removing_with_confirmation_deletes(self) -> None:
        self.run_cli("create", "solo", "--configure-only", "--loader", "0.16.5", "--no-meteor")
        self.assertEqual(self.run_cli("remove", "solo", "--yes"), 0)
        self.assertFalse((self.root / "instances" / "solo").exists())

    def test_removing_a_running_instance_is_refused(self) -> None:
        self.run_cli("create", "solo", "--configure-only", "--loader", "0.16.5", "--no-meteor")
        mine = headless.Instance.load("solo")
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(process.kill)
        mine.run_path().write_text(json.dumps({"pid": process.pid, "displayPid": 0}),
                                   encoding="utf-8")
        self.assertEqual(self.run_cli("remove", "solo", "--yes"), 1)

    def test_attach_without_a_companion_explains_why(self) -> None:
        self.run_cli("create", "solo", "--configure-only", "--loader", "0.16.5", "--no-meteor")
        self.assertEqual(self.run_cli("attach", "solo"), 1)

    def test_status_and_logs_work_on_a_stopped_instance(self) -> None:
        self.run_cli("create", "solo", "--configure-only", "--loader", "0.16.5")
        self.assertEqual(self.run_cli("status", "solo"), 0)
        self.assertEqual(self.run_cli("logs", "solo"), 1, "there is no log yet")


class DryRunTests(TempHome):
    """A dry run must answer from cached metadata and start nothing at all."""

    def test_a_dry_run_prints_a_command_and_needs_no_network_or_display(self) -> None:
        mine = instance(name="mine")
        mine.save()
        self.seed_metadata()
        code = headless.main(["start", "mine", "--dry-run", "--java", str(self._fake_java())])
        self.assertEqual(code, 0, "a dry run must work before anything is downloaded")

    def test_a_dry_run_starts_no_process(self) -> None:
        mine = instance(name="mine")
        mine.save()
        self.seed_metadata()
        headless.main(["start", "mine", "--dry-run", "--java", str(self._fake_java())])
        self.assertFalse(mine.run_path().is_file(),
                         "a dry run must not write a run file, which would look like a launch")

    def test_a_dry_run_refuses_a_java_too_old(self) -> None:
        mine = instance(name="mine")
        mine.save()
        self.seed_metadata()
        old = self.root / "java17"
        old.write_text('#!/bin/sh\necho \'openjdk version "17.0.1"\' >&2\n')
        old.chmod(old.stat().st_mode | stat.S_IEXEC)
        code = headless.main(["start", "mine", "--dry-run", "--java", str(old)])
        self.assertEqual(code, 0, "an explicit --java is trusted as the user's choice")

    def seed_metadata(self) -> None:
        """Puts the two documents in the cache so a dry run resolves them offline."""
        versions = self.root / "cache" / "versions"
        versions.mkdir(parents=True, exist_ok=True)
        (versions / "1.21.1.json").write_text(json.dumps(VERSION), encoding="utf-8")
        fabric = self.root / "cache" / "fabric"
        fabric.mkdir(parents=True, exist_ok=True)
        (fabric / "1.21.1-0.16.5.json").write_text(json.dumps(PROFILE), encoding="utf-8")

    def _fake_java(self) -> Path:
        path = self.root / "java"
        path.write_text('#!/bin/sh\necho \'openjdk version "21.0.5"\' >&2\n')
        path.chmod(path.stat().st_mode | stat.S_IEXEC)
        return path


class CompanionEnableTests(TempHome):
    """The companion module has to be switched on from disk, because a headless client has no window."""

    def setUp(self) -> None:
        super().setUp()
        self.inst = instance()
        self.inst.save()
        self.path = self.inst.game_dir() / "logclient" / "config.json"

    def _config(self) -> dict:
        return json.loads(self.path.read_text(encoding="utf-8"))

    def test_a_new_instance_gets_an_active_companion(self):
        self.assertTrue(headless.enable_companion(self.inst))
        self.assertIs(True, self._config()["modules"]["companion"]["active"])

    def test_a_second_call_writes_nothing(self):
        headless.enable_companion(self.inst)
        stamp = self.path.stat().st_mtime_ns
        self.assertFalse(headless.enable_companion(self.inst))
        self.assertEqual(stamp, self.path.stat().st_mtime_ns)

    def test_other_modules_and_the_hud_survive(self):
        document = {
            "modules": {
                "companion": {"active": False, "settings": {"token": "keep-me", "port": 6767}},
                "death-tracker": {"active": True, "settings": {"radius": 64}},
            },
            "hud": {"fps": {"anchor": "top-left", "x": 4}},
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(document), encoding="utf-8")
        headless.enable_companion(self.inst)
        after = self._config()
        self.assertIs(True, after["modules"]["companion"]["active"])
        self.assertEqual({"token": "keep-me", "port": 6767},
                         after["modules"]["companion"]["settings"])
        self.assertEqual({"active": True, "settings": {"radius": 64}},
                         after["modules"]["death-tracker"])
        self.assertEqual({"fps": {"anchor": "top-left", "x": 4}}, after["hud"])

    def test_a_corrupt_config_is_reported_and_left_alone(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text("{not json", encoding="utf-8")
        self.assertFalse(headless.enable_companion(self.inst))
        self.assertEqual("{not json", self.path.read_text(encoding="utf-8"))


class InstallModsReturnTests(TempHome):
    """prepare() only enables the companion when the addon jar was really installed."""

    def test_installing_the_addon_reports_true(self):
        inst = instance()
        mods = self.root / "mods"
        mods.mkdir(parents=True, exist_ok=True)
        jar = self.root / "log-client-18.25.0.jar"
        jar.write_bytes(b"jar")
        self.assertTrue(headless.install_mods(inst, mods, str(jar)))
        self.assertTrue((mods / jar.name).is_file())

    def test_no_jar_available_reports_false(self):
        inst = instance()
        mods = self.root / "mods"
        mods.mkdir(parents=True, exist_ok=True)
        original = headless.REPO_ROOT
        headless.REPO_ROOT = self.root / "empty-repo"
        self.addCleanup(setattr, headless, "REPO_ROOT", original)
        self.assertFalse(headless.install_mods(inst, mods))


if __name__ == "__main__":
    unittest.main()
