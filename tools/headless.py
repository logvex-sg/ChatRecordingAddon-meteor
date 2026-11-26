#!/usr/bin/env python3
"""Run Log Client in headless Minecraft instances on Debian.

This is a separate application from the game and from the companion app. It does not need
Minecraft installed, and it does not need a launcher: it resolves the official Mojang version
manifest, the Fabric loader profile and the mod jars itself, then starts each instance under its
own virtual X display so it can run on a machine with no monitor.

    python3 tools/headless.py doctor              # what is missing on this machine
    sudo python3 tools/headless.py setup         # install the Debian packages and a JDK 21
    python3 tools/headless.py create mine        # download the client, libraries and assets
    python3 tools/headless.py start mine         # start it under Xvfb
    python3 tools/headless.py list               # what is running
    python3 tools/headless.py attach mine        # open the terminal dashboard on it

Everything an instance needs lives under one directory, by default ``~/.logclient-headless``:

    jdk/                     a JDK 21 downloaded from Adoptium (only if setup ran)
    cache/                   the client jar, libraries, assets and metadata, shared by instances
    instances/<name>/game/   the game directory: mods, config, logs, the companion discovery file
    instances/<name>/logs/   the console output of each run

Two things this app deliberately cannot do, because no launcher can do them from a shell:

    * It cannot sign in to a Microsoft account. Instances run an offline account, which is right
      for singleplayer, a LAN world or a server you run in offline-mode, and wrong for any server
      that verifies accounts against Mojang.
    * It cannot grant a server's permission checks for you. An instance joins as the username you
      give it, so pick one the server allows.

The companion module still has to be switched on once per instance. The mod only starts the
server while its module is active, and Meteor stores that as binary NBT; writing that file from
Python would be a fragile guess at another project's format. Turn it on in game once (or set a
Meteor module keybind) and the discovery file appears under ``game/logclient/companion.json``,
which is exactly what ``attach`` reads.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import platform
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable, Optional

APP_NAME = "Log Client headless"
VERSION = "1.0.0"

DEFAULT_MC = "1.21.1"
DEFAULT_LOADER = "0.16.5"
DEFAULT_MEMORY_MB = 2048
DEFAULT_WIDTH = 854
DEFAULT_HEIGHT = 480

VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
MOJANG_OBJECT = "https://resources.download.minecraft.net"
FABRIC_LOADER_META = "https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json"
FABRIC_LATEST_LOADER = "https://meta.fabricmc.net/v2/versions/loader/{mc}"
ADOPTIUM_JDK = (
    "https://api.adoptium.net/v3/assets/latest/{major}/hotspot"
    "?architecture={arch}&image_type=jdk&os={os}&vendor=eclipse"
)
METEOR_JAR = (
    "https://maven.meteordev.org/releases/meteordevelopment/meteor-client/"
    "{version}/meteor-client-{version}.jar"
)

# Packages a headless client needs on Debian. The GL drivers matter: with no GPU the client runs
# on Mesa's software rasteriser, which is slow but real, and is the only option on a server.
DEBIAN_PACKAGES = (
    "xvfb",
    "libgl1",
    "libglx-mesa0",
    "libgl1-mesa-dri",
    "libx11-6",
    "libxext6",
    "libxcursor1",
    "libxrandr2",
    "libxi6",
    "libxxf86vm1",
    "libxinerama1",
    "libasound2t64",
    "ca-certificates",
)

TIMEOUT_SECONDS = 30.0
CHUNK = 1 << 16


class HeadlessError(RuntimeError):
    """A problem the user can act on, reported without a traceback."""


# --------------------------------------------------------------------------------------------
# Locations
# --------------------------------------------------------------------------------------------


def home() -> Path:
    """The root of everything this app owns. ``LOGLIENT_HEADLESS_HOME`` overrides it.

    ``setup`` must run under sudo, but the instances belong to the user who invoked it: using
    ``SUDO_USER``'s home keeps the download under that user's directory instead of root's, and
    keeps the files owned by the person who will actually run the instances.
    """
    override = os.environ.get("LOGLIENT_HEADLESS_HOME")
    if override:
        return Path(override).expanduser()
    sudo_user = os.environ.get("SUDO_USER")
    if sudo_user and os.geteuid() == 0:
        try:
            import pwd

            return Path(pwd.getpwnam(sudo_user).pw_dir) / ".logclient-headless"
        except KeyError:
            pass
    return Path.home() / ".logclient-headless"


REPO_ROOT = Path(__file__).resolve().parent.parent


def cache_dir() -> Path:
    return home() / "cache"


def instances_dir() -> Path:
    return home() / "instances"


def jdk_dir() -> Path:
    return home() / "jdk"


# --------------------------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------------------------


def log(message: str) -> None:
    print(message, flush=True)


def short_sha(text: str) -> str:
    return text[:8] if text else ""


def http_json(url: str) -> Any:
    """Reads a JSON document, retrying once: these endpoints are flaky by reputation, not design."""
    last: Optional[Exception] = None
    for attempt in range(2):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": f"{APP_NAME}/{VERSION}"})
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, OSError, ValueError) as error:
            last = error
            if attempt == 0:
                time.sleep(1.0)
    raise HeadlessError(f"Could not read {url}: {last}")


def file_sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, dest: Path, sha1: str = "", size: int = 0, sha256: str = "") -> Path:
    """Downloads to ``dest`` unless it is already the right file.

    The write goes to a temporary file and is renamed on success, so an interrupted download never
    leaves a half file that a later run would trust because its name matched. Either digest may be
    given; Adoptium publishes sha256 where Mojang publishes sha1, and both are verified the same way.
    """
    def matches(path: Path) -> bool:
        if size and path.stat().st_size != size:
            return False
        if sha256 and hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
            return False
        if sha1 and file_sha1(path) != sha1:
            return False
        return True

    if dest.is_file():
        if not size and not sha1 and not sha256:
            return dest
        if matches(dest):
            return dest

    dest.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(delete=False, dir=str(dest.parent), prefix=".part-")
    try:
        for attempt in range(3):
            try:
                with urllib.request.urlopen(
                    urllib.request.Request(url, headers={"User-Agent": f"{APP_NAME}/{VERSION}"}),
                    timeout=TIMEOUT_SECONDS,
                ) as response, open(handle.name, "wb") as out:
                    shutil.copyfileobj(response, out, CHUNK)
                break
            except (urllib.error.URLError, OSError) as error:
                # A download that times out mid-run must not escape as a traceback: it is an
                # ordinary thing on a busy network, and the caller decides whether to give up.
                if attempt == 2:
                    raise HeadlessError(f"Could not download {url}: {error}") from error
                time.sleep(0.5 * (attempt + 1))
        if not matches(Path(handle.name)):
            raise HeadlessError(f"{dest.name} downloaded with the wrong checksum")
        os.replace(handle.name, dest)
    finally:
        if os.path.exists(handle.name):
            os.unlink(handle.name)
    return dest


# --------------------------------------------------------------------------------------------
# Platform, and Mojang's rule system
# --------------------------------------------------------------------------------------------


def os_name() -> str:
    """Mojang's name for this platform. Anything that is not Windows or macOS is ``linux``."""
    system = platform.system().lower()
    if system == "darwin":
        return "osx"
    if system == "windows":
        return "windows"
    return "linux"


def os_arch() -> str:
    """Mojang's name for this CPU, either ``x86`` or ``arm64``."""
    machine = platform.machine().lower()
    if machine in ("aarch64", "arm64"):
        return "arm64"
    return "x86"


def rule_allows(rules: Optional[list], name: str = "", arch: str = "",
                features: Optional[dict] = None) -> bool:
    """Applies Mojang's allow/deny rule list to this platform.

    The semantics are not "any allow wins": rules are read in order, each matching rule sets the
    verdict, and a list with no allow rule denies. Getting this backwards is what makes launchers
    put a macOS-only jar on a Linux classpath and then fail with a missing main class.
    """
    if not rules:
        return True
    name = name or os_name()
    arch = arch or os_arch()
    features = features or {}
    allowed = False
    for rule in rules:
        if not _rule_matches(rule, name, arch, features):
            continue
        allowed = rule.get("action") == "allow"
    return allowed


def _rule_matches(rule: dict, name: str, arch: str, features: dict) -> bool:
    os_rule = rule.get("os")
    if os_rule:
        if "name" in os_rule and os_rule["name"] != name:
            return False
        if "arch" in os_rule and os_rule["arch"] != arch:
            return False
    wanted = rule.get("features")
    if wanted:
        for key, value in wanted.items():
            if bool(features.get(key)) != bool(value):
                return False
    return True


def expand(value: Any, variables: dict) -> list:
    """Replaces ``${name}`` placeholders and flattens the nested lists Mojang uses."""
    if isinstance(value, str):
        for key, replacement in variables.items():
            value = value.replace("${" + key + "}", str(replacement))
        return [value]
    if isinstance(value, list):
        out: list = []
        for item in value:
            out.extend(expand(item, variables))
        return out
    return []


def collect_args(entries: Optional[list], variables: dict,
                 features: Optional[dict] = None) -> list:
    """Turns a Mojang ``arguments`` array into a flat argument list."""
    out: list = []
    for entry in entries or []:
        if isinstance(entry, dict):
            if rule_allows(entry.get("rules"), features=features):
                out.extend(expand(entry.get("value"), variables))
        else:
            out.extend(expand(entry, variables))
    return out


def collect_libraries(stacks: Iterable[dict], features: Optional[dict] = None) -> list:
    """The library entries that apply here, in the order they were given."""
    return [lib for lib in stacks if rule_allows(lib.get("rules"), features=features)]


def maven_path(name: str) -> str:
    """``org.lwjgl:lwjgl:3.3.3`` to ``org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar``."""
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else ""
    suffix = f"-{classifier}" if classifier else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{suffix}.jar"


def library_url(lib: dict) -> tuple:
    """The download location and checksum for one library.

    Mojang's own libraries carry a full ``downloads.artifact``; Fabric's profile only carries a
    maven base URL, so the path is rebuilt from the coordinates.
    """
    artifact = (lib.get("downloads") or {}).get("artifact")
    if artifact and artifact.get("url"):
        return artifact["url"], artifact.get("sha1", ""), int(artifact.get("size") or 0)
    base = lib.get("url")
    if not base:
        return "", "", 0
    return base.rstrip("/") + "/" + maven_path(lib["name"]), "", 0


# --------------------------------------------------------------------------------------------
# Metadata: the Mojang manifest, the version document, the Fabric profile
# --------------------------------------------------------------------------------------------


def version_document(mc_version: str) -> dict:
    """The Mojang document for one version, cached on disk."""
    cached = cache_dir() / "versions" / f"{mc_version}.json"
    if cached.is_file():
        try:
            return json.loads(cached.read_text(encoding="utf-8"))
        except ValueError:
            pass

    manifest = http_json(VERSION_MANIFEST)
    entry = next((v for v in manifest.get("versions", []) if v.get("id") == mc_version), None)
    if entry is None:
        available = ", ".join(v["id"] for v in manifest.get("versions", [])[:8])
        raise HeadlessError(
            f"Minecraft {mc_version} is not in the version manifest. Recent versions: {available}"
        )
    document = http_json(entry["url"])
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(document, indent=1), encoding="utf-8")
    return document


def fabric_profile(mc_version: str, loader_version: str) -> dict:
    """The Fabric loader profile for one version pair, cached on disk."""
    cached = cache_dir() / "fabric" / f"{mc_version}-{loader_version}.json"
    if cached.is_file():
        try:
            return json.loads(cached.read_text(encoding="utf-8"))
        except ValueError:
            pass
    document = http_json(FABRIC_LOADER_META.format(mc=mc_version, loader=loader_version))
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(document, indent=1), encoding="utf-8")
    return document


def latest_loader(mc_version: str) -> str:
    """The newest stable Fabric loader for a Minecraft version."""
    entries = http_json(FABRIC_LATEST_LOADER.format(mc=mc_version))
    for entry in entries:
        if entry.get("loader", {}).get("stable"):
            return entry["loader"]["version"]
    if entries:
        return entries[0]["loader"]["version"]
    raise HeadlessError(f"No Fabric loader is published for Minecraft {mc_version}")


# --------------------------------------------------------------------------------------------
# Java
# --------------------------------------------------------------------------------------------


def java_major(java: str) -> int:
    """The feature version of a ``java`` binary, or 0 when it cannot be read."""
    try:
        result = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return 0
    text = (result.stderr or "") + (result.stdout or "")
    for token in text.replace('"', " ").split():
        if token[:2] in ("1.", "2.") and token[2:3].isdigit():
            return int(token[2:].split(".")[0])
        if token[:1].isdigit() and "." in token:
            try:
                return int(token.split(".")[0])
            except ValueError:
                continue
    return 0


def find_java(required: int = 21) -> Optional[str]:
    """A Java binary new enough for the game: the provisioned one first, then ``$JAVA_HOME``."""
    candidates = [jdk_dir() / "bin" / "java"]
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java")
    found = shutil.which("java")
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file() and java_major(str(candidate)) >= required:
            return str(candidate)
    return None


def provision_jdk(major: int = 21) -> str:
    """Downloads a JDK from Adoptium into the app directory and returns its ``java``."""
    arch = "aarch64" if os_arch() == "arm64" else "x64"
    assets = http_json(ADOPTIUM_JDK.format(major=major, arch=arch, os="linux"))
    if not assets:
        raise HeadlessError(f"Adoptium has no JDK {major} for linux/{arch}")
    package = assets[0]["binary"]["package"]
    archive = cache_dir() / f"jdk{major}.tar.gz"
    log(f"Downloading JDK {major} from Adoptium ({assets[0]['version']['semver']}) ...")
    download(package["link"], archive, sha256=package.get("checksum", ""))

    target = jdk_dir()
    if target.exists():
        shutil.rmtree(target)
    staging = target.parent / (target.name + ".tmp")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as tar:
        tar.extractall(staging, filter="data")
    roots = [p for p in staging.iterdir() if p.is_dir()]
    if not roots:
        raise HeadlessError("The JDK archive had no top-level directory")
    os.replace(roots[0], target)
    shutil.rmtree(staging, ignore_errors=True)

    java = target / "bin" / "java"
    if not java.is_file():
        raise HeadlessError(f"The JDK unpacked without a java binary at {java}")
    return str(java)


# --------------------------------------------------------------------------------------------
# An instance
# --------------------------------------------------------------------------------------------


@dataclass
class Instance:
    """One headless client. Serialised to ``instances/<name>/instance.json``."""

    name: str
    mc_version: str = DEFAULT_MC
    loader_version: str = DEFAULT_LOADER
    memory_mb: int = DEFAULT_MEMORY_MB
    width: int = DEFAULT_WIDTH
    height: int = DEFAULT_HEIGHT
    username: str = ""
    uuid: str = ""
    server: str = ""
    with_meteor: bool = True
    meteor_version: str = "0.5.8"
    mod_jars: list = field(default_factory=list)
    created_at: float = 0.0

    def root(self) -> Path:
        return instances_dir() / self.name

    def game_dir(self) -> Path:
        return self.root() / "game"

    def natives_dir(self) -> Path:
        return self.root() / "natives"

    def logs_dir(self) -> Path:
        return self.root() / "logs"

    def config_path(self) -> Path:
        return self.root() / "instance.json"

    def run_path(self) -> Path:
        return self.root() / "run.json"

    def discovery_path(self) -> Path:
        return self.game_dir() / "logclient" / "companion.json"

    def save(self) -> None:
        # The uuid is derived, so it is recomputed here rather than trusted from disk: a username
        # changed by hand or by a previous version must not leave a mismatched identity behind.
        self.uuid = offline_uuid(self.username or default_username(self.name))
        self.root().mkdir(parents=True, exist_ok=True)
        self.config_path().write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")

    @staticmethod
    def load(name: str) -> "Instance":
        path = instances_dir() / name / "instance.json"
        if not path.is_file():
            names = ", ".join(i.name for i in list_instances()) or "none yet"
            raise HeadlessError(f"There is no instance called '{name}'. Instances: {names}")
        document = json.loads(path.read_text(encoding="utf-8"))
        known = {f for f in Instance.__dataclass_fields__}
        return Instance(**{k: v for k, v in document.items() if k in known})


def list_instances() -> list:
    """Every instance on disk, by name."""
    base = instances_dir()
    if not base.is_dir():
        return []
    out = []
    for child in sorted(base.iterdir()):
        if (child / "instance.json").is_file():
            try:
                out.append(Instance.load(child.name))
            except (HeadlessError, ValueError):
                continue
    return out


def default_username(name: str) -> str:
    """A username Minecraft accepts: 3-16 characters, letters, digits and underscore."""
    cleaned = "".join(c for c in name if c.isalnum() or c == "_")
    if len(cleaned) < 3:
        cleaned = (cleaned + "bot")[:16]
    return cleaned[:16]


def offline_uuid(username: str) -> str:
    """The dashless UUID an offline server computes for a name, so both sides agree."""
    digest = hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest()
    return "".join(f"{b:02x}" for b in digest)


# --------------------------------------------------------------------------------------------
# Preparing an instance: client, libraries, natives, assets, mods
# --------------------------------------------------------------------------------------------


def prepare(instance: Instance, skip_assets: bool = False, log_client_jar: str = "") -> dict:
    """Downloads everything the instance needs and returns the metadata for a launch."""
    cache = cache_dir()
    log(f"Resolving Minecraft {instance.mc_version} ...")
    version = version_document(instance.mc_version)
    profile = fabric_profile(instance.mc_version, instance.loader_version)

    client = version["downloads"]["client"]
    client_jar = cache / "client" / f"{instance.mc_version}.jar"
    if not client_jar.is_file():
        log(f"Downloading the {instance.mc_version} client ({client['size'] // 1048576} MB) ...")
    download(client["url"], client_jar, client.get("sha1", ""), int(client.get("size") or 0))

    library_entries = collect_libraries(version.get("libraries", []) + profile.get("libraries", []))
    classpath: list = []
    natives: list = []
    for lib in library_entries:
        url, sha1, size = library_url(lib)
        if not url:
            continue
        path = cache / "libraries" / maven_path(lib["name"])
        download(url, path, sha1, size)
        classpath.append(path)
        is_native = ":natives-" in lib["name"] or (lib.get("downloads") or {}).get("classifiers")
        if is_native:
            natives.append(path)

    native_dir = instance.natives_dir()
    if native_dir.exists():
        shutil.rmtree(native_dir)
    native_dir.mkdir(parents=True, exist_ok=True)
    for jar in natives:
        extract_natives(jar, native_dir)

    asset_index_path = Path("")
    if not skip_assets:
        asset_index_path = fetch_assets(version)

    mods_dir = instance.game_dir() / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    if install_mods(instance, mods_dir, log_client_jar):
        enable_companion(instance)

    write_options(instance)

    log4j_config = fetch_log4j(version)

    return {
        "version": version,
        "profile": profile,
        "client_jar": str(client_jar),
        "classpath": [str(p) for p in classpath],
        "asset_index": str(asset_index_path) if asset_index_path else "",
        "natives": str(native_dir),
        "log4j_config": log4j_config,
    }


def fetch_log4j(version: dict) -> str:
    """Downloads the version's log4j configuration and returns its path, or '' if unavailable."""
    entry = (version.get("logging") or {}).get("client") or {}
    file_info = entry.get("file")
    if not file_info:
        return ""
    config = cache_dir() / "log4j" / file_info["id"]
    try:
        download(file_info["url"], config, file_info.get("sha1", ""),
                 int(file_info.get("size") or 0))
        return str(config)
    except HeadlessError as error:
        log(f"Could not fetch the log4j configuration ({error}); continuing without it.")
        return ""


def plan_prepared(instance: Instance) -> dict:
    """The shape ``plan_launch`` needs, built from metadata and cache paths only.

    This is what ``--dry-run`` uses: it resolves the documents (cached after the first fetch) and
    computes where every file would go, but downloads nothing and creates no directories.
    """
    version = version_document(instance.mc_version)
    profile = fabric_profile(instance.mc_version, instance.loader_version)
    cache = cache_dir()
    classpath = []
    for lib in collect_libraries(version.get("libraries", []) + profile.get("libraries", [])):
        if not library_url(lib)[0]:
            continue
        classpath.append(cache / "libraries" / maven_path(lib["name"]))
    return {
        "version": version,
        "profile": profile,
        "client_jar": str(cache / "client" / f"{instance.mc_version}.jar"),
        "classpath": [str(path) for path in classpath],
        "asset_index": str(cache / "assets" / "indexes" / f"{version['assetIndex']['id']}.json"),
        "natives": str(instance.natives_dir()),
        "log4j_config": "",
    }


def extract_natives(jar: Path, target: Path) -> None:
    """Unpacks the shared objects from a ``natives-*`` jar.

    LWJGL ships native code as ordinary jars, and the game expects the libraries loose on
    ``java.library.path``. A jar that holds no native code is not an error.
    """
    import zipfile

    try:
        with zipfile.ZipFile(jar) as archive:
            for member in archive.namelist():
                if member.endswith("/"):
                    continue
                if not member.endswith((".so", ".dll", ".dylib", ".jnilib")):
                    continue
                name = os.path.basename(member)
                if member.startswith(("META-INF", "meta-inf")):
                    continue
                with archive.open(member) as source:
                    (target / name).write_bytes(source.read())
    except (zipfile.BadZipFile, OSError) as error:
        raise HeadlessError(f"Could not unpack natives from {jar.name}: {error}") from error


def fetch_assets(version: dict) -> Path:
    """Downloads the asset index and every object it names, and returns the index path."""
    index_info = version["assetIndex"]
    index_path = cache_dir() / "assets" / "indexes" / f"{index_info['id']}.json"
    download(index_info["url"], index_path, index_info.get("sha1", ""),
             int(index_info.get("size") or 0))
    index = json.loads(index_path.read_text(encoding="utf-8"))
    objects = index.get("objects", {})

    objects_root = cache_dir() / "assets" / "objects"

    def fetch(item: tuple) -> Optional[str]:
        """Downloads one asset. Returns its name when it failed, so the run can report it."""
        name, entry = item
        digest = entry["hash"]
        dest = objects_root / digest[:2] / digest
        if dest.is_file():
            return None
        try:
            download(MOJANG_OBJECT + f"/{digest[:2]}/{digest}", dest, digest,
                     int(entry.get("size") or 0))
        except HeadlessError:
            return name
        return None

    pending = [(name, entry) for name, entry in objects.items()]
    log(f"Checking {len(pending)} assets ...")
    downloaded = 0
    failures: list = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for failed in pool.map(fetch, pending):
            if failed:
                failures.append(failed)
            else:
                downloaded += 1
    # One unreachable asset is not a reason to throw away a run of four thousand; the client
    # falls back on a missing sound or texture, so the gap is reported and the run continues.
    if downloaded:
        log(f"Downloaded {downloaded} assets.")
    if failures:
        log(f"{len(failures)} asset(s) could not be downloaded, e.g. {failures[0]}. "
            "Re-run create to retry them.")
    return index_path


def install_mods(instance: Instance, mods_dir: Path, log_client_jar: str = "") -> bool:
    """Places Meteor and the Log Client jar into the instance's mods folder.

    Returns True when the addon jar was installed, because the companion config only makes sense
    for an instance that actually runs the mod.
    """
    if instance.with_meteor:
        target = mods_dir / f"meteor-client-{instance.meteor_version}.jar"
        download(METEOR_JAR.format(version=instance.meteor_version), target)

    source: Optional[Path] = None
    if log_client_jar:
        source = Path(log_client_jar).expanduser()
    else:
        built = REPO_ROOT / "build" / "libs"
        if built.is_dir():
            candidates = sorted(built.glob("log-client-*.jar"))
            plain = [c for c in candidates if "sources" not in c.name]
            if plain:
                source = plain[-1]
    if source:
        if not source.is_file():
            raise HeadlessError(f"No Log Client jar at {source}")
        shutil.copy2(source, mods_dir / source.name)
        log(f"Installed {source.name}")
        return True
    log("No Log Client jar found; the instance will run Meteor without the addon.")
    return False


def write_options(instance: Instance) -> None:
    """Writes a minimal ``options.txt`` so a headless run does not open the tutorial or pause."""
    options = instance.game_dir() / "options.txt"
    if options.is_file():
        return
    options.parent.mkdir(parents=True, exist_ok=True)
    options.write_text(
        "\n".join(
            [
                "version:3955",
                "pauseOnLostFocus:false",
                "onboardAccessibility:false",
                "narrator:0",
                "guiScale:2",
                "maxFps:60",
                "renderDistance:6",
                "soundCategory_master:0.0",
                "",
            ]
        ),
        encoding="utf-8",
    )


# --------------------------------------------------------------------------------------------
# Building the launch
# --------------------------------------------------------------------------------------------


@dataclass
class LaunchPlan:
    """Everything needed to start one instance, separated from actually starting it."""

    argv: list
    env: dict
    cwd: str
    log_path: str


def plan_launch(instance: Instance, prepared: dict, java: str,
                display: str = ":0") -> LaunchPlan:
    """Builds the exact command line from the version document and the Fabric profile.

    Kept free of side effects so the result can be printed by ``--dry-run`` and asserted by tests
    without starting a game.
    """
    version = prepared["version"]
    profile = prepared["profile"]
    game_dir = str(instance.game_dir())
    natives = prepared["natives"]
    classpath = os.pathsep.join(prepared["classpath"] + [prepared["client_jar"]])

    variables = {
        "natives_directory": natives,
        "launcher_name": "logclient-headless",
        "launcher_version": VERSION,
        "classpath": classpath,
        "auth_player_name": instance.username,
        "version_name": f"{instance.mc_version}-fabric{instance.loader_version}",
        "game_directory": game_dir,
        "assets_root": str(cache_dir() / "assets"),
        "assets_index_name": version["assetIndex"]["id"],
        # An offline account's uuid is a pure function of its name, so it is always derived here
        # rather than read from the config: a server derives the same value, and a stored copy
        # that drifted from the name would present the wrong identity.
        "auth_uuid": offline_uuid(instance.username or default_username(instance.name)),
        "auth_access_token": "0",
        "clientid": "",
        "auth_xuid": "",
        "user_type": "legacy",
        "version_type": "release",
        "resolution_width": str(instance.width),
        "resolution_height": str(instance.height),
        "user_properties": "{}",
        "quickPlayPath": str(instance.root() / "quickplay.log"),
        "quickPlayMultiplayer": instance.server,
        "quickPlaySingleplayer": "",
    }
    features = {
        "is_demo_user": False,
        "has_custom_resolution": True,
        "has_quick_plays_support": True,
        "is_quick_play_multiplayer": bool(instance.server),
        "is_quick_play_singleplayer": False,
    }

    argv = [java, f"-Xmx{instance.memory_mb}M", f"-Xms{min(512, instance.memory_mb)}M"]
    # The loader's JVM arguments must come before the version's, because the version's list ends
    # with "-cp ${classpath}". Anything placed after that point is read as the main class and game
    # arguments, so the client would fail to find its main class.
    argv += collect_args(profile.get("arguments", {}).get("jvm"), variables, features)
    argv += collect_args(version.get("arguments", {}).get("jvm"), variables, features)

    logging_file = (version.get("logging") or {}).get("client", {}).get("file")
    if logging_file:
        config = prepared.get("log4j_config") or str(
            cache_dir() / "log4j" / logging_file["id"])
        classpath_at = argv.index("-cp") if "-cp" in argv else len(argv)
        argv.insert(classpath_at, f"-Dlog4j.configurationFile={config}")

    main_class = profile.get("mainClass") or version.get("mainClass")
    argv.append(main_class)
    argv += collect_args(version.get("arguments", {}).get("game"), variables, features)
    argv += collect_args(profile.get("arguments", {}).get("game"), variables, features)

    env = {
        "DISPLAY": display,
        "LIBGL_ALWAYS_SOFTWARE": "1",
        "MESA_GL_VERSION_OVERRIDE": "3.3",
        "MESA_GLSL_VERSION_OVERRIDE": "330",
        "HOME": str(instance.root()),
    }
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return LaunchPlan(
        argv=argv,
        env=env,
        cwd=game_dir,
        log_path=str(instance.logs_dir() / f"launch-{stamp}.log"),
    )


# --------------------------------------------------------------------------------------------
# Running an instance
# --------------------------------------------------------------------------------------------


def free_display() -> str:
    """The first X display number with no socket, so two instances can run side by side."""
    for number in range(90, 200):
        if not Path(f"/tmp/.X11-unix/X{number}").exists():
            return f":{number}"
    raise HeadlessError("No free X display number between :90 and :199")


def running_pid(instance: Instance) -> Optional[int]:
    """The PID of a live java process for this instance, or None."""
    path = instance.run_path()
    if not path.is_file():
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        pid = int(document.get("pid") or 0)
    except (ValueError, OSError):
        return None
    if pid and pid_alive(pid) and document.get("java", True):
        return pid
    return None


def pid_alive(pid: int) -> bool:
    """True when a process exists and has not exited.

    A killed child that has not been reaped yet is a zombie: it still answers ``kill(pid, 0)``,
    so checking that alone would report a stopped instance as running forever. On Linux the
    process state settles it.
    """
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    try:
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as handle:
            stat_line = handle.read()
    except OSError:
        return True
    close = stat_line.rfind(")")
    if close == -1:
        return True
    state = stat_line[close + 2:close + 3]
    return state not in ("Z", "X")


def start(instance: Instance, prepared: dict, java: str, dry_run: bool = False,
          wait_seconds: float = 0.0, keep_display: bool = False) -> int:
    """Starts Xvfb and the game, records the PIDs, and returns the game PID (0 on a dry run)."""
    if running_pid(instance):
        raise HeadlessError(f"'{instance.name}' is already running (pid {running_pid(instance)})")

    display = free_display()
    plan = plan_launch(instance, prepared, java, display)

    if dry_run:
        log("DISPLAY=" + display + " " + shlex.join(plan.argv))
        return 0

    instance.logs_dir().mkdir(parents=True, exist_ok=True)
    instance.root().mkdir(parents=True, exist_ok=True)

    xvfb = shutil.which("Xvfb")
    if not xvfb:
        raise HeadlessError(
            "Xvfb is not installed, and a headless client needs an X display. "
            "Run: sudo python3 tools/headless.py setup"
        )

    screen = f"{instance.width}x{instance.height}x24"
    display_proc = subprocess.Popen(
        [xvfb, display, "-screen", "0", screen, "-nolisten", "tcp"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        if not wait_for_display(display, timeout=10.0):
            raise HeadlessError(f"Xvfb did not come up on {display}")

        env = dict(os.environ)
        env.update(plan.env)
        log(f"Starting '{instance.name}' on display {display} ...")
        with open(plan.log_path, "wb") as log_file:
            game = subprocess.Popen(
                plan.argv,
                cwd=plan.cwd,
                env=env,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                start_new_session=True,
            )
        instance.run_path().write_text(
            json.dumps(
                {
                    "pid": game.pid,
                    "displayPid": display_proc.pid,
                    "display": display,
                    "startedAt": time.time(),
                    "log": plan.log_path,
                    "argv": plan.argv,
                    "java": True,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        log(f"Started pid {game.pid}. Log: {plan.log_path}")

        if wait_seconds:
            wait_for_companion(instance, wait_seconds)

        if keep_display:
            log("The display stays up: this process is exiting, so stop the instance with 'stop'.")
        return game.pid
    except Exception:
        if not keep_display:
            terminate(display_proc.pid)
        raise


def wait_for_display(display: str, timeout: float) -> bool:
    number = display.lstrip(":")
    deadline = time.time() + timeout
    while time.time() < deadline:
        if Path(f"/tmp/.X11-unix/X{number}").exists():
            return True
        time.sleep(0.1)
    return False


def wait_for_companion(instance: Instance, seconds: float) -> None:
    """Waits for the mod to publish its discovery file, then prints how to reach it."""
    deadline = time.time() + seconds
    path = instance.discovery_path()
    while time.time() < deadline:
        if path.is_file():
            try:
                document = json.loads(path.read_text(encoding="utf-8"))
            except ValueError:
                document = {}
            host = document.get("host") or "127.0.0.1"
            port = document.get("port") or 6767
            log(f"Companion is up: http://{host}:{port}  (attach: headless.py attach {instance.name})")
            return
        if not running_pid(instance):
            log("The instance exited before the companion appeared.")
            return
        time.sleep(0.5)
    log(
        "No companion yet. This is normal for a fresh world: the module is enabled in config, "
        f"but it only starts once the game reaches the title screen. Look in "
        f"{instance.logs_dir()} for errors. Discovery file: {path}"
    )


def terminate(pid: int, grace: float = 10.0) -> bool:
    """Stops a process politely, then firmly. Returns True when it is gone."""
    if not pid or not pid_alive(pid):
        return True
    try:
        os.kill(pid, signal.SIGCONT)
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    deadline = time.time() + grace
    while time.time() < deadline:
        if not pid_alive(pid):
            return True
        time.sleep(0.2)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        return True
    time.sleep(0.2)
    return not pid_alive(pid)


def stop(instance: Instance) -> bool:
    """Stops the game and the display it was using."""
    path = instance.run_path()
    document: dict = {}
    if path.is_file():
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            document = {}
    stopped = True
    pid = int(document.get("pid") or 0)
    if pid:
        stopped = terminate(pid)
    display_pid = int(document.get("displayPid") or 0)
    if display_pid:
        terminate(display_pid, grace=3.0)
    if path.is_file():
        document["stoppedAt"] = time.time()
        document["java"] = False
        path.write_text(json.dumps(document, indent=2), encoding="utf-8")
    return stopped


# --------------------------------------------------------------------------------------------
# Debian dependencies
# --------------------------------------------------------------------------------------------


def apt_install(packages: Iterable[str]) -> bool:
    """Installs Debian packages. Returns False when the machine has no usable apt."""
    packages = list(packages)
    if not shutil.which("apt-get"):
        log("apt-get is not available; install the packages by hand.")
        return False
    prefix = [] if os.geteuid() == 0 else ["sudo", "-n"]
    if prefix:
        probe = subprocess.run(prefix + ["true"], capture_output=True)
        if probe.returncode != 0:
            raise HeadlessError(
                "Installing packages needs root. Re-run with sudo: "
                "sudo python3 tools/headless.py setup"
            )
    subprocess.run(prefix + ["apt-get", "update", "-qq"], check=False)
    result = subprocess.run(
        prefix + ["apt-get", "install", "-y", "--no-install-recommends"] + packages,
        check=False,
    )
    return result.returncode == 0


def missing_debian_packages() -> list:
    """Which of the packages this app wants are not installed.

    Each package is asked about on its own. A batch query cannot answer this: a package that is
    not installed is left out of the output entirely and only mentioned on stderr, so a batch of
    results silently looks complete when nothing is installed at all.
    """
    if not shutil.which("dpkg-query"):
        return []
    missing = []
    for package in DEBIAN_PACKAGES:
        result = subprocess.run(
            ["dpkg-query", "-W", "-f=${Status}", package],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.stdout.strip().split()[-1:] != ["installed"]:
            missing.append(package)
    return missing


def doctor(offline: bool = False) -> int:
    """Reports what is present and what is missing. Never changes anything."""
    problems = 0
    log(f"{APP_NAME} {VERSION}")
    log(f"  home        {home()}")
    log(f"  platform    {os_name()} / {os_arch()}")

    java = find_java(21)
    if java:
        log(f"  java        {java} (major {java_major(java)})")
    else:
        problems += 1
        log("  java        MISSING a JDK 21. Run: sudo python3 tools/headless.py setup")

    xvfb = shutil.which("Xvfb")
    if xvfb:
        log(f"  xvfb        {xvfb}")
    else:
        problems += 1
        log("  xvfb        MISSING. Run: sudo python3 tools/headless.py setup")

    missing = missing_debian_packages()
    if missing:
        problems += 1
        log("  packages    missing: " + ", ".join(missing))
    else:
        log("  packages    all present")

    free_gb = shutil.disk_usage(home().anchor or "/").free / (1 << 30)
    log(f"  disk        {free_gb:.1f} GB free under {home().anchor or '/'}")
    if free_gb < 3:
        problems += 1
        log("              under 3 GB: a client with assets needs about 1.5 GB")

    if offline:
        log("  network     not checked (--offline)")
    else:
        try:
            http_json(VERSION_MANIFEST)
            log("  network     Mojang manifest reachable")
        except HeadlessError as error:
            problems += 1
            log(f"  network     {error}")

    instances = list_instances()
    log(f"  instances   {len(instances)}")
    for instance in instances:
        pid = running_pid(instance)
        log(f"              {instance.name}: {'running pid ' + str(pid) if pid else 'stopped'}")

    log("")
    log("Ready." if problems == 0 else f"{problems} thing(s) to fix above.")
    return 0 if problems == 0 else 1


def cmd_doctor(args: argparse.Namespace) -> int:
    return doctor(offline=getattr(args, "offline", False))


def cmd_setup(args: argparse.Namespace) -> int:
    return setup(install_packages=not args.no_apt, install_jdk=not args.no_jdk,
                 offline=args.offline)


def setup(install_packages: bool = True, install_jdk: bool = True,
          offline: bool = False) -> int:
    """Installs the Debian packages and a JDK 21 into this app's directory."""
    if install_packages:
        missing = missing_debian_packages()
        if missing:
            log("Installing packages: " + ", ".join(missing))
            if not apt_install(missing):
                log("Some packages could not be installed; the list above says which.")
        else:
            log("All Debian packages are already installed.")

    if find_java(21):
        log(f"Java 21 is already available: {find_java(21)}")
    elif not install_jdk:
        log("Skipping the JDK download (--no-jdk); Java 21 is still missing.")
    else:
        java = provision_jdk(21)
        log(f"Provisioned Java: {java}")
    return doctor(offline=offline)


# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------


def enable_companion(instance: Instance) -> bool:
    """Turns the companion module on in the mod's own config, so ``attach`` works unprompted.

    The mod persists modules as plain JSON under ``logclient/config.json`` and applies ``active``
    on load, so this is a supported path rather than a hack. It is also the only hands-free one: the
    module is off by default, and a headless client has no window in which to click it on.

    An existing config is edited in place and never replaced, because it also holds the user's
    other modules, HUD layout and token. Returns True when a change was written.
    """
    path = instance.game_dir() / "logclient" / "config.json"
    document: dict = {}
    if path.is_file():
        try:
            loaded = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                document = loaded
        except ValueError:
            log(f"{path} is not valid JSON; leaving it alone.")
            return False
    modules = document.get("modules")
    if not isinstance(modules, dict):
        modules = {}
        document["modules"] = modules
    entry = modules.get("companion")
    if not isinstance(entry, dict):
        entry = {}
        modules["companion"] = entry
    if entry.get("active") is True:
        return False
    entry["active"] = True
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2), encoding="utf-8")
    return True


def cmd_create(args: argparse.Namespace) -> int:
    name = args.name
    if (instances_dir() / name / "instance.json").is_file() and not args.force:
        raise HeadlessError(f"'{name}' already exists. Pass --force to rewrite its config.")

    loader = args.loader or latest_loader(args.mc)
    instance = Instance(
        name=name,
        mc_version=args.mc,
        loader_version=loader,
        memory_mb=args.memory,
        width=args.width,
        height=args.height,
        username=args.username or default_username(name),
        server=args.server or "",
        with_meteor=not args.no_meteor,
        meteor_version=args.meteor_version,
        created_at=time.time(),
    )
    instance.uuid = offline_uuid(instance.username)
    instance.save()
    log(f"Created '{name}': Minecraft {instance.mc_version}, Fabric {loader}, "
        f"user {instance.username}")

    if args.configure_only:
        return 0
    prepare(instance, skip_assets=args.skip_assets, log_client_jar=args.log_client)
    log(f"Instance '{name}' is ready. Start it with: headless.py start {name}")
    return 0


def cmd_start(args: argparse.Namespace) -> int:
    instance = Instance.load(args.name)
    if args.server:
        instance.server = args.server
        instance.save()
    java = args.java or find_java(21)
    if not java:
        raise HeadlessError(
            "No JDK 21 found. Run: sudo python3 tools/headless.py setup"
        )
    if args.dry_run:
        # A dry run answers "what would be run?" without downloading a client or touching the
        # network, so it works on a machine that is not set up yet.
        prepared = plan_prepared(instance)
        plan = plan_launch(instance, prepared, java, free_display())
        log("DISPLAY=" + plan.env["DISPLAY"] + " cwd=" + plan.cwd)
        log(shlex.join(plan.argv))
        return 0
    prepared = prepare(instance, skip_assets=args.skip_assets, log_client_jar=args.log_client)
    start(
        instance,
        prepared,
        java,
        wait_seconds=args.wait_companion,
        keep_display=args.detach,
    )
    return 0


def cmd_stop(args: argparse.Namespace) -> int:
    instance = Instance.load(args.name)
    if not stop(instance):
        log(f"'{instance.name}' did not stop")
        return 1
    log(f"Stopped '{instance.name}'")
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    instances = list_instances()
    if not instances:
        log("No instances yet. Create one: headless.py create mine")
        return 0
    for instance in instances:
        pid = running_pid(instance)
        state = f"running pid {pid}" if pid else "stopped"
        log(f"{instance.name:<16} {instance.mc_version:<9} fabric {instance.loader_version:<8} "
            f"{instance.username:<16} {state}")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    instance = Instance.load(args.name)
    pid = running_pid(instance)
    path = instance.run_path()
    document: dict = {}
    if path.is_file():
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            document = {}
    log(f"instance  {instance.name}")
    log(f"minecraft {instance.mc_version} (fabric {instance.loader_version})")
    log(f"user      {instance.username}  uuid {instance.uuid}")
    log(f"server    {instance.server or '(none: stays at the title screen)'}")
    log(f"memory    {instance.memory_mb} MB")
    log(f"state     {'running pid ' + str(pid) if pid else 'stopped'}")
    if document.get("display"):
        log(f"display   {document['display']}")
    if path.is_file():
        started = document.get("startedAt")
        if started:
            log(f"uptime    {int(time.time() - float(started))}s")
    discovery = instance.discovery_path()
    if discovery.is_file():
        log(f"companion {discovery}")
    if args.log:
        log_tail(Path(document.get("log", "")), args.lines)
    return 0


def log_tail(path: Path, lines: int) -> None:
    if not path.is_file():
        log(f"no log at {path}")
        return
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        tail = handle.readlines()[-lines:]
    for line in tail:
        sys.stdout.write(line if line.endswith("\n") else line + "\n")


def cmd_logs(args: argparse.Namespace) -> int:
    instance = Instance.load(args.name)
    logs = sorted(instance.logs_dir().glob("launch-*.log")) if instance.logs_dir().is_dir() else []
    if not logs:
        log("No logs yet.")
        return 1
    latest = logs[-1]
    if not args.follow:
        log_tail(latest, args.lines)
        return 0
    log(f"Following {latest} (Ctrl-C to stop)")
    with open(latest, "r", encoding="utf-8", errors="replace") as handle:
        handle.seek(0, os.SEEK_END)
        try:
            while True:
                line = handle.readline()
                if line:
                    sys.stdout.write(line)
                    sys.stdout.flush()
                else:
                    time.sleep(0.4)
        except KeyboardInterrupt:
            return 0


def cmd_attach(args: argparse.Namespace) -> int:
    """Opens the terminal dashboard against this instance's companion server."""
    instance = Instance.load(args.name)
    discovery = instance.discovery_path()
    if not discovery.is_file():
        raise HeadlessError(
            f"No companion yet for '{instance.name}'. Start the instance, then turn the "
            "'companion' module on in game; it publishes " + str(discovery)
        )
    dashboard = REPO_ROOT / "tools" / "dashboard.py"
    if not dashboard.is_file():
        raise HeadlessError("tools/dashboard.py is missing; run this from the repository")
    env = dict(os.environ)
    env["LOGLIENT_COMPANION_FILE"] = str(discovery)
    return subprocess.call([sys.executable, str(dashboard)] + args.dashboard_args, env=env)


def cmd_remove(args: argparse.Namespace) -> int:
    instance = Instance.load(args.name)
    if running_pid(instance):
        raise HeadlessError(f"'{instance.name}' is running. Stop it first.")
    if not args.yes:
        raise HeadlessError(f"Refusing to delete {instance.root()}. Pass --yes to confirm.")
    shutil.rmtree(instance.root())
    log(f"Removed '{instance.name}'")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="headless.py",
        description="Run Log Client in headless Minecraft instances on Debian.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Start with 'doctor', then 'setup', then 'create', then 'start'.",
    )
    parser.add_argument("--version", action="version", version=f"{APP_NAME} {VERSION}")
    sub = parser.add_subparsers(dest="command", required=True)

    doctor_parser = sub.add_parser("doctor", help="report what is missing on this machine")
    doctor_parser.add_argument("--offline", action="store_true",
                               help="skip the network check")
    doctor_parser.set_defaults(func=cmd_doctor)

    setup_parser = sub.add_parser("setup", help="install Debian packages and a JDK 21")
    setup_parser.add_argument("--no-apt", action="store_true", help="skip the apt step")
    setup_parser.add_argument("--offline", action="store_true", help="skip the network check")
    setup_parser.add_argument("--no-jdk", action="store_true", help="skip the JDK download")
    setup_parser.set_defaults(func=cmd_setup)

    create = sub.add_parser("create", help="create an instance and download what it needs")
    create.add_argument("name")
    create.add_argument("--mc", default=DEFAULT_MC, help=f"Minecraft version (default {DEFAULT_MC})")
    create.add_argument("--loader", default="", help="Fabric loader version (default: newest stable)")
    create.add_argument("--memory", type=int, default=DEFAULT_MEMORY_MB, help="heap in MB")
    create.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    create.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    create.add_argument("--username", help="offline username (default: the instance name)")
    create.add_argument("--server", help="join this host:port at start (optional)")
    create.add_argument("--no-meteor", dest="no_meteor", action="store_true",
                        help="do not install Meteor Client")
    create.add_argument("--meteor-version", default="0.5.8")
    create.add_argument("--log-client", help="path to a Log Client jar (default: build/libs)")
    create.add_argument("--skip-assets", action="store_true",
                        help="skip the asset download (fast, but text may render blank)")
    create.add_argument("--configure-only", action="store_true",
                        help="write the config without downloading anything")
    create.add_argument("--force", action="store_true", help="overwrite an existing instance")
    create.set_defaults(func=cmd_create)

    start_parser = sub.add_parser("start", help="start an instance under Xvfb")
    start_parser.add_argument("name")
    start_parser.add_argument("--server", help="join this host:port at start")
    start_parser.add_argument("--java", help="path to java (default: the provisioned JDK)")
    start_parser.add_argument("--log-client", help="path to a Log Client jar")
    start_parser.add_argument("--skip-assets", action="store_true")
    start_parser.add_argument("--dry-run", action="store_true",
                              help="print the command instead of running it")
    start_parser.add_argument("--wait-companion", type=float, default=0.0, metavar="SECONDS",
                              help="wait this long for the companion server, then report its URL")
    start_parser.add_argument("--detach", action="store_true",
                              help="leave the display running after this command returns")
    start_parser.set_defaults(func=cmd_start)

    stop_parser = sub.add_parser("stop", help="stop an instance")
    stop_parser.add_argument("name")
    stop_parser.set_defaults(func=cmd_stop)

    sub.add_parser("list", help="list instances").set_defaults(func=cmd_list)

    status = sub.add_parser("status", help="detail for one instance")
    status.add_argument("name")
    status.add_argument("--log", action="store_true", help="also print the end of the log")
    status.add_argument("--lines", type=int, default=20)
    status.set_defaults(func=cmd_status)

    logs = sub.add_parser("logs", help="show an instance's console output")
    logs.add_argument("name")
    logs.add_argument("--follow", "-f", action="store_true")
    logs.add_argument("--lines", type=int, default=40)
    logs.set_defaults(func=cmd_logs)

    attach = sub.add_parser("attach", help="open the dashboard on a running instance")
    attach.add_argument("name")
    attach.add_argument("dashboard_args", nargs=argparse.REMAINDER,
                        help="extra arguments for tools/dashboard.py")
    attach.set_defaults(func=cmd_attach)

    remove = sub.add_parser("remove", help="delete an instance")
    remove.add_argument("name")
    remove.add_argument("--yes", action="store_true")
    remove.set_defaults(func=cmd_remove)

    return parser


def main(argv: Optional[list] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except HeadlessError as error:
        log(f"error: {error}")
        return 1
    except KeyboardInterrupt:
        log("interrupted")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
