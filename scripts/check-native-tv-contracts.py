#!/usr/bin/env python3
"""Check native TV source/packaging boundaries, not runtime feature parity.

The unchanged-server guard compares tracked and untracked files to --baseline
(default HEAD). Give the pre-migration revision to verify a committed change.
APK checks are opt-in and do not replace instrumentation, signing/alignment
verification, live-provider tests, or physical TV/USB acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = Path("androidtv/app/src/main/java/app/seanime/tv")
FEATURES = {"LIBRARY", "ANILIST", "MANGA", "OFFLINE", "PLAYLISTS", "EXTENSIONS", "STREAMING", "DOWNLOADS", "NAKAMA", "SETTINGS", "LOGS"}
REQUIRED_LIBRARIES = {"libgojni.so", "libffmpeg.so", "libffprobe.so", "libc++_shared.so"}
# These are approved Go provider payloads, never Android presentation code.
# Exact source bytes from Seanime-contributions/Seanime-Providers@641b9d842e5502d34fb923dac959129d3da9fc1a.
APPROVED_PROVIDER_ASSETS = {
    "assets/providers/animeheaven/provider.js": "bd26ab1d453a19694c36da8d9ab54b29aed1d0292d053d4d14b5e7042e2f9452",
    "assets/providers/anidb/provider.js": "065ec31a990f7196605f4a7ab67b5441d2c709a3f28e9272752c104c6f90502d",
    "assets/providers/atsumaru/provider.js": "605a77d6807f123050405e6136dc19f43aea1680375cecb79572b7352f1a5070",
}


def forbidden_apk_asset(name: str, content: bytes) -> bool:
    if name in APPROVED_PROVIDER_ASSETS:
        return hashlib.sha256(content).hexdigest() != APPROVED_PROVIDER_ASSETS[name]
    return bool(re.search(r'lib(?:reactnative|hermes)|assets/(?:.*\/)?(?:index\.html|.*\.(?:js|jsx|tsx|bundle))$', name))


def read(path: str | Path) -> str:
    file = ROOT / path
    return file.read_text(encoding="utf-8") if file.exists() else ""


def kotlin_code(text: str) -> str:
    # Remove ordinary comments while retaining string contents and line boundaries.
    token = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*')
    return token.sub(lambda match: "\n" * match[0].count("\n") if match[0].startswith(("/*", "//")) else match[0], text)


def backend_routes(text: str) -> set[tuple[str, str]]:
    groups = {"e": "", "v1": "/api/v1"}
    for name, parent, suffix in re.findall(r'\b(\w+)\s*:=\s*(\w+)\.Group\("([^"]+)"\)', text):
        if name == "v1":
            continue  # v1 is the chained /api then /v1 group.
        if parent in groups:
            groups[name] = groups[parent] + suffix
    return {(method, groups[group] + path) for group, method, path in re.findall(r'\b(\w+)\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\("([^"]+)"', text) if group in groups}


def client_routes(text: str, repository: bool = False) -> set[tuple[str, str]]:
    calls = set(re.findall(r'\brequest\(\s*"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)"\s*,\s*"(/api/v1[^"]*)"', text))
    if repository:
        calls.update((method.upper(), "/api/v1" + path) for method, path in re.findall(r'(?<![\w.])(get|post)\(\s*"(/[^"\n]*)"', text))
    return {(method, path.split("?", 1)[0]) for method, path in calls if path != "/api/v1$path"}


def route_matches(client: str, server: str) -> bool:
    # Kotlin interpolated path components and Echo :parameters both match one
    # component. Echo wildcard routes may match any suffix.
    client = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_]\w*', "__PARAM__", client)
    server_parts, client_parts = server.strip("/").split("/"), client.strip("/").split("/")
    for index, expected in enumerate(server_parts):
        if expected == "*":
            return True
        if index >= len(client_parts):
            return False
        actual = client_parts[index]
        if not (expected.startswith(":") or actual == "__PARAM__" or expected == actual):
            return False
    return len(server_parts) == len(client_parts)


def player_presentation_contract(text: str) -> dict[str, object]:
    code = kotlin_code(text)
    host = bool(re.search(r"\bsetContent\s*\{", code)) and (
        "ComponentActivity" in code or "ComposeView" in code
    )
    controller_disabled = bool(re.search(r"\buseController\s*=\s*false\b", code))
    forbidden = [name for name, pattern in {
        "built-in Media3 controller": r"\buseController\s*=\s*true\b|\bPlayerControlView\b",
        "legacy widget controls": r"android\.widget\.(?:Button|TextView|LinearLayout)\b",
        "legacy Android dialog": r"android\.app\.AlertDialog\b",
        "built-in track picker": r"\bTrackSelectionDialogBuilder\b",
    }.items() if re.search(pattern, code)]
    return {"compose_host": host, "media3_controller_disabled": controller_disabled,
            "retained_legacy_presentation": forbidden,
            "passed": host and controller_disabled and not forbidden}


def native_chrome_contract(auth: str, main: str, platform: str, policy: str) -> dict[str, object]:
    auth, main, platform, policy = map(kotlin_code, (auth, main, platform, policy))
    violations = [label for label, code in (("Auth", auth), ("Main", main), ("NativePlatformActions", platform))
                  if re.search(r"\bsetContentView\s*\(|android\.widget\.(?:Button|TextView|LinearLayout|Toast)\b|android\.app\.AlertDialog\b", code)]
    compose_auth = "ComponentActivity" in auth and bool(re.search(r"\bsetContent\s*\{", auth))
    provider_gate = all(value in auth for value in ("AuthBrowserPolicy.isProviderUrl", "AndroidView", 'testTag("provider-login-page")',
        "allowFileAccess = false", "allowContentAccess = false", "MIXED_CONTENT_NEVER_ALLOW"))
    hosts_match = re.search(r"providerHosts\s*=\s*setOf\(([^)]*)\)", policy)
    hosts = set(re.findall(r'"([^"\n]+)"', hosts_match[1])) if hosts_match else set()
    policy_gate = hosts == {"anilist.co", "myanimelist.net", "www.myanimelist.net"} and all(value in policy for value in
        ('uri.scheme == "https"', "uri.rawUserInfo == null", "uri.port in setOf(-1, 443)"))
    prompts = "onPrompt" in platform and "PlatformPromptDialog" in main and "PlatformPromptDialog" in auth
    return {"compose_auth_chrome": compose_auth, "provider_only_browser": provider_gate and policy_gate,
            "compose_platform_prompts": prompts, "legacy_presentation_files": violations,
            "passed": compose_auth and provider_gate and policy_gate and prompts and not violations}


def check_source(baseline: str) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []

    def check(name: str, passed: bool, detail: object) -> None:
        results.append({"check": name, "passed": bool(passed), "detail": detail})

    main = kotlin_code(read(NATIVE / "MainActivity.kt"))
    check("native-main-activity", "ComponentActivity" in main and "setContent" in main,
          "MainActivity must host Compose in ComponentActivity")
    player_contract = player_presentation_contract(read(NATIVE / "NativePlayerActivity.kt"))
    check("fresh-compose-player-presentation", player_contract["passed"], player_contract)
    chrome_contract = native_chrome_contract(read(NATIVE / "AuthWebViewActivity.kt"), main,
        read(NATIVE / "platform/NativePlatformActions.kt"), read(NATIVE / "platform/AuthBrowserPolicy.kt"))
    check("fresh-compose-auth-and-platform-chrome", chrome_contract["passed"], chrome_contract)
    violations = []
    for path in sorted((ROOT / NATIVE).rglob("*")):
        if path.suffix not in {".kt", ".java"} or path.name == "AuthWebViewActivity.kt":
            continue
        code = kotlin_code(path.read_text(encoding="utf-8"))
        if re.search(r'\b(?:WebView|JavascriptInterface|ReactRootView|ReactActivity|ReactNativeHost)\b|evaluateJavascript\s*\(', code):
            violations.append(str(path.relative_to(ROOT)))
    check("no-web-or-react-ui", not violations, violations or "OAuth-only AuthWebViewActivity is the sole WebView exception")
    gradle = read("androidtv/app/build.gradle.kts")
    check("no-react-build-or-embed-task", not re.search(r'buildAndroidWeb|embedAndroidWeb|out-androidtv|commandLine\("(?:npm|node|yarn|pnpm)"', gradle),
          "Android build must not execute or package the React frontend")
    web_assets = [str(path.relative_to(ROOT)) for path in (ROOT / "mobile/web").rglob("*") if path.is_file() and path.name != ".gitkeep"]
    check("no-embedded-web-assets", not web_assets, web_assets or "mobile/web contains only its unchanged Go embed placeholder")
    check("native-compose-tv-dependencies", "compose = true" in gradle and "androidx.tv:tv-material" in gradle and "androidx.activity:activity-compose" in gradle,
          "Compose and TV Material must be actual build dependencies")
    check("real-media3-go-ffmpeg-build", all(value in gradle for value in ("androidx.media3:media3-exoplayer", "buildAndroidFfmpeg", "bindGoMobile", "stageAndroidNdkCppRuntime"))
          and any(target in gradle for target in ('"./mobile"', '"seanime/mobile"')),
          "Native runtime tasks and the real mobile package/Media3 dependency must remain")
    ui = "\n".join(kotlin_code(path.read_text()) for path in sorted((ROOT / NATIVE / "ui").glob("*.kt")))
    feature_match = re.search(r'enum class TvFeature\b[\s\S]*?\{([\s\S]*?)\n\}', ui)
    routes = set(re.findall(r'^\s*([A-Z][A-Z_]*)\s*\(', feature_match[1], re.M)) if feature_match else set()
    check("feature-route-scope", FEATURES <= routes, {"required": sorted(FEATURES), "found": sorted(routes), "missing": sorted(FEATURES - routes)})
    required_tags = ("native-tv-root", "anime-search-field", "anime-search-submit", "exit-confirm")
    all_native = main + ui
    check("native-test-semantics", all(f'"{tag}"' in all_native for tag in required_tags) and '"nav-${feature.name}"' in ui,
          {"required": required_tags, "rail": "nav-${feature.name}"})
    check("native-tv-back-navigation", "BackHandler" in ui and "FocusRequester" in ui,
          "Source must include native Back and focus management; runtime quality needs device checks")
    # API23 ignores Network Security Config, while API24+ must retain the
    # canonical-loopback-only exception. This checks configuration, not an API23 run.
    manifest = read("androidtv/app/src/main/AndroidManifest.xml")
    resource = "allow_legacy_loopback_http"
    def legacy_policy(folder: str) -> str | None:
        path = ROOT / "androidtv/app/src/main/res" / folder
        for xml in path.glob("*.xml"):
            for value in ET.parse(xml).getroot().findall("bool"):
                if value.get("name") == resource:
                    return (value.text or "").strip()
        return None
    config = ET.fromstring(read("androidtv/app/src/main/res/xml/network_security_config.xml"))
    cleartext_domains = []
    unsafe_config = False
    for entry in config.iter():
        if entry.get("cleartextTrafficPermitted") == "true":
            if entry.tag != "domain-config":
                unsafe_config = True
            for domain in entry.findall("domain"):
                cleartext_domains.append((domain.text or "").strip())
                if domain.get("includeSubdomains") == "true":
                    unsafe_config = True
    check("api23-loopback-policy", f'android:usesCleartextTraffic="@bool/{resource}"' in manifest
          and legacy_policy("values") == "true" and legacy_policy("values-v24") == "false"
          and set(cleartext_domains) == {"127.0.0.1"} and not unsafe_config,
          "Legacy boolean allows the unchanged HTTP loopback server on API23; API24+ keeps only canonical loopback cleartext")
    api = kotlin_code(read(NATIVE / "data/SeanimeApiClient.kt"))
    check("same-origin-client-auth", all(value in api for value in ("X-Seanime-Client-Platform", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "X-Seanime-Token", "isServerUrl", "followRedirects(false)")),
          "HTTP credentials require platform/client identity and same-origin enforcement")
    backend = backend_routes(read("internal/handlers/routes.go"))
    called: set[tuple[str, str]] = set()
    for path in sorted((ROOT / NATIVE).rglob("*.kt")):
        called.update(client_routes(kotlin_code(path.read_text()), repository=path.name == "SeanimeRepository.kt"))
    unknown = sorted((method, path) for method, path in called if not any(method == server_method and route_matches(path, server_path) for server_method, server_path in backend))
    check("existing-backend-route-contracts", bool(called) and not unknown,
          {"literal_calls_checked": len(called), "unknown": unknown, "limitation": "Checks literal HTTP method/path contracts, not payload schemas, dynamic actions, or server behavior"})
    try:
        tracked = subprocess.check_output(["git", "diff", "--name-only", baseline, "--"], cwd=ROOT, text=True).splitlines()
        untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
        changed_server = sorted({path for path in tracked + untracked if path.endswith(".go") or path in {"go.mod", "go.sum"} or path.startswith(("internal/", "mobile/"))})
        check("go-server-unchanged", not changed_server, {"baseline": baseline, "changed": changed_server})
    except subprocess.CalledProcessError as failure:
        check("go-server-unchanged", False, f"Cannot compare baseline {baseline}: {failure}")
    return results


def check_apk(path: Path) -> dict[str, object]:
    try:
        with zipfile.ZipFile(path) as apk:
            names = apk.namelist()
            abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and len(name.split("/")) == 3})
            libraries = {abi: {Path(name).name for name in names if name.startswith(f"lib/{abi}/")} for abi in abis}
            missing = {abi: sorted(REQUIRED_LIBRARIES - libs) for abi, libs in libraries.items() if REQUIRED_LIBRARIES - libs}
            forbidden = [name for name in names if forbidden_apk_asset(name, apk.read(name) if name in APPROVED_PROVIDER_ASSETS else b"")]
            invalid_elf = []
            library_sizes = {}
            for abi in abis:
                for library in REQUIRED_LIBRARIES & libraries[abi]:
                    name = f"lib/{abi}/{library}"
                    with apk.open(name) as stream:
                        header = stream.read(20)
                    size = apk.getinfo(name).file_size
                    library_sizes[name] = size
                    machine = int.from_bytes(header[18:20], "little") if len(header) == 20 else -1
                    if header[:6] != b"\x7fELF\x02\x01" or machine != {"arm64-v8a": 183, "x86_64": 62}.get(abi) or size < 4096:
                        invalid_elf.append(name)
            passed = len(abis) == 1 and abis[0] in {"arm64-v8a", "x86_64"} and not missing and not forbidden and not invalid_elf and "classes.dex" in names
            return {"check": f"apk-native-runtime:{path.name}", "passed": passed, "detail": {"abis": abis, "missing_libraries": missing, "invalid_elf": invalid_elf, "native_library_bytes": library_sizes, "forbidden_web_assets": forbidden}}
    except (OSError, zipfile.BadZipFile) as failure:
        return {"check": f"apk-native-runtime:{path.name}", "passed": False, "detail": str(failure)}


class ScannerTests(unittest.TestCase):
    def test_only_exact_approved_provider_payloads_are_allowed(self):
        for name in APPROVED_PROVIDER_ASSETS:
            content = (ROOT / "androidtv/app/src/main" / name).read_bytes()
            self.assertFalse(forbidden_apk_asset(name, content), name)
            self.assertTrue(forbidden_apk_asset(name, content + b"\n// changed"), name)
            self.assertTrue(forbidden_apk_asset(name.replace("provider.js", "other.js"), content), name)
        for name in ("assets/index.html", "assets/index.android.bundle", "assets/react/app.js", "assets/providers/other/provider.js", "lib/x86_64/libhermes.so"):
            self.assertTrue(forbidden_apk_asset(name, b"provider"), name)

    def test_comments_do_not_create_webview_false_positive(self):
        code = kotlin_code('// WebView\n/* ReactActivity */\nval example = "http://localhost/"\n')
        self.assertNotIn("WebView", code)
        self.assertIn('"http://localhost/"', code)

    def test_exact_path_and_method_extraction(self):
        self.assertEqual(backend_routes('v1.GET("/status", h.Status)\nv1.POST("/start", h.Start)'), {("GET", "/api/v1/status"), ("POST", "/api/v1/start")})
        self.assertEqual(client_routes('get("/status"); request("PATCH", "/api/v1/settings")', True), {("GET", "/api/v1/status"), ("PATCH", "/api/v1/settings")})

    def test_nested_echo_groups(self):
        routes = backend_routes('v1 := e.Group("/api").Group("/v1")\n'
            'v1Library := v1.Group("/library")\n'
            'v1Explorer := v1Library.Group("/explorer")\n'
            'v1Library.GET("/collection", h.Collection)\n'
            'v1Explorer.POST("/move", h.Move)')
        self.assertIn(("GET", "/api/v1/library/collection"), routes)
        self.assertIn(("POST", "/api/v1/library/explorer/move"), routes)

    def test_player_presentation_accepts_compose_with_video_only_media3(self):
        for host in ("class NativePlayerActivity : ComponentActivity()", "val root = ComposeView(this)"):
            self.assertTrue(player_presentation_contract(host + " setContent { NativePlayerScreen() }; PlayerView(this).apply { useController = false }")["passed"])

    def test_player_presentation_rejects_legacy_controls_or_controller(self):
        prefix = "class NativePlayerActivity : ComponentActivity() { setContent { PlayerScreen() }; useController = false; "
        for control in ("useController = true", "android.widget.Button", "android.widget.TextView", "android.widget.LinearLayout", "android.app.AlertDialog.Builder(this)", "TrackSelectionDialogBuilder", "PlayerControlView"):
            self.assertFalse(player_presentation_contract(prefix + control)["passed"], control)
        self.assertFalse(player_presentation_contract("class NativePlayerActivity : Activity() { setContentView(frame); useController = false }")["passed"])
        self.assertFalse(player_presentation_contract("class NativePlayerActivity : ComponentActivity() { setContent { Screen() }; /* useController = false */ }")["passed"])

    def test_auth_chrome_rejects_legacy_layout_and_broad_browser_policy(self):
        auth = 'class Auth : ComponentActivity() { setContent { AndroidView(); testTag("provider-login-page"); PlatformPromptDialog() }; AuthBrowserPolicy.isProviderUrl(); allowFileAccess = false; allowContentAccess = false; MIXED_CONTENT_NEVER_ALLOW }'
        policy = 'providerHosts = setOf("anilist.co", "myanimelist.net", "www.myanimelist.net"); uri.scheme == "https"; uri.rawUserInfo == null; uri.port in setOf(-1, 443)'
        self.assertTrue(native_chrome_contract(auth, "PlatformPromptDialog", "onPrompt", policy)["passed"])
        for legacy in ("setContentView(frame)", "android.app.AlertDialog", "android.widget.Toast"):
            self.assertFalse(native_chrome_contract(auth, "PlatformPromptDialog " + legacy, "onPrompt", policy)["passed"])
        self.assertFalse(native_chrome_contract(auth, "PlatformPromptDialog", "onPrompt", policy.replace('"anilist.co"', '"example.com"'))["passed"])
        self.assertFalse(native_chrome_contract(auth, "PlatformPromptDialog", "onPrompt", policy.replace('"https"', '"http"'))["passed"])

    def test_interpolated_segments(self):
        self.assertTrue(route_matches('/api/v1/item/${encodePathSegment(id)}', '/api/v1/item/:id'))
        self.assertTrue(route_matches('/api/v1/log/$name', '/api/v1/log/*'))
        self.assertFalse(route_matches('/api/v1/items/$id', '/api/v1/item/:id'))
        self.assertFalse(route_matches('/api/v1/item/$id/more', '/api/v1/item/:id'))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="HEAD")
    parser.add_argument("--apk", action="append", type=Path, default=[])
    parser.add_argument("--json", action="store_true", help="Emit machine-readable results")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return 0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ScannerTests)).wasSuccessful() else 1
    results = check_source(args.baseline) + [check_apk(path) for path in args.apk]
    if args.json:
        print(json.dumps({"scope": "Static native boundaries and literal endpoint contracts; no runtime parity claim", "results": results}, indent=2))
    else:
        for result in results:
            print(f"{'PASS' if result['passed'] else 'FAIL'} {result['check']}: {result['detail']}")
        print("Static source checks do not establish runtime or complete feature parity.")
    return 0 if all(result["passed"] for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
