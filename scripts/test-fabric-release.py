"""Exercise frozen release JARs in non-development Fabric instances on localhost."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import time
from urllib.request import Request, urlopen
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / ".local/f3-production"
RUNTIME = BASE / "runtime"
CACHE = ROOT / ".local/gradle-fabric-26.2/caches"
JAVA = ROOT / ".local/jdk25/bin/java.exe"
MODULES = ("forestry", "forestrybutterflies", "forestryfarms", "forestrymail")
MODULE_ARCHIVE_SUFFIXES = {
    "forestry": "",
    "forestrybutterflies": "-butterflies",
    "forestryfarms": "-farms",
    "forestrymail": "-mail",
}
PORT = 25579


def digest(path, algorithm="sha256"):
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def release_jar(mod, properties):
    prefix = properties["modArchiveBaseName"] + MODULE_ARCHIVE_SUFFIXES[mod]
    return ROOT / "build/fabric-26.2/libs" / f"{prefix}-{properties['minecraftVersion']}-fabric-{properties['forestryVersion']}.jar"


def request(url):
    with urlopen(Request(url, headers={"User-Agent": "Forestry-Fabric-Release-Acceptance"}), timeout=60) as response:
        return response.read()


def fetch(path, url, expected, algorithm="sha256"):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(request(url))
    if digest(path, algorithm) != expected:
        raise ValueError(f"Checksum mismatch: {path}")
    return path


def artifact(group, name, version):
    candidates = list((CACHE / "modules-2/files-2.1" / group / name / version).rglob("*.jar"))
    candidates = [path for path in candidates if not path.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if len(candidates) != 1:
        raise ValueError(f"Expected one verified cached artifact: {group}:{name}:{version}: {candidates}")
    return candidates[0]


def fabric_libraries(profile):
    paths = []
    for library in profile["libraries"]:
        group, name, version = library["name"].split(":")
        relative = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.jar"
        url = library["url"] + relative
        checksum = library.get("sha256") or request(url + ".sha256").decode().split()[0]
        paths.append(str(fetch(RUNTIME / "libraries" / relative, url, checksum)))
    return paths


def prepare(runtime_only=False):
    RUNTIME.mkdir(parents=True, exist_ok=True)
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
    profiles = {}
    for side in ("server", "client"):
        profile = "server" if side == "server" else "profile"
        url = f"https://meta.fabricmc.net/v2/versions/loader/26.2/{properties['fabricLoaderVersion']}/{profile}/json"
        body = request(url)
        (RUNTIME / f"fabric-{side}.json").write_bytes(body)
        profiles[side] = json.loads(body)
    info = json.loads((CACHE / "fabric-loom/26.2/mojang_minecraft_info.json").read_text())
    server_jar = CACHE / "fabric-loom/26.2/minecraft-server.jar"
    if digest(server_jar, "sha1") != info["downloads"]["server"]["sha1"]:
        raise ValueError("Minecraft server bundle changed")
    server_libraries = []
    with zipfile.ZipFile(server_jar) as bundle:
        for index in ("libraries", "versions"):
            for line in bundle.read(f"META-INF/{index}.list").decode().splitlines():
                checksum, coordinate, relative = line.split("\t")
                target = (RUNTIME / index / relative).resolve()
                if not target.is_relative_to(RUNTIME.resolve()):
                    raise ValueError("Bundle path escaped runtime")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.read(f"META-INF/{index}/{relative}"))
                if digest(target) != checksum:
                    raise ValueError(f"Bundled library checksum mismatch: {coordinate}")
                server_libraries.append(str(target))
    client_libraries = []
    for library in info["libraries"]:
        if library.get("rules"):
            allowed = False
            for rule in library["rules"]:
                system = rule.get("os", {})
                if system.get("name", "windows") == "windows" and system.get("arch", "x86_64") in ("x86_64", "amd64"):
                    allowed = rule["action"] == "allow"
            if not allowed:
                continue
        download = library["downloads"]["artifact"]
        target = RUNTIME / "client-libraries" / download["path"]
        if not target.exists():
            group, name, version, *classifier = library["name"].split(":")
            cached = list((CACHE / "modules-2/files-2.1" / group / name / version).rglob(Path(download["path"]).name))
            if len(cached) == 1:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(cached[0], target)
        client_libraries.append(str(fetch(target, download["url"], download["sha1"], "sha1")))
    client_jar = CACHE / "fabric-loom/26.2/minecraft-client.jar"
    if digest(client_jar, "sha1") != info["downloads"]["client"]["sha1"]:
        raise ValueError("Minecraft client changed")
    copied_client = RUNTIME / "minecraft-client.jar"
    shutil.copy2(client_jar, copied_client)
    asset_index = fetch(CACHE / "fabric-loom/assets/indexes" / f"{info['assetIndex']['id']}.json",
                        info["assetIndex"]["url"], info["assetIndex"]["sha1"], "sha1")
    for entry in json.loads(asset_index.read_text())["objects"].values():
        asset = CACHE / "fabric-loom/assets/objects" / entry["hash"][:2] / entry["hash"]
        fetch(asset, f"https://resources.download.minecraft.net/{entry['hash'][:2]}/{entry['hash']}", entry["hash"], "sha1")
    runtime = {"server_classpath": fabric_libraries(profiles["server"]) + server_libraries,
               "client_classpath": fabric_libraries(profiles["client"]) + [str(copied_client)] + client_libraries,
               "asset_index": asset_index.stem}
    (RUNTIME / "manifest.json").write_text(json.dumps(runtime, indent=2) + "\n", encoding="utf-8")
    if runtime_only:
        print("Prepared checksum-verified native Fabric runtime", flush=True)
        return
    outputs = {}
    for mod in MODULES:
        source = release_jar(mod, properties)
        with zipfile.ZipFile(source) as archive:
            if any(name.startswith(("forestry/fabric/", "forestry/datagen/", "groovy/", "net/neoforged/")) for name in archive.namelist()):
                raise ValueError(f"Development contents in release: {source}")
        destination = BASE / "artifacts" / source.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        outputs[mod] = {"path": str(destination), "sha256": digest(destination)}
    required = [artifact("net.fabricmc.fabric-api", "fabric-api", properties["fabricApiVersion"])]
    optional = [artifact("maven.modrinth", "jade", properties["jadeVersion"]), artifact("mezz.jei", "jei-26.2-fabric", properties["jeiVersion"]), artifact("net.mezzdev.config", "mezz_config-26.2-fabric", properties["mezzConfigVersion"])]
    probe = ROOT / "build/fabric-26.2/test-tools" / f"forestry-fabric-release-probe-{properties['forestryVersion']}.jar"
    manifest = {"version": properties["forestryVersion"], "loader": properties["fabricLoaderVersion"], "artifacts": outputs,
                "required": [{"path": str(path), "sha256": digest(path)} for path in required],
                "optional": [{"path": str(path), "sha256": digest(path)} for path in optional],
                "probe": {"path": str(probe), "sha256": digest(probe)}, **runtime}
    (BASE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared frozen release {manifest['version']}", flush=True)


def instance(manifest, parent, side, mask, optional):
    game = parent / side
    mods = game / "mods"
    mods.mkdir(parents=True, exist_ok=False)
    selected = [manifest["artifacts"][MODULES[0]]] + [manifest["artifacts"][MODULES[index + 1]] for index in range(3) if mask & (1 << index)]
    selected += manifest["required"] + (manifest["optional"] if optional else [])
    if side == "client":
        selected.append(manifest["probe"])
        (game / "options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nrenderDistance:3\nsimulationDistance:5\nmaxFps:60\nguiScale:2\nsoundCategory_master:0.0\n", encoding="utf-8")
    for entry in selected:
        source = Path(entry["path"])
        if digest(source) != entry["sha256"]:
            raise ValueError(f"Frozen artifact changed: {source}")
        shutil.copy2(source, mods / source.name)
    return game


def launch(manifest, side, game, mask):
    arguments = [str(JAVA), "-Xmx2G", "-Dfabric.development=false", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                 "-cp", os.pathsep.join(manifest[f"{side}_classpath"])]
    if side == "client":
        for folder in ("java", "jna", "lwjgl", "netty"):
            (game / "natives" / folder).mkdir(parents=True, exist_ok=True)
        arguments += [f"-Dforestry.releaseProbe.directory={game}", f"-Dforestry.releaseProbe.modules={mask}",
                      f"-Djava.library.path={game / 'natives/java'}", f"-Dio.netty.native.workdir={game / 'natives/netty'}",
                      f"-Dorg.lwjgl.system.SharedLibraryExtractPath={game / 'natives/lwjgl'}", f"-Djna.tmpdir={game / 'natives/jna'}",
                      "net.fabricmc.loader.impl.launch.knot.KnotClient", "--version", manifest["version"], "--gameDir", str(game),
                      "--assetsDir", str(CACHE / "fabric-loom/assets"), "--assetIndex", manifest["asset_index"], "--username", "ForestryF3",
                      "--uuid", str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:ForestryF3").digest(), version=3)), "--accessToken", "0",
                      "--userType", "legacy", "--quickPlayMultiplayer", f"127.0.0.1:{PORT}", "--width", "1280", "--height", "720"]
    else:
        arguments += ["net.fabricmc.loader.impl.launch.knot.KnotServer", "--nogui"]
    log = game / "console.log"
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(arguments, cwd=game, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    (game / "launch.json").write_text(json.dumps(arguments, indent=2), encoding="utf-8")
    return process, log


def wait(process, predicate, description, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        if process.poll() is not None:
            raise RuntimeError(f"Process exited ({process.returncode}) before {description}")
        time.sleep(0.5)
    raise TimeoutError(description)


def command(process, text):
    process.stdin.write(text + "\n")
    process.stdin.flush()


def stop(process):
    if process.poll() is None:
        try:
            command(process, "stop")
            process.wait(timeout=40)
        except (OSError, subprocess.TimeoutExpired):
            process.kill()
            process.wait()


def run(eula_from, clients, modules, append=False):
    if not eula_from or "eula=true" not in Path(eula_from).read_text().splitlines():
        raise ValueError("Provide an existing user-accepted Minecraft EULA file using --eula-from; this tool never accepts it")
    manifest = json.loads((BASE / "manifest.json").read_text())
    root = BASE / "runs" / f"{manifest['artifacts']['forestry']['sha256'][:12]}-{time.time_ns()}"
    cases = [(mask, False, False) for mask in modules]
    if clients:
        cases += [(0, False, True), (7, False, True), (7, True, False), (7, True, True)]
    results = json.loads((BASE / "results.json").read_text()) if append and (BASE / "results.json").exists() else []
    (BASE / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    for index, (mask, server_optional, client_optional) in enumerate(cases):
        case = root / f"{index}-modules-{mask}-server-{server_optional}-client-{client_optional}"
        server_game = instance(manifest, case, "server", mask, server_optional)
        shutil.copy2(eula_from, server_game / "eula.txt")
        terrain = "level-type=minecraft:normal\ngenerate-structures=true\nlevel-seed=2622026\n" if mask == 7 else (
            "level-type=minecraft:flat\ngenerate-structures=false\n"
            'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"structure_overrides":[]}\n')
        (server_game / "server.properties").write_text(
            f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nenforce-secure-profile=false\n"
            "level-name=acceptance\n" + terrain +
            "view-distance=3\nsimulation-distance=3\nmax-players=2\npause-when-empty-seconds=0\n", encoding="utf-8")
        for phase in ("initial", "restart"):
            server, log = launch(manifest, "server", server_game, mask)
            client = None
            try:
                wait(server, lambda: "Done (" in log.read_text(errors="replace"), "server ready")
                if clients and mask in (0, 7) and phase == "initial":
                    client_game = instance(manifest, case, "client", mask, client_optional)
                    client, client_log = launch(manifest, "client", client_game, mask)
                    wait(client, lambda: (client_game / "client.ready").exists(), "client join")
                    command(server, "op ForestryF3\ngamemode creative ForestryF3\nfill -6 99 -6 6 99 6 minecraft:stone\nfill -6 100 -6 6 106 6 minecraft:air\nsetblock 0 100 3 forestry:carpenter\ntp ForestryF3 0.5 100 0.5 0 10\nitem replace entity ForestryF3 weapon.mainhand with forestry:portable_analyzer")
                    (client_game / "begin").write_text("server fixtures ready\n")
                    wait(client, lambda: (client_game / "client.reload").exists(), "real menus and genome command")
                    command(server, "reload")
                    if client.wait(timeout=180) != 0 or not (client_game / "client.passed").exists():
                        raise RuntimeError(f"Native release client failed: {client_log}")
                    check_log(client_log)
                else:
                    before = log.read_text(errors="replace").count("Loaded 50 tree species")
                    command(server, "reload")
                    wait(server, lambda: log.read_text(errors="replace").count("Loaded 50 tree species") > before, "server reload")
                command(server, "save-all flush\nstop")
                if server.wait(timeout=90) != 0:
                    raise RuntimeError(f"Dedicated server failed: {log}")
                text = log.read_text(errors="replace")
                check_log(log)
                if "All dimensions are saved" not in text or not (server_game / "acceptance/level.dat").exists():
                    raise RuntimeError("Server save was not confirmed")
                if "forestry_f2_tests" in text or "forestry_datagen" in text or "forestry_release_probe" in text:
                    raise RuntimeError("Acceptance driver or development mod leaked into the production server")
                saved_log = server_game / f"console-{phase}.log"
                shutil.copy2(log, saved_log)
                results = [item for item in results if (item["mask"], item["server_optional"], item["client_optional"], item["phase"])
                           != (mask, server_optional, client_optional, phase)]
                results.append({"mask": mask, "server_optional": server_optional, "client_optional": client_optional,
                                "client": clients and mask in (0, 7) and phase == "initial", "phase": phase, "status": "passed", "directory": str(case), "log_sha256": digest(saved_log)})
                (BASE / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
                print(json.dumps(results[-1]), flush=True)
            finally:
                if client is not None and client.poll() is None:
                    client.kill()
                    client.wait()
                stop(server)


def check_log(path):
    pattern = r"Couldn't parse|Error loading recipe|Failed to load registries|Failed to parse.*forestry|Unknown registry key.*forestry|Missing (?:item model|textures in model).*forestry|Failed to load blockstate.*forestry|Missing model for variant:.*forestry|Error loading item model.*forestry|Unable to bake.*forestry|Missing referenced model.*forestry|Mixin apply.*failed|FORESTRY_F3_RELEASE_CLIENT_FAILED"
    errors = re.findall(pattern, path.read_text(errors="replace"), re.IGNORECASE)
    if errors:
        raise RuntimeError(f"Release resource or runtime errors in {path}: {errors}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "runtime", "run"))
    parser.add_argument("--eula-from", type=Path)
    parser.add_argument("--clients", action="store_true")
    parser.add_argument("--append", action="store_true")
    parser.add_argument("--modules", type=int, nargs="+", default=list(range(8)))
    args = parser.parse_args()
    if any(mask < 0 or mask > 7 for mask in args.modules):
        parser.error("Modules must be masks 0..7")
    if args.action in ("prepare", "runtime"):
        prepare(args.action == "runtime")
    else:
        run(args.eula_from, args.clients, args.modules, args.append)
