#!/usr/bin/env python3
"""Launch local, preview or production with explicit environment boundaries."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

PROJECT = Path(__file__).resolve().parent.parent
RUNTIMES = PROJECT / ".local" / "runtime"
MAC_JAVA_HOME = Path("/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home")


def fail(message):
    raise SystemExit(message)


def isolated_environment():
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(("SPRING_CONFIG_", "SPRING_PROFILES_")) or key in {
            "SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS", "JAVA_OPTS", "PORT", "SERVER_PORT", "SERVER_ADDRESS",
        }:
            env.pop(key, None)
    return env


def load_local_minimax(env, path):
    keys = {"MINIMAX_API_KEY", "MINIMAX_BASE_URL"}
    with path.expanduser().open(encoding="utf-8") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            fail("MiniMax environment file must be a regular file owned by this user and private (0600).")
        values = {}
        for line in stream:
            match = re.match(r"\s*(?:export\s+)?(MINIMAX_API_KEY|MINIMAX_BASE_URL)\s*=(.*)", line)
            if not match:
                continue
            name, raw = match.groups()
            try:
                parts = shlex.split(raw, comments=True)
            except ValueError:
                fail("Invalid value in MiniMax environment file.")
            if name in values or len(parts) != 1 or not parts[0]:
                fail("Invalid or duplicate value in MiniMax environment file.")
            values[name] = parts[0]
    if "MINIMAX_API_KEY" not in values:
        fail("MiniMax environment file does not configure MINIMAX_API_KEY.")
    for key in keys:
        env.pop(key, None)
    env.update(values)


def local_minimax_file(explicit):
    if explicit is not None:
        return explicit
    pointer = PROJECT / ".local" / "content-assistant-minimax-env"
    if pointer.is_file():
        path = pointer.read_text(encoding="utf-8").strip()
        if not path or not Path(path).expanduser().is_absolute():
            fail("Local MiniMax environment pointer must contain one absolute dotenv path.")
        return Path(path)
    return None


def java17(env):
    if sys.platform == "darwin":
        java_home = MAC_JAVA_HOME
    else:
        java_home = Path(env["JAVA_HOME"]).expanduser() if env.get("JAVA_HOME") else None
    java = str(java_home / "bin/java") if java_home else shutil.which("java", path=env.get("PATH"))
    if not java:
        fail("Java 17 is required; set JAVA_HOME.")
    try:
        version = subprocess.run([java, "-version"], env=env, capture_output=True,
                                 text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        fail("Cannot run Java 17; check JAVA_HOME.")
    if version.returncode or not re.search(r'version "17(?:[."]|$)', version.stdout + version.stderr):
        fail("Java 17 is required; the selected runtime is not Java 17.")
    if java_home:
        env["JAVA_HOME"] = str(java_home)
    return java


def stage_jar(source, runtime):
    releases = runtime / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=releases, suffix=".jar", delete=False) as stream:
            temporary = Path(stream.name)
            with source.open("rb") as original:
                shutil.copyfileobj(original, stream)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        target = releases / ("x-community-hub-" + digest[:16] + ".jar")
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                fail("Existing versioned JAR does not match its expected digest.")
        else:
            os.replace(temporary, target)
        return target, digest
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("local", "preview", "production"))
    parser.add_argument("--jar", type=Path, default=PROJECT / "target/x-community-hub.jar")
    parser.add_argument("--port", type=int, help="local/production: 8080; preview: 0 (temporary port)")
    parser.add_argument("--runtime-dir", type=Path, help="required existing production runtime directory")
    parser.add_argument("--public-url", default="https://x.life-benchmark.com",
                        help="production HTTPS public origin")
    parser.add_argument("--minimax-env-file", type=Path,
                        help="local only: load MiniMax configuration from an existing private dotenv file")
    args = parser.parse_args()
    if args.minimax_env_file is not None and args.mode != "local":
        parser.error("--minimax-env-file is only supported in local mode.")
    port = args.port if args.port is not None else (0 if args.mode == "preview" else 8080)
    if not 0 <= port <= 65535 or (args.mode != "preview" and port == 0):
        parser.error("--port must be 1..65535 (preview also accepts 0).")
    if args.mode == "preview" and port == 8080:
        parser.error("Preview cannot use port 8080; keep the local login environment available.")
    if args.mode != "production" and args.runtime_dir is not None:
        parser.error("--runtime-dir is only supported for production.")
    source = args.jar.expanduser().resolve()
    if not source.is_file():
        parser.error("--jar must point to an existing built JAR.")

    if args.mode == "production":
        if args.runtime_dir is None:
            parser.error("Production requires --runtime-dir.")
        runtime = args.runtime_dir.expanduser().resolve()
        if runtime == PROJECT or runtime.is_relative_to((RUNTIMES / "preview").resolve()) \
                or runtime.is_relative_to((RUNTIMES / "local").resolve()):
            parser.error("Production requires a directory separate from the project, local and preview runtimes.")
        if not runtime.is_dir():
            parser.error("Production --runtime-dir must already exist.")
        public = urlsplit(args.public_url)
        if public.scheme != "https" or not public.hostname or public.username or public.password \
                or public.path not in ("", "/") or public.query or public.fragment:
            parser.error("--public-url must be an HTTPS origin without credentials, path, query or fragment.")
        try:
            public.port
        except ValueError:
            parser.error("--public-url contains an invalid port.")
        callback = args.public_url.rstrip("/") + "/api/auth/x/callback"
        cwd = runtime
    elif args.mode == "local":
        runtime = (RUNTIMES / "local").resolve()
        cwd = PROJECT
        callback = "http://localhost:" + str(port) + "/api/auth/x/callback"
    else:
        runtime = (RUNTIMES / "preview").resolve()
        cwd = runtime
        callback = ""

    private_config = cwd / "application-local.yml"
    has_private_config = private_config.is_file() if args.mode != "preview" else False
    if args.mode != "preview" and not has_private_config and not os.environ.get("X_OAUTH_CLIENT_ID", "").strip():
        fail("The selected environment requires its own application-local.yml or X_OAUTH_CLIENT_ID environment configuration.")
    env = isolated_environment()
    if args.mode == "local":
        minimax_file = local_minimax_file(args.minimax_env_file)
        if minimax_file is not None:
            try:
                load_local_minimax(env, minimax_file)
            except (OSError, UnicodeError):
                fail("Could not read the local MiniMax environment file.")
        doh = PROJECT / ".local" / "content-assistant-doh-url"
        if doh.is_file() and not env.get("X_CONTENT_ASSISTANT_DOH_URL"):
            try:
                env["X_CONTENT_ASSISTANT_DOH_URL"] = doh.read_text(encoding="utf-8").strip()
            except (OSError, UnicodeError):
                fail("Could not read the local content assistant DNS configuration.")
    if args.mode == "preview":
        for key in ("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "X_CONTENT_ASSISTANT_COMMANDCODE_API_KEY",
                    "X_CONTENT_ASSISTANT_COMMANDCODE_BASE_URL", "X_CONTENT_ASSISTANT_DOH_URL",
                    "MINIMAX_API_KEY", "MINIMAX_BASE_URL"):
            env.pop(key, None)
    java = java17(env)
    runtime.mkdir(parents=True, exist_ok=True)
    immutable_jar, digest = stage_jar(source, runtime)
    # Only Spring opens the existing private configuration; this launcher never reads it.
    config_import = private_config.as_uri() if has_private_config else ""
    options = {
        "spring.config.location": "classpath:/application.yml",
        "spring.config.additional-location": "",
        "spring.config.import": config_import,
        "spring.profiles.active": args.mode,
        "spring.profiles.include": "",
        "server.address": "127.0.0.1",
        "server.port": str(port),
        "server.servlet.session.cookie.secure": str(args.mode == "production").lower(),
        "xops.require-login-self-only": str(args.mode != "preview").lower(),
        "xops.oauth-redirect-uri": callback,
    }
    if args.mode != "production":
        options.update({
            "xops.scheduler-enabled": "false", "xops.auto-repost-enabled": "false",
            "xops.top50-auto-sync-enabled": "false",
            "xops.top10-silent-history-enabled": "false",
            "xops.content-assistant-background-collection-enabled": "false",
            "server.forward-headers-strategy": "none",
            "server.servlet.session.store-dir": str(cwd / ".sessions"),
            "xops.disk-cache-dir": str(cwd / ".cache/snapshots"),
            "xops.algo-profile-path": str(cwd / ".cache/x-algorithm-profile.json"),
        })
    if args.mode == "preview":
        options.update({
            "xops.default-provider": "DEMO",
            "xops.fxtwitter-enabled": "false",
            "xops.twitterapi-io-key": "",
            "xops.official-bearer-token": "",
            "xops.oauth-client-id": "",
            "xops.oauth-client-secret": "",
            "xops.token-encryption-secret": "isolated-preview-only",
            "xops.twitterapi-io-base-url": "http://127.0.0.1:9",
            "xops.official-base-url": "http://127.0.0.1:9/2",
            "xops.official-proxy-url": "",
            "xops.hourly-upstream-api-budget": "0",
            "xops.daily-upstream-api-budget": "0",
            "xops.hourly-upstream-usd-budget": "0",
            "xops.daily-upstream-usd-budget": "0",
            "xops.monthly-upstream-usd-budget": "0",
            "server.servlet.session.persistent": "false",
        })
        for key in ("X_TWITTERAPI_IO_KEY", "X_OFFICIAL_BEARER_TOKEN", "X_OFFICIAL_PROXY_URL",
                    "X_OAUTH_CLIENT_ID", "X_OAUTH_CLIENT_SECRET", "X_TOKEN_ENCRYPTION_SECRET"):
            env.pop(key, None)
    command = [java]
    if args.mode != "production":
        command.extend(["-Xms64m", "-Xmx512m", "-XX:ActiveProcessorCount=4", "-Djava.net.preferIPv4Stack=true"])
    command.extend(["-jar", str(immutable_jar)])
    command.extend("--" + key + "=" + value for key, value in options.items())
    print("Environment: " + args.mode + "; loopback port: " + str(port), flush=True)
    print("Runtime JAR: " + str(immutable_jar), flush=True)
    print("SHA-256: " + digest, flush=True)
    os.chdir(cwd)
    os.execve(java, command, env)


if __name__ == "__main__":
    main()
