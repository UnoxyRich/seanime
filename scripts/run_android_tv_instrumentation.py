#!/usr/bin/env python3
"""Run the connected suite and retain bounded fixture evidence before AVD teardown."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile


PACKAGE = "app.seanime.tv"
TEST_PACKAGE = PACKAGE + ".test"
OWNED_JOURNEY = "app.seanime.tv.AndroidIsolatedLibraryPlaybackJourneyTest#remoteOnlyLibraryFilesAndExplorerPlayOwnedMultitrackVideo"
JOURNEY_KIND = "native-isolated-go-library-playback-journey-v1"
OWNED_FIXTURES = {
    "owned-library-journey": {
        "selector": OWNED_JOURNEY,
        "flag": "isolatedNativeGoLibraryPlaybackJourneyFixture",
        "kind": JOURNEY_KIND,
        "statusKey": "ownedJourneyManifest",
    },
    "owned-library-management": {
        "selector": "app.seanime.tv.AndroidIsolatedLibraryManagementTest#importedOwnedUnmatchedIndexSupportsNativeBulkRenameExplorerAndDelete",
        "flag": "isolatedNativeGoLibraryManagementFixture",
        "kind": "native-isolated-go-library-management-v1",
        "statusKey": "ownedFixtureManifest",
    },
    "owned-raw-media": {
        "selector": "app.seanime.tv.AndroidIsolatedRawMediaPlaybackTest#generatedVideoUsesSignedGoRangeRouteAndNativePlayerWithoutMetadata",
        "flag": "isolatedNativeGoRawMediaFixture",
        "kind": "native-isolated-go-raw-media-v1",
        "statusKey": "ownedFixtureManifest",
    },
    "owned-external-player": {
        "selector": "app.seanime.tv.AndroidIsolatedRawMediaPlaybackTest#nativeMoreHandsOwnedGoVideoToSeparatePlayerAcrossBackgroundAndReturnsPaused",
        "flag": "isolatedNativeGoExternalPlayerFixture",
        "kind": "native-isolated-go-external-player-v1",
        "statusKey": "ownedFixtureManifest",
    },
}
RUNNER_ARGUMENT = "-Pandroid.testInstrumentationRunnerArguments."
FIXTURE_DIRECTORY = r"native-go-fixture-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
# AGP 8.10.1 defaults this Stable option to false, which makes UTP uninstall
# app/test APKs (and app-private evidence) before connectedAndroidTest returns.
# Retain them only on the explicit disposable CI emulator until AVD teardown.
KEEP_APKS_ARGUMENT = "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
COMMAND = ["./gradlew", ":app:connectedDebugAndroidTest", "--no-daemon", KEEP_APKS_ARGUMENT]
OUTPUT = "androidtv/build/native-ci-evidence/connected-suite"
REMOTE_VIDEO = "/data/local/tmp/seanime-native-ci-startup.mp4"
VIDEO_SECONDS = 60
VIDEO_BIT_RATE = 2_000_000
OWNED_JOURNEY_VIDEO_SECONDS = 120
OWNED_JOURNEY_VIDEO_BIT_RATE = 1_000_000
VIDEO_MAX_BYTES = 32 * 1024 * 1024
APP_WAIT_SECONDS = 180
NO_SCREENSHOTS_RECEIPT = b"seanime-native-screenshots-not-created\n"
PLAYER_LIFECYCLE_PATH = "cache/native-acceptance-diagnostics/player-lifecycle-recreation.json"
PLAYER_LIFECYCLE_MAX_BYTES = 65_536
PLAYER_LIFECYCLE_STAGES = ("initial-autoplay", "resume-after-stop", "paused-media-handoff",
                           "playing-media-handoff", "activity-recreation")
PLUGIN_STARTUP_TESTS = (
    "globalTrayOpenRendersOnlyItsSurfaceAndBackRestoresTheOpener",
    "globalCommandsUseTheirOwnerAndIgnoreUnrelatedCloseRequests",
    "relativeAnchorItemsNavigateNativelyAndHandlerLinksKeepTheirCallbackContract",
)
PLUGIN_STARTUP_MAX_BYTES = 8192
NATIVE_SEARCH_HARDWARE_INPUT_PATH = "cache/native-acceptance-diagnostics/native-search-hardware-input.json"
NATIVE_SEARCH_HARDWARE_INPUT_MAX_BYTES = 8192
NATIVE_SEARCH_HARDWARE_INPUT_TEST = "app.seanime.tv.AndroidTvStartupTest#remoteLetterKeysEnterNativeSearchWithoutStealingCursorFocus"
NATIVE_SEARCH_HARDWARE_INPUT_STAGES = (
    ("before-a", 0), ("after-a", 1), ("before-b", 1), ("after-b", 2),
    ("text-delivered", 2), ("after-left", 3),
)
NATIVE_SETTINGS_PRESERVATION_PATH = "cache/native-acceptance-diagnostics/native-settings-preservation.json"
NATIVE_SETTINGS_PRESERVATION_MAX_BYTES = 16384
NATIVE_SETTINGS_PRESERVATION_TEST = "app.seanime.tv.AndroidNativeBackendFlowsTest#typedSettingsPersistPreserveOtherFieldsAndRejectInvalidWritesAtomically"
NATIVE_SETTINGS_PRESERVATION_PHASES = (
    "after-boolean", "after-number", "after-choice", "rejected-write", "fresh-client", "restored",
)
NATIVE_SETTINGS_PRESERVATION_SECTIONS = frozenset((
    "id", "library", "mediaPlayer", "torrent", "manga", "anilist", "listSync",
    "autoDownloader", "discord", "notifications", "nakama",
))
NATIVE_SETTINGS_PRESERVATION_FIELDS = frozenset((
    "hideAudienceScore", "scannerMatchingThreshold", "scannerMatchingAlgorithm", "libraryPath", "libraryPaths",
    "richPresenceUseMediaTitleStatus", "richPresenceShowAniListMediaButton",
))
NATIVE_SETTINGS_PRESERVATION_TYPES = ("missing", "null", "boolean", "number", "string", "object", "array")
PLUGIN_STARTUP_EVENT_TYPES = frozenset((
    "screen:changed", "tray:render", "tray:opened", "tray:list-icons", "tray:closed", "handler:triggered",
    "command-palette:render", "command-palette:opened", "command-palette:list", "command-palette:input",
    "command-palette:item-selected", "command-palette:closed", "other",
))
# Exact NativeScreenshotEvidence names, including the two parameterized fixtures.
# Deliberately do not enumerate or copy any other application cache files.
SCENARIOS = frozenset("""
auto-batch-explicit-rule-preview
auto-batch-partial-creation-results
auto-finished-rule-cleanup-preview
discovery-compact-toolbar-dpad-card-focus
discovery-native-advanced-filters
discovery-page-two-restored-card-focus
existing-debrid-download-requested
file-match-retained-retry-draft
isolated-go-external-open-focus
isolated-go-external-receiver-streaming
isolated-go-external-return-paused
isolated-go-generated-local-media
isolated-go-generated-local-media-resumed
isolated-go-library-bulk-ignore
isolated-go-library-delete-verified
isolated-go-library-explorer
isolated-go-raw-route-native-player
library-empty-rail-focus
library-fixture-restored-card-focus
library-folder-typed-match-preview
library-multi-file-confirm-retry
library-rename-retained-filename
list-entry-main-status-focus
manga-discovery-filtered-page-restoration
manga-language-filter-remote-focus
manga-queue-live-terminal-clear
manga-reader-rtl-two-page-fixture
manga-reader-wide-page-cover-fixture
manga-scanlator-filter-retry-draft
native-action-row-first-focus
native-action-row-last-focus
native-airing-page-two-restored-focus
native-custom-source-large-id-return
native-extension-config-repaired
native-library-issue-report-ready
native-marketplace-install-retry
native-setting-choice-scaled-focus
native-title-picker-search-focus
native-torrent-downloads-row-return
native-torrent-editor-return-failure
native-torrent-file-priority-return
native-torrent-peers
native-torrent-session-limits-accepted
native-torrent-session-limits-failure
offline-metadata-server-finished
offline-poster-native-render-focus
owned-go-journey-audio-second-track
owned-go-journey-decoded-video
owned-go-journey-explorer-play-focus
owned-go-journey-explorer-return-focus
owned-go-journey-files-play-focus
owned-go-journey-files-return-focus
owned-go-journey-library-manage
owned-go-journey-picture-mode-c
owned-go-journey-picture-off-return
owned-go-journey-seek-focus
owned-go-journey-subtitle-enabled
owned-go-journey-subtitle-off
personal-collection-large-id-restoration
personal-collection-editor-return-failure
personal-collection-restored-filtered-card
platform-confirm-cancel-focus
player-error-convert-focus
player-error-external-route-focus
player-error-retry-focus
player-fresh-anime4k-dialog
player-fresh-audio-dialog
player-fresh-hud-audio-focus
player-fresh-hud-play-focus
player-hls-original-ass
player-hls-original-pgs
player-many-tracks-close-focus
player-many-tracks-selected-18
player-many-tracks-selected-20
plugin-action-unloaded-native-focus
plugin-ambiguous-late-collection-rejected
plugin-episode-source-manual-fallback
plugin-global-native-entry-navigation
plugin-global-native-tray
plugin-native-clipboard-confirmation
real-go-anilist-options-account-focus
real-go-library-home-rail-focus
real-go-library-search-toolbar-focus
real-go-logs-rail-safe-focus
real-go-playlist-cancel-focus
real-go-playlist-save-focus
real-go-search-query-restored-after-background
real-go-settings-rail-safe-focus
settings-category-row-restored
settings-device-before-return
settings-device-reopen-focus-failure
settings-device-last-row-restored
settings-library-before-return
settings-library-reopen-focus-failure
source-bounded-query-editor
source-debrid-download-requested
source-modes-debrid-back-focused
source-modes-torrent-back-focused
source-provider-debrid-focused
source-provider-startup-debrid-failure
source-provider-startup-torrent-failure
source-provider-torrent-focused
source-search-download-requested
text-entry-ime-multiline-failure
text-entry-ime-multiline-shown
text-entry-ime-numeric-failure
text-entry-ime-numeric-shown
text-entry-long-helper-focused
text-entry-numeric-ime-full-footer
""".split())
EXPECTED_FILES = {f"{name}.{ext}" for name in SCENARIOS for ext in ("png", "json")}
APK_PATHS = (
    "androidtv/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk",
    "androidtv/app/build/outputs/apk/debug/app-x86_64-debug.apk",
    "androidtv/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
)


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def adb_command():
    # Never fall back to adb's default device, which could be a personal device.
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("An explicit CI emulator serial is required")
    return ["adb", "-s", serial]


def exec_out_run_as(*arguments):
    # adb exec-out itself escapes each argument after the executable. Unlike
    # adb shell, pre-quoting a sh -c script here adds a second quoting layer.
    # The raw exec service also merges remote stderr and does not return the
    # remote exit code: validate the payload, never just adb's return code.
    # https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/commandline.cpp
    return adb_command() + ["exec-out", "run-as", PACKAGE, *arguments]


def probe_app_evidence_access():
    """Report fixed diagnostic codes without retaining pm/run-as output."""
    try:
        result = subprocess.run(adb_command() + ["shell", "pm", "path", PACKAGE],
                                capture_output=True, timeout=5)
        if not result.stdout.strip() and not result.stderr.strip() and result.returncode in (0, 1):
            return {"status": "package-not-installed"}
        if result.returncode != 0:
            return {"status": "package-query-failed", "adbExitCode": result.returncode}
        pattern = rb"package:/data/app/(?:~~[A-Za-z0-9_+=-]+/)?" + PACKAGE.encode().replace(b".", rb"\.") + rb"-[A-Za-z0-9_+=-]+/base\.apk\r?\n?"
        if len(result.stdout) > 4096 or re.fullmatch(pattern, result.stdout) is None:
            return {"status": "unexpected-package-path"}
        result = subprocess.run(exec_out_run_as("sh", "-c", 'printf "seanime-native-evidence-access-ok\\n"'),
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
        if result.returncode != 0:
            return {"status": "run-as-transport-failed", "adbExitCode": result.returncode}
        if result.stdout != b"seanime-native-evidence-access-ok\n":
            # exec-out can return zero when run-as fails; require the receipt.
            return {"status": "run-as-unavailable"}
        return {"status": "available"}
    except Exception as error:
        return {"status": "app-access-probe-failed", "errorType": type(error).__name__}


def screenshot_command():
    names = " ".join(sorted(EXPECTED_FILES))
    script = (
        '[ ! -L cache/native-acceptance-screenshots ] || exit 2; '
        'if [ ! -d cache/native-acceptance-screenshots ]; then '
        'printf "seanime-native-screenshots-not-created\\n"; exit 0; fi; '
        "cd cache/native-acceptance-screenshots || exit 2; set --; "
        f"for name in {names}; do "
        '[ -f "$name" ] && [ ! -L "$name" ] && set -- "$@" "$name"; '
        'done; if [ "$#" -eq 0 ]; then printf "seanime-native-screenshots-not-created\\n"; exit 0; fi; '
        'exec tar -cf - "$@"'
    )
    return exec_out_run_as("sh", "-c", script)


def clear_prior_screenshots():
    receipt = b"seanime-native-screenshots-cleared\n"
    script = ("cd cache/native-acceptance-screenshots || exit 2; rm -f "
              + " ".join(sorted(EXPECTED_FILES))
              + ' && printf "seanime-native-screenshots-cleared\\n"')
    try:
        result = subprocess.run(exec_out_run_as("sh", "-c", script),
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
        return "cleared" if result.returncode == 0 and result.stdout == receipt else "app-cache-not-available"
    except Exception as error:
        return "cleanup-unavailable-" + type(error).__name__


def capture_failure(error):
    # Only collector-authored constants may be retained. Exception text can
    # include device paths, shell output, command arguments or JSON contents.
    safe_reasons = {
        "Unexpected screenshot archive member", "Screenshot archive too large",
        "Empty screenshot archive", "Invalid fixture object", "Invalid fixture UUID",
        "Invalid owned fixture root", "Invalid fixture schema or path relationships",
        "Invalid fixture stage or outcome", "Invalid or oversized key trace",
        "Invalid key trace enum", "Invalid numeric observation", "Invalid pixel observation",
        "Invalid picture observation", "Invalid aspect observation", "Invalid boolean observation",
        "Invalid verified observation", "Invalid index readback", "Invalid owned media digest",
        "Invalid observation enum", "Unexpected owned fixture path",
        "Missing or oversized owned fixture manifest",
        "Invalid player lifecycle evidence", "Stale player lifecycle evidence",
        "Invalid plugin startup evidence", "Stale plugin startup evidence", "Missing or oversized plugin startup evidence",
        "Missing or oversized player lifecycle evidence",
        "Invalid native search hardware input evidence", "Stale native search hardware input evidence",
        "Missing or oversized native search hardware input evidence",
        "Invalid native settings preservation evidence", "Stale native settings preservation evidence",
        "Missing or oversized native settings preservation evidence",
    }
    reason = "Unexpected collector failure"
    if type(error) is ValueError and str(error) in safe_reasons:
        reason = str(error)
    elif isinstance(error, tarfile.ReadError):
        reason = "Invalid or truncated screenshot archive"
    elif isinstance(error, json.JSONDecodeError):
        reason = "Invalid owned fixture JSON"
    elif isinstance(error, UnicodeDecodeError):
        reason = "Invalid evidence text encoding"
    elif isinstance(error, subprocess.TimeoutExpired):
        reason = "Evidence command timed out"
    return {"status": "capture-failed", "errorType": type(error).__name__, "reason": reason}


def validate_screenshots(stream):
    files = {}
    with tarfile.open(fileobj=stream, mode="r:") as archive:
        total = 0
        for member in archive:
            limit = 32 * 1024 * 1024 if member.name.endswith(".png") else 8192
            total += member.size
            if (member.name not in EXPECTED_FILES or not member.isfile()
                    or member.name in files or not 0 < member.size <= limit
                    or total > 256 * 1024 * 1024):
                raise ValueError("Unexpected screenshot archive member")
            files[member.name] = archive.extractfile(member).read()
    accepted, invalid = {}, []
    for name in sorted(SCENARIOS):
        png, metadata = files.get(name + ".png"), files.get(name + ".json")
        if png is None and metadata is None:
            continue
        try:
            info = json.loads(metadata)
            if (not png or png[:8] != b"\x89PNG\r\n\x1a\n" or png[12:16] != b"IHDR"
                    or len(png) < 33 or info.get("scenario") != name):
                raise ValueError("Invalid screenshot pair")
            width, height = struct.unpack(">II", png[16:24])
            if not (0 < width <= 8192 and 0 < height <= 8192
                    and info.get("width") == width and info.get("height") == height):
                raise ValueError("Screenshot dimensions disagree")
            clean = {"scenario": name, "width": width, "height": height,
                     "evidenceKind": "instrumentation-fixture-native-state"}
            for field in ("sdk", "capturedAtMs"):
                if type(info.get(field)) is not int or info[field] <= 0:
                    raise ValueError("Invalid screenshot metadata")
                clean[field] = info[field]
            # Do not retain arbitrary extra JSON fields or device-identifying text.
            accepted[f"screenshots/{name}.png"] = png
            accepted[f"screenshots/{name}.json"] = json_bytes(clean)
        except (TypeError, ValueError, AttributeError):
            invalid.append(name)
    return accepted, invalid


def screenshot_header_kind(header):
    """Classify one bounded tar header without retaining any of its contents."""
    if len(header) < 512:
        return "short-header"
    if header == b"\0" * 512:
        return "zero-block"
    try:
        tarfile.TarInfo.frombuf(header, "utf-8", "surrogateescape")
    except tarfile.HeaderError:
        return "invalid-header"
    return "valid-header"


def collect_screenshots():
    with tempfile.TemporaryFile() as stream:
        result = subprocess.run(screenshot_command(), stdout=stream, stderr=subprocess.DEVNULL, timeout=60)
        if result.returncode != 0:
            return {}, {"status": "missing-or-adb-failed", "adbExitCode": result.returncode}
        if stream.tell() > 257 * 1024 * 1024:
            raise ValueError("Screenshot archive too large")
        if stream.tell() == 0:
            raise ValueError("Empty screenshot archive")
        stream.seek(0)
        if stream.read(len(NO_SCREENSHOTS_RECEIPT) + 1) == NO_SCREENSHOTS_RECEIPT:
            return {}, {"status": "not-created", "captured": [], "invalidPairs": [],
                        "notCaptured": sorted(SCENARIOS),
                        "note": "The invocation wrote no approved screenshot checkpoints; this is not a test pass."}
        archive_bytes = stream.seek(0, os.SEEK_END)
        stream.seek(0)
        try:
            files, invalid = validate_screenshots(stream)
        except tarfile.ReadError as error:
            # exec-out can return zero for remote errors or an interrupted read.
            # Keep only bounded counts and an enum, never the failed tar or text.
            stream.seek(0)
            return {}, {**capture_failure(error), "archiveBytes": archive_bytes,
                        "firstHeaderKind": screenshot_header_kind(stream.read(512))}
    captured = sorted(Path(name).stem for name in files if name.endswith(".png"))
    return files, {"status": "captured" if captured else "missing", "captured": captured,
                   "invalidPairs": invalid, "notCaptured": sorted(SCENARIOS - set(captured)),
                   "note": "Some names are opt-in or failure-only; absence does not determine a test result."}


def sanitized_failure(original):
    exception = r"(?:(?:java|javax|kotlin|kotlinx|android|androidx|org\.junit)\.[A-Za-z0-9_.$]+|app\.seanime\.tv\.[A-Za-z0-9_.$]+)(?:Exception|Error|Failure|Throwable)"
    attributes = {}
    if re.fullmatch(exception, original.get("type", "")):
        attributes["type"] = original.get("type")
    lines = []
    frame = r"at ((?:app\.seanime\.tv|androidx\.compose)\.[A-Za-z0-9_.$<>]+\([A-Za-z0-9_$-]{1,120}\.(?:kt|java):[1-9][0-9]{0,6}\))"
    for line in (original.text or "")[:262144].splitlines():
        match = re.fullmatch(frame, line.strip())
        if match:
            lines.append("at " + match.group(1))
        else:
            match = re.match(r"(?:(Caused by: |Suppressed: ))?(" + exception + r")(?=:|$)", line.strip())
            if match:
                lines.append((match.group(1) or "") + match.group(2))
        if len(lines) >= 200:
            break
    return attributes, "\n".join(lines)


def collect_junit(root, started_at_ms=0):
    suites = ET.Element("testsuites")
    sources, rejected = 0, 0
    results = root / "androidtv/app/build/outputs/androidTest-results/connected"
    for path in sorted(results.glob("debug/**/TEST-*.xml")):
        if (path.is_symlink() or not path.resolve().is_relative_to(results.resolve())
                or path.stat().st_size > 16 * 1024 * 1024 or path.stat().st_mtime_ns < started_at_ms * 1_000_000):
            rejected += 1
            continue
        try:
            original = ET.fromstring(path.read_bytes())
            suite = ET.SubElement(suites, "testsuite", name=f"connected-debug-{sources + 1}")
            for case in original.iter("testcase"):
                fields = {key: case.get(key, "") for key in ("name", "classname")}
                if (not fields["classname"].startswith("app.seanime.tv.")
                        or any(not re.fullmatch(r"[A-Za-z0-9_.$\[\]-]{1,300}", val) for val in fields.values())):
                    rejected += 1
                    continue
                duration = case.get("time", "")
                if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", duration):
                    fields["time"] = duration
                clean = ET.SubElement(suite, "testcase", fields)
                for state in ("failure", "error", "skipped"):
                    original_state = case.find(state)
                    if original_state is not None:
                        attributes, trace = sanitized_failure(original_state) if state != "skipped" else ({}, "")
                        ET.SubElement(clean, state, attributes).text = trace
            suite.set("tests", str(len(suite)))
            for state, attr in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                suite.set(attr, str(len(suite.findall(f"testcase/{state}"))))
            sources += 1
        except (ET.ParseError, OSError):
            rejected += 1
    data = ET.tostring(suites, encoding="utf-8", xml_declaration=True)
    return data, {"status": "sanitized" if sources else "missing", "sourceReports": sources,
                  "tests": len(suites.findall("testsuite/testcase")),
                  "failures": len(suites.findall("testsuite/testcase/failure")),
                  "errors": len(suites.findall("testsuite/testcase/error")),
                  "skipped": len(suites.findall("testsuite/testcase/skipped")),
                  "rejectedReportsOrCases": rejected,
                  "note": "Names, durations, outcomes and sanitized app/Compose source frames; messages, properties and output omitted."}


def sanitize_player_lifecycle(data, started_at_ms, finished_at_ms):
    """Accept typed generated-fixture diagnostics only, never Throwable text or source addresses."""
    def require(condition):
        if not condition:
            raise ValueError("Invalid player lifecycle evidence")

    def integer(value, low, high):
        return type(value) is int and low <= value <= high

    root_fields = {"schemaVersion", "scenario", "outcome", "startedAtMs", "stages"}
    require(type(data) is dict and root_fields <= data.keys() <= root_fields | {"completedAtMs"})
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1)
    require(data["scenario"] == "player-lifecycle-recreation" and data["outcome"] in ("running", "passed", "failed"))
    require(integer(started_at_ms, 1, 9_007_199_254_740_991) and integer(finished_at_ms, started_at_ms, 9_007_199_254_740_991))
    require(integer(data["startedAtMs"], 1, 9_007_199_254_740_991))
    if not started_at_ms <= data["startedAtMs"] <= finished_at_ms:
        raise ValueError("Stale player lifecycle evidence")
    require(("completedAtMs" in data) == (data["outcome"] != "running"))
    if "completedAtMs" in data:
        require(integer(data["completedAtMs"], data["startedAtMs"], finished_at_ms))
    stages = data["stages"]
    require(type(stages) is list and 1 <= len(stages) <= len(PLAYER_LIFECYCLE_STAGES))
    stage_fields = {"id", "result", "playerPresent", "playbackState", "positionMs", "durationMs",
                    "paused", "mediaMatches", "readyForMs"}
    error_fields = {"errorCode", "errorSummary", "causeFrames"}
    category = ("provider_dns_policy|provider_url_policy|provider_proxy_policy|provider_redirect_policy|"
                "platform_dns|tls|timeout|socket|http|io|other")
    identifier = r"[A-Za-z0-9_.$]{1,96}"
    summary = re.compile(r"category=(?P<category>" + category + r") causes=" + identifier + r"(?:>" + identifier + r"){0,7}"
                         r" cycle=(?:true|false) truncated=(?:true|false) playerCode=(?P<code>[0-9]{1,10})"
                         r"(?: httpStatus=[1-5][0-9]{2})?(?: operation=(?:open|read|close|other))?(?: errno=-?[0-9]{1,10})?"
                         r"(?: dnsReason=(?P<dns_reason>invalid_host|empty_answers|nonpublic_answers)"
                         r" dnsAnswers=(?P<dns_answers>[0-9]{1,3}) dnsRejected=(?P<dns_rejected>[0-9]{1,3})"
                         r" dnsFamilies=(?P<dns_families>[a-z0-9_+]{1,16}) dnsKinds=(?P<dns_kinds>[a-z0-9_+]{1,72}))?")
    frame_class = re.compile(r"(?:androidx\.media3|io\.github\.peerless2012|app\.seanime\.tv|android|java|javax|kotlin|kotlinx|com\.google\.common)\.[A-Za-z0-9_.$]{1,160}")
    for index, stage in enumerate(stages):
        require(type(stage) is dict and stage_fields <= stage.keys() <= stage_fields | error_fields)
        require(stage["id"] == PLAYER_LIFECYCLE_STAGES[index] and stage["result"] in ("waiting", "ready", "error", "timeout"))
        require(all(type(stage[field]) is bool for field in ("playerPresent", "paused", "mediaMatches")))
        require(integer(stage["playbackState"], 0, 4) and integer(stage["positionMs"], 0, 9_007_199_254_740_991))
        require(integer(stage["durationMs"], -1, 9_007_199_254_740_991) and integer(stage["readyForMs"], 0, 60_000))
        require(index == len(stages) - 1 or stage["result"] == "ready")
        if stage["result"] == "ready":
            require(stage["playerPresent"] and stage["mediaMatches"] and stage["playbackState"] == 3)
        if stage["result"] == "error":
            require(stage.keys() == stage_fields | error_fields and stage["playerPresent"])
            require(integer(stage["errorCode"], 0, 2_147_483_647))
            require(type(stage["errorSummary"]) is str and len(stage["errorSummary"]) <= 1200)
            match = summary.fullmatch(stage["errorSummary"])
            require(match is not None and int(match["code"]) == stage["errorCode"])
            if match["dns_reason"] is not None:
                require(match["category"] == "provider_dns_policy")
                answers, rejected = int(match["dns_answers"]), int(match["dns_rejected"])
                require(0 <= rejected <= answers <= 255)
                families, kinds = match["dns_families"], match["dns_kinds"]
                if match["dns_reason"] in ("invalid_host", "empty_answers"):
                    require(answers == rejected == 0 and families == kinds == "none")
                else:
                    require(rejected > 0)
                    for tokens, ordered in ((families, ("ipv4", "ipv6", "other")),
                                            (kinds, ("nat64", "transition", "private", "local", "multicast", "benchmark", "non_global"))):
                        values = tokens.split("+")
                        require(values == [value for value in ordered if value in values] and len(values) > 0)
            frames = stage["causeFrames"]
            require(type(frames) is list and len(frames) <= 32)
            depths = []
            for frame in frames:
                require(type(frame) is dict and frame.keys() == {"causeDepth", "className", "methodName", "lineNumber"})
                require(integer(frame["causeDepth"], 0, stage["errorSummary"].split(" causes=", 1)[1].split(" cycle=", 1)[0].count(">")))
                require(type(frame["className"]) is str and frame_class.fullmatch(frame["className"]) is not None)
                require(type(frame["methodName"]) is str and re.fullmatch(r"[A-Za-z0-9_$<>-]{1,160}", frame["methodName"]) is not None)
                require(integer(frame["lineNumber"], -2, 1_000_000))
                depths.append(frame["causeDepth"])
            require(depths == sorted(depths) and all(depths.count(depth) <= 4 for depth in set(depths)))
        else:
            require(stage.keys() == stage_fields)
    if data["outcome"] == "passed":
        require(len(stages) == len(PLAYER_LIFECYCLE_STAGES) and all(stage["result"] == "ready" for stage in stages))
    return data


def collect_player_lifecycle(started_at_ms, finished_at_ms):
    # Read exactly one fixed file, rejecting symbolic links including its parents.
    # head bounds remote stdout even when app-private contents are malformed.
    script = ('[ ! -L cache ] && [ ! -L cache/native-acceptance-diagnostics ] && '
              f'[ ! -L {PLAYER_LIFECYCLE_PATH} ] && [ -f {PLAYER_LIFECYCLE_PATH} ] || exit 2; '
              f'exec head -c {PLAYER_LIFECYCLE_MAX_BYTES + 1} {PLAYER_LIFECYCLE_PATH}')
    result = subprocess.run(exec_out_run_as("sh", "-c", script), stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=10)
    if result.returncode != 0 or not result.stdout:
        return {}, {"status": "missing-or-unreadable"}
    if len(result.stdout) > PLAYER_LIFECYCLE_MAX_BYTES:
        raise ValueError("Missing or oversized player lifecycle evidence")

    def unique_object(pairs):
        value = {}
        for key, entry in pairs:
            if key in value:
                raise ValueError("Invalid player lifecycle evidence")
            value[key] = entry
        return value

    try:
        data = json.loads(result.stdout, object_pairs_hook=unique_object)
    except (ValueError, UnicodeDecodeError):
        raise ValueError("Invalid player lifecycle evidence") from None
    clean = sanitize_player_lifecycle(data, started_at_ms, finished_at_ms)
    return {"diagnostics/player-lifecycle-recreation.json": json_bytes(clean)}, {
        "status": "captured", "outcome": clean["outcome"], "stageCount": len(clean["stages"]),
        "note": "Generated WAV lifecycle observations; correlate with JUnit outcome, not a standalone acceptance result."}


def sanitize_plugin_startup(data, test_name, started_at_ms, finished_at_ms):
    """Keep only fixed startup flags and event kinds, never plugin payloads or exception text."""
    def require(condition):
        if not condition:
            raise ValueError("Invalid plugin startup evidence")

    def integer(value, low, high):
        return type(value) is int and low <= value <= high

    fields = {"schemaVersion", "scenario", "testName", "outcome", "startedAtMs", "completedAtMs",
              "elapsedMs", "libraryReady", "socket", "connected", "eventTypes"}
    require(type(data) is dict and data.keys() == fields)
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1)
    require(test_name in PLUGIN_STARTUP_TESTS and data["testName"] == test_name)
    require(data["scenario"] == "plugin-presentation-startup" and data["outcome"] == "failed")
    require(integer(started_at_ms, 1, 9_007_199_254_740_991) and integer(finished_at_ms, started_at_ms, 9_007_199_254_740_991))
    require(integer(data["startedAtMs"], 1, 9_007_199_254_740_991))
    if not started_at_ms <= data["startedAtMs"] <= finished_at_ms:
        raise ValueError("Stale plugin startup evidence")
    require(integer(data["completedAtMs"], data["startedAtMs"], finished_at_ms))
    require(integer(data["elapsedMs"], 0, 9_007_199_254_740_991))
    require(all(type(data[field]) is bool for field in ("libraryReady", "socket", "connected")))
    kinds = data["eventTypes"]
    require(type(kinds) is list and len(kinds) <= len(PLUGIN_STARTUP_EVENT_TYPES))
    require(all(type(kind) is str and kind in PLUGIN_STARTUP_EVENT_TYPES for kind in kinds))
    require(kinds == sorted(set(kinds)))
    return data


def collect_plugin_startup(started_at_ms, finished_at_ms):
    files, tests = {}, {}
    for test_name in PLUGIN_STARTUP_TESTS:
        path = f"cache/native-acceptance-diagnostics/plugin-startup-{test_name}.json"
        # Read only these fixed paths; never enumerate the application's cache.
        script = ('[ ! -L cache ] && [ ! -L cache/native-acceptance-diagnostics ] && '
                  f'[ ! -L {path} ] && [ -f {path} ] || exit 2; '
                  f'exec head -c {PLUGIN_STARTUP_MAX_BYTES + 1} {path}')
        try:
            result = subprocess.run(exec_out_run_as("sh", "-c", script), stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, timeout=10)
            if result.returncode != 0 or not result.stdout:
                tests[test_name] = {"status": "missing-or-unreadable"}
                continue
            if len(result.stdout) > PLUGIN_STARTUP_MAX_BYTES:
                raise ValueError("Missing or oversized plugin startup evidence")

            def unique_object(pairs):
                value = {}
                for key, entry in pairs:
                    if key in value:
                        raise ValueError("Invalid plugin startup evidence")
                    value[key] = entry
                return value

            try:
                data = json.loads(result.stdout, object_pairs_hook=unique_object)
            except (ValueError, UnicodeDecodeError):
                raise ValueError("Invalid plugin startup evidence") from None
            clean = sanitize_plugin_startup(data, test_name, started_at_ms, finished_at_ms)
            files[f"diagnostics/plugin-startup-{test_name}.json"] = json_bytes(clean)
            tests[test_name] = {"status": "captured"}
        except Exception as error:
            tests[test_name] = capture_failure(error)
    return files, {"status": "captured" if files else "not-captured", "snapshotCount": len(files), "tests": tests,
                   "note": "On-failure fixture startup observations only; absence is not proof of a passing test. Correlate with JUnit outcome."}


def sanitize_native_search_hardware_input(data, started_at_ms, finished_at_ms):
    """Accept fixed keyboard observations, never entered text or exception details."""
    def require(condition):
        if not condition:
            raise ValueError("Invalid native search hardware input evidence")

    def integer(value, low, high):
        return type(value) is int and low <= value <= high

    fields = {"schemaVersion", "scenario", "outcome", "startedAtMs", "observations"}
    require(type(data) is dict and fields <= data.keys() <= fields | {"completedAtMs"})
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1)
    require(data["scenario"] == "native-search-hardware-input" and data["outcome"] in ("running", "passed", "failed"))
    require(integer(started_at_ms, 1, 9_007_199_254_740_991) and integer(finished_at_ms, started_at_ms, 9_007_199_254_740_991))
    require(integer(data["startedAtMs"], 1, 9_007_199_254_740_991))
    if not started_at_ms <= data["startedAtMs"] <= finished_at_ms:
        raise ValueError("Stale native search hardware input evidence")
    require(("completedAtMs" in data) == (data["outcome"] != "running"))
    if "completedAtMs" in data:
        require(integer(data["completedAtMs"], data["startedAtMs"], finished_at_ms))
    observations = data["observations"]
    require(type(observations) is list and len(observations) <= 7)
    boolean_fields = {"hasEditableText", "exactEmpty", "exactA", "exactAB", "semanticFocused",
                      "viewAttached", "viewLaidOut", "windowFocused"}
    observation_fields = boolean_fields | {"stage", "keysSent", "nodeCount", "textLength", "imeVisible"}
    exact_lengths = {"exactEmpty": 0, "exactA": 1, "exactAB": 2}
    single_node_flags = ("hasEditableText", "semanticFocused", "viewAttached", "viewLaidOut", "windowFocused")
    for index, observation in enumerate(observations):
        require(type(observation) is dict and observation.keys() == observation_fields)
        require(integer(observation["keysSent"], 0, 3))
        if observation["stage"] == "failure":
            require(data["outcome"] == "failed" and index == len(observations) - 1)
        else:
            require(index < len(NATIVE_SEARCH_HARDWARE_INPUT_STAGES)
                    and (observation["stage"], observation["keysSent"]) == NATIVE_SEARCH_HARDWARE_INPUT_STAGES[index])
        require(integer(observation["nodeCount"], 0, 255) and integer(observation["textLength"], 0, 4096))
        require(all(type(observation[field]) is bool for field in boolean_fields))
        require(observation["imeVisible"] in ("visible", "hidden", "unknown"))
        require(sum(observation[field] for field in exact_lengths) <= 1)
        require(all(not observation[field] or (observation["hasEditableText"] and observation["textLength"] == length)
                    for field, length in exact_lengths.items()))
        if not observation["hasEditableText"]:
            require(observation["textLength"] == 0 and not any(observation[field] for field in exact_lengths))
        if observation["nodeCount"] != 1:
            require(not any(observation[field] for field in single_node_flags))
    if data["outcome"] == "failed":
        require(bool(observations) and observations[-1]["stage"] == "failure")
    if data["outcome"] == "passed":
        require(len(observations) == len(NATIVE_SEARCH_HARDWARE_INPUT_STAGES))
        after_left = observations[-1]
        require(after_left["nodeCount"] == 1 and after_left["textLength"] == 2 and after_left["exactAB"]
                and all(after_left[field] for field in single_node_flags))
    return data


def collect_native_search_hardware_input(started_at_ms, finished_at_ms):
    path = NATIVE_SEARCH_HARDWARE_INPUT_PATH
    script = ('[ ! -L cache ] && [ ! -L cache/native-acceptance-diagnostics ] && '
              f'[ ! -L {path} ] && [ -f {path} ] || exit 2; '
              f'exec head -c {NATIVE_SEARCH_HARDWARE_INPUT_MAX_BYTES + 1} {path}')
    result = subprocess.run(exec_out_run_as("sh", "-c", script), stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=10)
    if result.returncode != 0 or not result.stdout:
        return {}, {"status": "missing-or-unreadable"}
    if len(result.stdout) > NATIVE_SEARCH_HARDWARE_INPUT_MAX_BYTES:
        raise ValueError("Missing or oversized native search hardware input evidence")

    def unique_object(pairs):
        value = {}
        for key, entry in pairs:
            if key in value:
                raise ValueError("Invalid native search hardware input evidence")
            value[key] = entry
        return value

    try:
        data = json.loads(result.stdout, object_pairs_hook=unique_object)
    except (ValueError, UnicodeDecodeError):
        raise ValueError("Invalid native search hardware input evidence") from None
    clean = sanitize_native_search_hardware_input(data, started_at_ms, finished_at_ms)
    return {"diagnostics/native-search-hardware-input.json": json_bytes(clean)}, {
        "status": "captured", "outcome": clean["outcome"], "observationCount": len(clean["observations"]),
        "note": "Fixed native keyboard fixture observations; correlate with JUnit outcome, not a standalone acceptance result."}


def sanitize_native_settings_preservation(data, started_at_ms, finished_at_ms):
    """Keep only settings equality flags and fixed JSON types, never config values."""
    def require(condition):
        if not condition:
            raise ValueError("Invalid native settings preservation evidence")

    def integer(value, low, high):
        return type(value) is int and low <= value <= high

    require(type(data) is dict and data.keys() == {"schemaVersion", "scenario", "startedAtMs", "observations"})
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1)
    require(data["scenario"] == "native-settings-preservation")
    require(integer(started_at_ms, 1, 9_007_199_254_740_991) and integer(finished_at_ms, started_at_ms, 9_007_199_254_740_991))
    require(integer(data["startedAtMs"], 1, 9_007_199_254_740_991))
    if not started_at_ms <= data["startedAtMs"] <= finished_at_ms:
        raise ValueError("Stale native settings preservation evidence")
    observations = data["observations"]
    require(type(observations) is list and len(observations) <= len(NATIVE_SETTINGS_PRESERVATION_PHASES))
    previous_phase, previous_time = -1, data["startedAtMs"]
    for observation in observations:
        require(type(observation) is dict and observation.keys() == {
            "phase", "observedAtMs", "wholeConfigEqual", "auditTimesEqual", "sectionsEqual", "fields"})
        require(observation["phase"] in NATIVE_SETTINGS_PRESERVATION_PHASES)
        phase = NATIVE_SETTINGS_PRESERVATION_PHASES.index(observation["phase"])
        require(phase > previous_phase and integer(observation["observedAtMs"], previous_time, finished_at_ms))
        previous_phase, previous_time = phase, observation["observedAtMs"]
        require(type(observation["wholeConfigEqual"]) is bool and type(observation["auditTimesEqual"]) is bool)
        sections = observation["sectionsEqual"]
        require(type(sections) is dict and sections.keys() == NATIVE_SETTINGS_PRESERVATION_SECTIONS)
        require(all(type(value) is bool for value in sections.values()))
        fields = observation["fields"]
        require(type(fields) is dict and fields.keys() == NATIVE_SETTINGS_PRESERVATION_FIELDS)
        for field in fields.values():
            require(type(field) is dict and field.keys() == {"equal", "expectedType", "actualType"})
            require(type(field["equal"]) is bool)
            require(field["expectedType"] in NATIVE_SETTINGS_PRESERVATION_TYPES
                    and field["actualType"] in NATIVE_SETTINGS_PRESERVATION_TYPES)
    return data


def collect_native_settings_preservation(started_at_ms, finished_at_ms):
    path = NATIVE_SETTINGS_PRESERVATION_PATH
    script = ('[ ! -L cache ] && [ ! -L cache/native-acceptance-diagnostics ] && '
              f'[ ! -L {path} ] && [ -f {path} ] || exit 2; '
              f'exec head -c {NATIVE_SETTINGS_PRESERVATION_MAX_BYTES + 1} {path}')
    result = subprocess.run(exec_out_run_as("sh", "-c", script), stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=10)
    if result.returncode != 0 or not result.stdout:
        return {}, {"status": "missing-or-unreadable"}
    if len(result.stdout) > NATIVE_SETTINGS_PRESERVATION_MAX_BYTES:
        raise ValueError("Missing or oversized native settings preservation evidence")

    def unique_object(pairs):
        value = {}
        for key, entry in pairs:
            if key in value:
                raise ValueError("Invalid native settings preservation evidence")
            value[key] = entry
        return value

    try:
        data = json.loads(result.stdout, object_pairs_hook=unique_object)
    except (ValueError, UnicodeDecodeError):
        raise ValueError("Invalid native settings preservation evidence") from None
    clean = sanitize_native_settings_preservation(data, started_at_ms, finished_at_ms)
    any_mismatch = any(not observation["wholeConfigEqual"]
                       or (observation["phase"] == "rejected-write" and not observation["auditTimesEqual"])
                       for observation in clean["observations"])
    return {"diagnostics/native-settings-preservation.json": json_bytes(clean)}, {
        "status": "captured", "observationCount": len(clean["observations"]), "anyMismatch": any_mismatch,
        "note": "Fixed settings equality observations; wholeConfigEqual excludes root audit times. Only rejected-write audit changes count as mismatches. JUnit determines the test outcome; observations are not standalone acceptance."}


def capture_installed_apks():
    snapshot = {"status": "incomplete", "capturedAtMs": int(time.time() * 1000),
                "captureStage": "first-target-app-process", "packages": {}}
    try:
        adb = adb_command()
        result = subprocess.run(adb + ["shell", "getprop", "ro.product.cpu.abi"],
                                capture_output=True, text=True, timeout=5)
        abi = result.stdout.strip() if result.returncode == 0 else ""
        snapshot["abi"] = abi if abi in ("arm64-v8a", "x86_64") else None
        for package in (PACKAGE, TEST_PACKAGE):
            entry = {"status": "missing"}
            snapshot["packages"][package] = entry
            try:
                result = subprocess.run(adb + ["shell", "pm", "path", package],
                                        capture_output=True, text=True, timeout=5)
                if result.returncode != 0 or not result.stdout.strip():
                    continue
                lines = result.stdout.splitlines()
                pattern = r"package:(/data/app/(?:~~[A-Za-z0-9_+=-]+/)?" + re.escape(package) + r"-[A-Za-z0-9_+=-]+/base\.apk)"
                match = re.fullmatch(pattern, lines[0]) if len(lines) == 1 else None
                if match is None:
                    entry["status"] = "unexpected-package-path"
                    continue
                path = match.group(1)
                result = subprocess.run(adb + ["shell", "sha256sum", path],
                                        capture_output=True, text=True, timeout=10)
                fields = result.stdout.split()
                if (result.returncode != 0 or len(fields) != 2
                        or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]) or fields[1] != path):
                    entry["status"] = "hash-unavailable"
                    continue
                entry.update(status="hashed", installedPath=path, sha256=fields[0].lower())
            except Exception as error:
                entry.update(status="capture-failed", errorType=type(error).__name__)
    except Exception as error:
        snapshot.update(status="capture-failed", errorType=type(error).__name__)
    return snapshot


def match_installed_apks(snapshot, hashes):
    packages = snapshot.get("packages", {})
    expected = {PACKAGE: {"arm64-v8a": APK_PATHS[0], "x86_64": APK_PATHS[1]}.get(snapshot.get("abi")),
                TEST_PACKAGE: APK_PATHS[2]}
    for package, entry in packages.items():
        if entry["status"] != "hashed":
            continue
        relative = expected[package]
        entry["builtApk"] = relative
        if not relative or relative not in hashes:
            entry["status"] = "matching-build-apk-unavailable"
        else:
            entry["matchesBuiltApk"] = entry["sha256"] == hashes[relative]
            entry["status"] = "matched" if entry["matchesBuiltApk"] else "mismatch"
    statuses = [packages.get(package, {}).get("status") for package in (PACKAGE, TEST_PACKAGE)]
    snapshot["status"] = "matched" if statuses == ["matched", "matched"] else "mismatch" if "mismatch" in statuses else "incomplete"
    return snapshot


def is_owned_journey(invocation, command):
    return invocation == "owned-library-journey" and "-Pandroid.testInstrumentationRunnerArguments.class=" + OWNED_JOURNEY in command


def owned_fixture_profile(invocation, command):
    """Require one exact method, its sole opt-in, and a fresh process flag."""
    spec = OWNED_FIXTURES.get(invocation)
    if spec is None or not isinstance(command, list) or any(type(arg) is not str for arg in command):
        return None
    actual = [arg for arg in command if arg.startswith(RUNNER_ARGUMENT)]
    expected = [RUNNER_ARGUMENT + "class=" + spec["selector"],
                RUNNER_ARGUMENT + spec["flag"] + "=true",
                RUNNER_ARGUMENT + "freshInstrumentationProcess=true"]
    return spec if sorted(actual) == sorted(expected) else None


def requires_owned_fixture(invocation, test_class):
    return invocation in OWNED_FIXTURES or any(test_class == spec["selector"] for spec in OWNED_FIXTURES.values())


def recording_profile(invocation, command):
    owned = is_owned_journey(invocation, command)
    seconds = OWNED_JOURNEY_VIDEO_SECONDS if owned else VIDEO_SECONDS
    scope = "owned library journey" if owned else "connected instrumentation invocation"
    return {"maxSeconds": seconds,
            "bitRateBitsPerSecond": OWNED_JOURNEY_VIDEO_BIT_RATE if owned else VIDEO_BIT_RATE,
            "resolution": "1280x720", "maxBytes": VIDEO_MAX_BYTES,
            "recordCommandTimeoutSeconds": seconds + 15, "workerJoinTimeoutSeconds": seconds + 75,
            "appWaitMaxSeconds": APP_WAIT_SECONDS,
            "coverage": f"First app process observed during {scope}; bounded excerpt only, without a complete-flow or real-service acceptance claim."}


def sanitize_video_paint(paint):
    if (not isinstance(paint, dict) or type(paint.get("positionMs")) is not int or not 0 <= paint["positionMs"] <= 60_000
            or type(paint.get("channelTolerance")) is not int or paint["channelTolerance"] != 25
            or any(not isinstance(paint.get(field), list) or len(paint[field]) != 3
                   or any(type(value) is not int or not 0 <= value <= 255 for value in paint[field])
                   for field in ("meanRgb", "expectedRgb"))):
        raise ValueError("Invalid pixel observation")
    return {field: paint[field] for field in ("positionMs", "meanRgb", "expectedRgb", "channelTolerance")}


def sanitize_video_aspect(aspect):
    if not isinstance(aspect, dict):
        raise ValueError("Invalid aspect observation")
    integers = {"viewportWidth": (1, 8192), "viewportHeight": (1, 8192),
                "markerLeft": (0, 8192), "markerTop": (0, 8192),
                "markerWidth": (1, 8192), "markerHeight": (1, 8192), "maxBarChannel": (0, 255)}
    for field, (minimum, maximum) in integers.items():
        if type(aspect.get(field)) is not int or not minimum <= aspect[field] <= maximum:
            raise ValueError("Invalid aspect observation")
    width, height = aspect["viewportWidth"], aspect["viewportHeight"]
    numbers = {"expectedLeft": width, "expectedTop": height, "expectedSide": min(width, height), "pillarboxWidth": width / 2}
    for field, maximum in numbers.items():
        # Exact numeric types reject booleans; chained bounds also reject NaN/Infinity.
        if type(aspect.get(field)) not in (int, float) or not 0 <= aspect[field] <= maximum:
            raise ValueError("Invalid aspect observation")
    if (aspect["expectedSide"] <= 0 or aspect["markerLeft"] + aspect["markerWidth"] > width
            or aspect["markerTop"] + aspect["markerHeight"] > height
            or aspect["expectedLeft"] + aspect["expectedSide"] > width
            or aspect["expectedTop"] + aspect["expectedSide"] > height):
        raise ValueError("Invalid aspect observation")
    return {field: aspect[field] for field in (*integers, *numbers)}


def sanitize_picture_round_trip(picture):
    booleans = ("ownerRetained", "sourceRetained", "checkpointRetained", "audioTrackRetained", "subtitlesDisabledRetained")
    if (not isinstance(picture, dict) or picture.get("preset") != "mode-c"
            or type(picture.get("positionMs")) is not int or not 0 <= picture["positionMs"] <= 60_000
            or type(picture.get("offWidth")) is not int or picture["offWidth"] != 320
            or type(picture.get("offHeight")) is not int or picture["offHeight"] != 240
            or any(type(picture.get(field)) is not bool for field in booleans)):
        raise ValueError("Invalid picture observation")
    clean = {field: picture[field] for field in ("preset", "positionMs", "offWidth", "offHeight", *booleans)}
    for field in ("enhancedPaint", "directPaint"):
        clean[field] = sanitize_video_paint(picture.get(field))
    for field in ("enhancedAspect", "directAspect"):
        clean[field] = sanitize_video_aspect(picture.get(field))
    return clean


def sanitize_journey_manifest(data, directory):
    if not isinstance(data, dict):
        raise ValueError("Invalid fixture object")
    identifier = directory.removeprefix("native-go-fixture-")
    if not directory.startswith("native-go-fixture-") or str(uuid.UUID(identifier)) != identifier or uuid.UUID(identifier).version != 4:
        raise ValueError("Invalid fixture UUID")
    root = data.get("root")
    if root not in (f"/data/user/0/{PACKAGE}/files/{directory}", f"/data/data/{PACKAGE}/files/{directory}"):
        raise ValueError("Invalid owned fixture root")
    paths = {"dataDir": "data", "cacheDir": "cache", "libraryDir": "library",
             "mediaPath": "library/Owned generated multitrack fixture.mkv", "indexPath": "library/Owned unmatched index.json"}
    if (data.get("kind") != JOURNEY_KIND or data.get("requiresColdRestart") is not True
            or any(data.get(key) != root + "/" + relative for key, relative in paths.items())):
        raise ValueError("Invalid fixture schema or path relationships")
    stages = {"created", "generating-owned-color-video-two-audio-tracks-and-srt",
              "existing-import-signed-range-and-root-boundary-verified", "remote-library-manage-files-play-focus",
              "remote-decoding-seek-audio-and-embedded-cues-verified", "remote-only-owned-library-playback-verified",
              "remote-picture-round-trip-and-checkpoint-verified",
              "stopped-awaiting-force-stop-and-reviewed-cleanup"}
    if data.get("stage") not in stages | {stage + "-terminal" for stage in stages} or data.get("outcome") not in {"running", "passed", "failed"}:
        raise ValueError("Invalid fixture stage or outcome")
    clean = {"kind": JOURNEY_KIND, "root": directory, **paths, "stage": data["stage"], "outcome": data["outcome"],
             "scope": "owned generated media and native remote controls; no live-service or retained-state-restoration claim"}
    trace = data.get("keyTrace")
    if not isinstance(trace, list) or len(trace) > 512:
        raise ValueError("Invalid or oversized key trace")
    keys = {"KEYCODE_DPAD_" + suffix for suffix in ("UP", "DOWN", "LEFT", "RIGHT", "CENTER")} | {"KEYCODE_BACK"}
    controls = set("""nav-LIBRARY anime-search-submit anime-collection-options anime-discover anime-manage
        library-tab-Files library-tab-Explorer library-tab-Issues library-tools-refresh library-tools-filter
        library-selected-actions library-clear-selection library-current-folder-select library-current-folder-actions
        native-player-play native-player-seek native-player-previous native-player-next native-player-back
        native-player-rewind native-player-forward
        native-player-audio native-player-subtitles native-player-speed native-player-fit native-player-quality
        native-player-anime4k native-player-picture native-player-screenshot native-player-dialog-close
        native-player-choice-mode-c native-player-choice-off""".split())
    path_controls = {f"library-file-{action}-{root}/{paths['mediaPath']}": f"library-file-{action}-{paths['mediaPath']}"
                     for action in ("select", "edit", "play", "rename", "delete")}
    path_controls.update({f"library-folder-{action}-{root}/library": f"library-folder-{action}-library"
                          for action in ("open", "select", "actions")})
    clean["keyTrace"], unknown = [], 0
    for event in trace:
        if not isinstance(event, dict) or event.get("key") not in keys or not isinstance(event.get("focused"), str):
            raise ValueError("Invalid key trace enum")
        focused = event["focused"]
        if focused in path_controls:
            focused = path_controls[focused]
        elif focused not in controls and not re.fullmatch(r"native-player-choice-[0-9]{1,2}-[0-9]{1,2}", focused):
            focused = "unrecognized-or-untagged"
            unknown += 1
        clean["keyTrace"].append({"key": event["key"], "focused": focused})
    clean["unrecognizedFocusObservations"] = unknown
    for key in ("seekPositionMs", "audioDecodedBuffers", "videoDecodedBuffers", "subtitleWhitePixelsOn", "subtitleWhitePixelsOff"):
        if key in data:
            if type(data[key]) is not int or not 0 <= data[key] <= 100_000_000:
                raise ValueError("Invalid numeric observation")
            clean[key] = data[key]
    for key in ("videoPaintBeforeSeek", "videoPaintAfterSeek"):
        if key in data:
            clean[key] = sanitize_video_paint(data[key])
    if "pictureRoundTrip" in data:
        clean["pictureRoundTrip"] = sanitize_picture_round_trip(data["pictureRoundTrip"])
    for key in ("picturePreferencesRestored", "rootCanonical", "appFilesAliasObserved"):
        if key in data:
            if type(data[key]) is not bool:
                raise ValueError("Invalid boolean observation")
            clean[key] = data[key]
    for key in ("explorerFilePath", "explorerIndexedPath"):
        if key in data:
            if type(data[key]) is not str or data[key] != data["mediaPath"]:
                raise ValueError("Invalid fixture schema or path relationships")
            clean[key] = paths["mediaPath"]
    if "verified" in data:
        allowed = {"owned-generated-multitrack-mkv", "existing-index-import", "signed-go-ranges", "library-root-boundary",
                   "remote-library-manage-files-play", "remote-explorer-play", "decoded-video-and-selected-audio",
                   "embedded-subtitle-cue-pixels", "remote-seek-and-pause", "same-file-return-focus",
                   "fresh-playback-identity", "owned-native-recovery", "remote-picture-round-trip"}
        verified = data["verified"]
        if (not isinstance(verified, list) or len(verified) > len(allowed)
                or any(type(value) is not str or value not in allowed for value in verified)
                or len(set(verified)) != len(verified)):
            raise ValueError("Invalid verified observation")
        clean["verified"] = verified
    for key, allowed in (("selectedAudioLanguage", {"eng", "fra", "en", "fr"}),
                         ("hostStatusAfterRun", {"stopped", "starting", "ready", "running", "stopping", "error"}),
                         ("failedAt", stages)):
        if key in data:
            if data[key] not in allowed:
                raise ValueError("Invalid observation enum")
            clean[key] = data[key]
    if type(data.get("recoveryClearedByPlayer")) is bool:
        clean["recoveryClearedByPlayer"] = data["recoveryClearedByPlayer"]
    return clean


def sanitize_external_resolution(data):
    relationships = ("targetContextOwnsCallerUid", "testContextOwnsCallerUid", "targetAndTestPackagesDiffer")
    matches = ("scopedWildcardResolves", "concreteVideoResolves", "scopedConcreteVideoResolves", "declaredFilterMatches")
    settings = ("componentEnabledSetting", "applicationEnabledSetting")
    keys = {"sdk", "scheme", "mimeType", "candidateCount", "receiver", *relationships, *matches, *settings}
    if type(data) is not dict or set(data) != keys:
        raise ValueError("Invalid external resolution schema")
    clean = {}
    for key, minimum, maximum in (("sdk", 23, 1000), ("candidateCount", 0, 4096)):
        if type(data[key]) is not int or not minimum <= data[key] <= maximum:
            raise ValueError("Invalid external resolution number")
        clean[key] = data[key]
    for key, allowed in (("scheme", {"http", "https", "content", "file", "missing", "other"}),
                         ("mimeType", {"video/*", "other"})):
        if type(data[key]) is not str or data[key] not in allowed:
            raise ValueError("Invalid external resolution enum")
        clean[key] = data[key]
    for key in relationships:
        if type(data[key]) is not bool:
            raise ValueError("Invalid external resolution relationship")
        clean[key] = data[key]
    for key in matches:
        if not (type(data[key]) is bool or type(data[key]) is str and data[key] == "unavailable"):
            raise ValueError("Invalid external resolution match")
        clean[key] = data[key]
    for key in settings:
        if not (type(data[key]) is int and 0 <= data[key] <= 4
                or type(data[key]) is str and data[key] == "unavailable"):
            raise ValueError("Invalid external resolution setting")
        clean[key] = data[key]
    receiver = data["receiver"]
    receiver_keys = {"enabled", "exported", "applicationEnabled", "receiverOwnsCallerUid",
                     "receiverOwnsTestUid", "testOnly", "stopped"}
    if type(receiver) is str and receiver == "unavailable":
        clean["receiver"] = receiver
    elif (type(receiver) is dict and set(receiver) == receiver_keys
          and all(type(value) is bool for value in receiver.values())):
        clean["receiver"] = {key: receiver[key] for key in sorted(receiver_keys)}
    else:
        raise ValueError("Invalid external resolution receiver")
    return clean


def sanitize_owned_manifest(data, directory, invocation):
    if isinstance(data, dict) and "externalResolution" in data and invocation != "owned-external-player":
        raise ValueError("External resolution belongs only to its owned fixture")
    if invocation == "owned-library-journey":
        return sanitize_journey_manifest(data, directory)
    if not isinstance(data, dict):
        raise ValueError("Invalid fixture object")
    if not re.fullmatch(FIXTURE_DIRECTORY, directory):
        raise ValueError("Invalid fixture UUID")
    root = data.get("root")
    if root not in (f"/data/user/0/{PACKAGE}/files/{directory}", f"/data/data/{PACKAGE}/files/{directory}"):
        raise ValueError("Invalid owned fixture root")
    management = invocation == "owned-library-management"
    external = invocation == "owned-external-player"
    paths = {"dataDir": "data", "cacheDir": "cache", "libraryDir": "library",
             "mediaPath": "library/Original generated video.mp4" if management else "library/Generated owned raw video.mp4"}
    if management:
        paths["indexPath"] = "library/Owned unmatched index.json"
    spec = OWNED_FIXTURES[invocation]
    if (data.get("kind") != spec["kind"] or data.get("requiresColdRestart") is not True
            or any(data.get(key) != root + "/" + relative for key, relative in paths.items())):
        raise ValueError("Invalid fixture schema or path relationships")
    stages = {"created", "stopped-awaiting-force-stop-and-reviewed-cleanup"}
    if management:
        stages |= {"isolated-server-and-empty-index-verified", "generated-files-and-index-ready",
                   "existing-import-endpoint-verified", "native-bulk-ignore-and-signed-readback-verified",
                   "native-rename-index-and-owned-bytes-verified", "owned-library-workflow-verified"}
        verified = {"generated-owned-video-copies", "existing-index-import-api", "main-native-library-route",
                    "multi-file-ignore", "native-rename", "explorer-tree", "native-delete", "signed-go-index-readback"}
        booleans = ("originalPreserved", "retainedCopyPreserved", "recoveryAbsent", "rootCanonical", "appFilesAliasObserved")
    else:
        stages |= {"isolated-server-ready", "generating-owned-h264", "signed-go-range-and-library-boundary-verified",
                   "signed-go-native-frame-pause-seek-verified", "raw-media-route-verified"}
        if external:
            stages.add("native-external-background-ranges-and-return-verified")
        verified = {"generated-h264", "signed-go-range-get", "library-root-boundary",
                    "main-coordinator-media3-rendered-frame", "remote-play-pause-seek", "owned-recovery-write-and-dismissal"}
        booleans = ("recoverySourceValidated", "recoveryClearedByPlayer")
        if external:
            booleans += ("externalReceiverDifferentUid", "foregroundHostVerified", "externalReturnPaused", "externalHostReleased")
    if (type(data.get("stage")) is not str or data["stage"] not in stages | {stage + "-terminal" for stage in stages}
            or type(data.get("outcome")) is not str or data["outcome"] not in {"running", "passed", "failed"}):
        raise ValueError("Invalid fixture stage or outcome")
    clean = {"kind": spec["kind"], "root": directory, **paths, "stage": data["stage"], "outcome": data["outcome"],
             "scope": "owned generated media and isolated native fixtures; no live-service or retained-state-restoration claim"}
    if "externalResolution" in data:
        if data["outcome"] != "failed":
            raise ValueError("External resolution is failure-only evidence")
        clean["externalResolution"] = sanitize_external_resolution(data["externalResolution"])
    for key, allowed in (("hostStatusAfterRun", {"stopped", "starting", "ready", "running", "stopping", "error"}),
                         ("failedAt", stages)):
        if key in data:
            if type(data[key]) is not str or data[key] not in allowed:
                raise ValueError("Invalid observation enum")
            clean[key] = data[key]
    for key in booleans:
        if key in data:
            if type(data[key]) is not bool:
                raise ValueError("Invalid boolean observation")
            clean[key] = data[key]
    if "verified" in data:
        values = data["verified"]
        if (not isinstance(values, list) or len(values) > len(verified)
                or any(type(value) is not str or value not in verified for value in values)
                or len(set(values)) != len(values)):
            raise ValueError("Invalid verified observation")
        clean["verified"] = values
    if management:
        owned = ["library/Owned editable copy.mp4", "library/Owned retained copy.mp4", "library/Renamed owned copy.mp4"]
        expected = [root + "/" + path for path in owned]
        copies = data.get("ownedCopyPaths")
        if (not isinstance(copies, list) or len(copies) != len(expected)
                or any(type(path) is not str for path in copies) or set(copies) != set(expected)):
            raise ValueError("Invalid fixture schema or path relationships")
        clean["ownedCopyPaths"] = owned
        if "explorerOwnedPaths" in data:
            targets = data["explorerOwnedPaths"]
            relative = ["library/Renamed owned copy.mp4", paths["mediaPath"]]
            if (not isinstance(targets, list) or len(targets) != 2
                    or any(type(path) is not str for path in targets)
                    or set(targets) != {root + "/" + path for path in relative}):
                raise ValueError("Invalid fixture schema or path relationships")
            clean["explorerOwnedPaths"] = relative
        if "originalSha256" in data:
            if type(data["originalSha256"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", data["originalSha256"]):
                raise ValueError("Invalid owned media digest")
            clean["originalSha256"] = data["originalSha256"]
        readbacks = data.get("indexReadbacks")
        readback_stages = ("fresh-empty-index", "imported", "native-bulk-ignore", "native-rename", "native-delete")
        if not isinstance(readbacks, list) or len(readbacks) > len(readback_stages):
            raise ValueError("Invalid index readback")
        clean["indexReadbacks"] = []
        for index, readback in enumerate(readbacks):
            if (not isinstance(readback, dict) or readback.get("stage") != readback_stages[index]
                    or not isinstance(readback.get("files"), list) or len(readback["files"]) > 2):
                raise ValueError("Invalid index readback")
            files, seen = [], set()
            for row in readback["files"]:
                if (not isinstance(row, dict) or type(row.get("path")) is not str or row["path"] not in expected
                        or row["path"] in seen or row.get("name") != row["path"].rsplit("/", 1)[1]
                        or type(row.get("mediaId")) is not int or row["mediaId"] != 0
                        or any(type(row.get(key)) is not bool for key in ("locked", "ignored"))):
                    raise ValueError("Invalid index readback")
                seen.add(row["path"])
                files.append({"path": row["path"][len(root) + 1:], **{key: row[key] for key in ("name", "mediaId", "locked", "ignored")}})
            clean["indexReadbacks"].append({"stage": readback["stage"], "files": files})
    else:
        numbers = {"rangeStatus": (206, 206), "rangeBytes": (256, 256), "pausedPositionMs": (0, 31_000),
                   "renderedWidth": (1, 8192), "renderedHeight": (1, 8192)}
        if external:
            numbers["externalAnonymousGoRanges"] = (3, 3)
        for key, (minimum, maximum) in numbers.items():
            if key in data:
                # Integer-only observations reject booleans, NaN and Infinity.
                if type(data[key]) is not int or not minimum <= data[key] <= maximum:
                    raise ValueError("Invalid numeric observation")
                clean[key] = data[key]
    return clean


def collect_journey_manifest():
    return collect_owned_manifest("owned-library-journey")


def collect_owned_manifest(invocation):

    script = ('for dir in files/native-go-fixture-*; do [ -d "$dir" ] && [ ! -L "$dir" ] '
              '&& [ -f "$dir/fixture.json" ] && [ ! -L "$dir/fixture.json" ] '
              '&& printf "%s\\n" "$dir/fixture.json"; done')
    result = subprocess.run(exec_out_run_as("sh", "-c", script),
                            capture_output=True, text=True, timeout=5)
    paths = result.stdout.splitlines()
    if result.returncode != 0 or not paths:
        return {}, {"status": "missing-or-unreadable"}
    if len(paths) != 1:
        return {}, {"status": "ambiguous-owned-roots", "candidateCount": len(paths)}
    match = re.fullmatch(r"files/(" + FIXTURE_DIRECTORY + r")/fixture\.json", paths[0])
    if match is None:
        raise ValueError("Unexpected owned fixture path")
    result = subprocess.run(exec_out_run_as("head", "-c", "131073", paths[0]),
                            capture_output=True, timeout=5)
    if result.returncode != 0 or not 0 < len(result.stdout) <= 131072:
        raise ValueError("Missing or oversized owned fixture manifest")
    clean = sanitize_owned_manifest(json.loads(result.stdout), match.group(1), invocation)
    return {f"fixtures/{invocation}.json": json_bytes(clean)}, {"status": "captured", "fixtureRoot": match.group(1), "outcome": clean["outcome"]}


def record_startup(stop, state, video, profile=None):
    profile = profile or recording_profile("connected-suite", COMMAND)
    state.update(profile)
    try:
        adb = adb_command()
        deadline = time.monotonic() + APP_WAIT_SECONDS
        while not stop.is_set() and time.monotonic() < deadline:
            process = subprocess.run(adb + ["shell", "pidof", PACKAGE],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            if process.returncode == 0:
                break
            stop.wait(2)
        else:
            state["status"] = "app-process-not-observed-within-budget"
            return
        if stop.is_set():
            state["status"] = "tests-finished-before-recording"
            return
        state["installedApks"] = capture_installed_apks()
        if stop.is_set():
            state["status"] = "tests-finished-before-recording"
            return
        state["startedAtMs"] = int(time.time() * 1000)
        result = subprocess.run(adb + ["shell", "screenrecord", "--time-limit", str(profile["maxSeconds"]),
                                      "--size", profile["resolution"], "--bit-rate",
                                      str(profile["bitRateBitsPerSecond"]), REMOTE_VIDEO],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=profile["recordCommandTimeoutSeconds"])
        state["finishedAtMs"] = int(time.time() * 1000)
        if result.returncode != 0:
            state.update(status="screenrecord-failed", exitCode=result.returncode)
            return
        with tempfile.TemporaryFile() as stream:
            result = subprocess.run(adb + ["exec-out", "cat", REMOTE_VIDEO], stdout=stream,
                                    stderr=subprocess.DEVNULL, timeout=15)
            size = stream.tell()
            if result.returncode != 0 or not 16 <= size <= VIDEO_MAX_BYTES:
                raise ValueError("Recording missing or too large")
            stream.seek(0)
            data = stream.read()
        if data[4:8] != b"ftyp" or b"moov" not in data or b"mdat" not in data:
            raise ValueError("Recording is not a finalized MP4")
        video["recordings/instrumentation-startup-excerpt.mp4"] = data
        state["fileBytes"] = len(data)
        state["status"] = "captured"
    except Exception as error:
        state.update(status="capture-failed", errorType=type(error).__name__)


def collect(root, status, recording, video, invocation="connected-suite", command=None):
    output = root / Path(OUTPUT).parent / invocation
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"schemaVersion": 1, "invocation": invocation, "gradleExitCode": status, "command": command or COMMAND,
                "recording": {key: value for key, value in recording.items() if key != "installedApks"},
                "evidenceKind": "instrumentation-fixtures",
                "realServiceAcceptance": "not-established"}
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, timeout=5)
    manifest["sourceCommit"] = head.stdout.strip() if re.fullmatch(r"[0-9a-f]{40}\n?", head.stdout) else None
    for name in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        if re.fullmatch(r"[0-9]+", os.environ.get(name, "")):
            manifest[name] = os.environ[name]
    files = dict(video)
    access = probe_app_evidence_access()
    manifest["appEvidenceAccess"] = access
    if access["status"] != "available":
        manifest["screenshots"] = {"status": "missing-or-adb-failed", "reason": access["status"]}
    else:
        try:
            screenshots, manifest["screenshots"] = collect_screenshots()
            files.update(screenshots)
        except Exception as error:
            manifest["screenshots"] = capture_failure(error)
    try:
        files["junit-summary.xml"], manifest["junit"] = collect_junit(root, recording.get("gradleStartedAtMs", 0))
    except Exception as error:
        manifest["junit"] = {"status": "capture-failed", "errorType": type(error).__name__}
    if invocation == "connected-suite":
        if access["status"] != "available":
            manifest["playerLifecycle"] = {"status": "missing-or-unreadable", "reason": access["status"]}
            manifest["pluginStartup"] = {"status": "missing-or-unreadable", "reason": access["status"]}
        else:
            try:
                diagnostics, manifest["playerLifecycle"] = collect_player_lifecycle(
                    recording.get("gradleStartedAtMs", 0), recording.get("gradleFinishedAtMs", 0))
                files.update(diagnostics)
            except Exception as error:
                manifest["playerLifecycle"] = capture_failure(error)
            try:
                diagnostics, manifest["pluginStartup"] = collect_plugin_startup(
                    recording.get("gradleStartedAtMs", 0), recording.get("gradleFinishedAtMs", 0))
                files.update(diagnostics)
            except Exception as error:
                manifest["pluginStartup"] = capture_failure(error)
    selected_methods = [arg for arg in (command or COMMAND) if arg.startswith(RUNNER_ARGUMENT + "class=")]
    for key, selector, collector in (
            ("nativeSearchHardwareInput", NATIVE_SEARCH_HARDWARE_INPUT_TEST, collect_native_search_hardware_input),
            ("nativeSettingsPreservation", NATIVE_SETTINGS_PRESERVATION_TEST, collect_native_settings_preservation)):
        if invocation != "connected-suite" and selected_methods != [RUNNER_ARGUMENT + "class=" + selector]:
            continue
        if access["status"] != "available":
            manifest[key] = {"status": "missing-or-unreadable", "reason": access["status"]}
        else:
            try:
                diagnostics, manifest[key] = collector(
                    recording.get("gradleStartedAtMs", 0), recording.get("gradleFinishedAtMs", 0))
                files.update(diagnostics)
            except Exception as error:
                manifest[key] = capture_failure(error)
    owned = owned_fixture_profile(invocation, command or COMMAND)
    if owned:
        key = owned["statusKey"]
        if access["status"] != "available":
            manifest[key] = {"status": "missing-or-unreadable", "reason": access["status"]}
        else:
            try:
                fixture, manifest[key] = collect_owned_manifest(invocation)
                files.update(fixture)
            except Exception as error:
                manifest[key] = capture_failure(error)
        if manifest[key]["status"] != "captured":
            print("::warning::Owned fixture manifest is missing, ambiguous or invalid; evidence is incomplete", flush=True)
    hashes = {}
    for relative in APK_PATHS:
        path = root / relative
        if path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root.resolve()):
            with path.open("rb") as stream:
                hashes[relative] = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest["apkSha256"] = hashes
    manifest["missingApks"] = sorted(set(APK_PATHS) - hashes.keys())
    manifest["installedApkIdentity"] = match_installed_apks(
        recording.get("installedApks", {"status": "app-process-not-observed", "packages": {}}), hashes)
    manifest["fileSha256"] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    files["collection-status.json"] = json_bytes(manifest)
    (output / "collection-status.json").write_bytes(files["collection-status.json"])
    # Build a fresh archive only from validated bytes; never extract device tar paths.
    temporary = output / "evidence.zip.tmp"
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    with zipfile.ZipFile(temporary) as archive:
        if set(archive.namelist()) != set(files) or archive.testzip() is not None:
            raise ValueError("Evidence archive verification failed")
    temporary.replace(output / "evidence.zip")
    if manifest["screenshots"]["status"] != "captured" or recording["status"] != "captured":
        print("::warning::Some Android TV visual evidence is unavailable; see collection-status.json", flush=True)
    if manifest["installedApkIdentity"]["status"] != "matched":
        print("::warning::Installed Android TV app/test APK identity is incomplete or mismatched; see collection-status.json", flush=True)
    if invocation != "connected-suite" and (not manifest["junit"].get("tests") or manifest["junit"].get("skipped")):
        print("::warning::Selected Android TV invocation has missing or skipped tests; acceptance is not established", flush=True)


def main(root, invocation="connected-suite", test_class=None, runner_flags=()):
    adb_command()  # Retaining app data is restricted to an explicit CI emulator.
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", invocation):
        raise ValueError("Invalid evidence invocation name")
    command = list(COMMAND)
    if test_class:
        if invocation == "connected-suite":
            raise ValueError("A selected test requires a distinct evidence invocation name")
        if not re.fullmatch(r"app\.seanime\.tv\.[A-Za-z0-9_.$]+(?:#[A-Za-z0-9_$]+)?", test_class):
            raise ValueError("Only one Seanime instrumentation class or method can be selected")
        command.append("-Pandroid.testInstrumentationRunnerArguments.class=" + test_class)
    for flag in runner_flags:
        if not test_class or not re.fullmatch(r"(?:isolatedNativeGo[A-Za-z0-9]*Fixture|freshInstrumentationProcess)", flag):
            raise ValueError("Only explicit fixture boolean flags with a selected class are supported")
        command.append("-Pandroid.testInstrumentationRunnerArguments." + flag + "=true")
    if requires_owned_fixture(invocation, test_class) and owned_fixture_profile(invocation, command) is None:
        raise ValueError("Owned fixtures require their exact invocation, method, opt-in and fresh-process flags")
    output = root / Path(OUTPUT).parent / invocation
    try:
        # Exact outputs owned by this helper, so a retry cannot upload stale evidence.
        for name in ("evidence.zip", "evidence.zip.tmp", "collection-status.json"):
            (output / name).unlink(missing_ok=True)
    except OSError:
        print("::warning::Unable to clear prior Android TV evidence outputs", flush=True)
    stop = threading.Event()
    profile = recording_profile(invocation, command)
    recording = {"status": "waiting-for-app-process", **profile,
                 "evidenceKind": "instrumentation-fixtures-early-run-excerpt", "audioRecorded": False}
    recording["priorScreenshotCleanup"] = clear_prior_screenshots()
    video = {}
    worker = threading.Thread(target=record_startup, args=(stop, recording, video, profile), daemon=True)
    worker.start()
    recording["gradleStartedAtMs"] = int(time.time() * 1000)
    try:
        status = subprocess.run(command, cwd=root / "androidtv").returncode
        if status < 0:
            status = 128 - status
    except OSError:
        status = 127
    finally:
        recording["gradleFinishedAtMs"] = int(time.time() * 1000)
        stop.set()
    worker.join(profile["workerJoinTimeoutSeconds"])
    if worker.is_alive():
        recording = {**recording, "status": "capture-timeout"}
        video = {}
    # Evidence failures must never turn a failing test green or a passing test red.
    try:
        collect(root, status, recording, video, invocation, command)
    except Exception as error:
        print(f"::warning::Android TV evidence collection failed ({type(error).__name__}); Gradle exit code remains {status}", flush=True)
        try:
            output.mkdir(parents=True, exist_ok=True)
            (output / "evidence.zip").unlink(missing_ok=True)
            (output / "collection-status.json").write_bytes(json_bytes({
                "status": "collection-failed", "invocation": invocation,
                "gradleExitCode": status, "errorType": type(error).__name__}))
        except OSError:
            print("::warning::Android TV evidence status could not be saved", flush=True)
    finally:
        if owned_fixture_profile(invocation, command):
            try:
                stopped = subprocess.run(adb_command() + ["shell", "am", "force-stop", PACKAGE],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                if stopped.returncode != 0:
                    print("::warning::Unable to force-stop disposable owned-fixture app after capture", flush=True)
            except Exception:
                print("::warning::Disposable owned-fixture app force-stop failed after capture", flush=True)
    return status


def check_acceptance(root, invocation, test_class):
    """Separate evidence gate; never replaces the instrumentation command's exit code."""
    try:
        if (not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", invocation)
                or invocation == "connected-suite" or not test_class or "#" not in test_class):
            raise ValueError("A named invocation and exact class#method are required")
        output = root / Path(OUTPUT).parent / invocation
        raw_status = (output / "collection-status.json").read_bytes()
        manifest = json.loads(raw_status)
        if manifest.get("invocation") != invocation or manifest.get("gradleExitCode") != 0:
            raise ValueError("Selected instrumentation invocation did not pass")
        if "-Pandroid.testInstrumentationRunnerArguments.class=" + test_class not in manifest.get("command", []):
            raise ValueError("Selected class/method does not match the captured invocation")
        owned = owned_fixture_profile(invocation, manifest.get("command", []))
        if requires_owned_fixture(invocation, test_class) and owned is None:
            raise ValueError("Owned fixture method or required flags do not match the captured invocation")
        junit = manifest.get("junit", {})
        expected = {"tests": 1, "failures": 0, "errors": 0, "skipped": 0, "rejectedReportsOrCases": 0}
        if junit.get("status") != "sanitized" or any(junit.get(key) != value for key, value in expected.items()):
            raise ValueError("Expected exactly one test with zero failures, errors, skips or rejected results")
        identity = manifest.get("installedApkIdentity", {})
        if identity.get("status") != "matched":
            raise ValueError("Installed app/test APK identities were not verified")
        if owned and manifest.get(owned["statusKey"], {}).get("status") != "captured":
            raise ValueError("Owned fixture manifest evidence was not retained")
        for package in (PACKAGE, TEST_PACKAGE):
            entry = identity.get("packages", {}).get(package, {})
            digest = entry.get("sha256", "")
            if (entry.get("status") != "matched" or entry.get("matchesBuiltApk") is not True
                    or not re.fullmatch(r"[0-9a-f]{64}", digest)
                    or manifest.get("apkSha256", {}).get(entry.get("builtApk")) != digest):
                raise ValueError("Installed APK digest does not match its built artifact")
        with zipfile.ZipFile(output / "evidence.zip") as archive:
            if archive.testzip() is not None or archive.read("collection-status.json") != raw_status:
                raise ValueError("Evidence archive is invalid or disagrees with its status file")
            xml = archive.read("junit-summary.xml")
            if owned:
                fixture_path = f"fixtures/{invocation}.json"
                fixture = archive.read(fixture_path)
                if (manifest.get("fileSha256", {}).get(fixture_path) != hashlib.sha256(fixture).hexdigest()
                        or json.loads(fixture).get("outcome") != "passed"):
                    raise ValueError("Owned fixture manifest digest or outcome is not verified")
        if manifest.get("fileSha256", {}).get("junit-summary.xml") != hashlib.sha256(xml).hexdigest():
            raise ValueError("JUnit artifact digest does not match")
        cases = list(ET.fromstring(xml).iter("testcase"))
        classname, method = test_class.split("#", 1)
        if (len(cases) != 1 or cases[0].get("classname") != classname or cases[0].get("name") != method
                or any(cases[0].find(state) is not None for state in ("failure", "error", "skipped"))):
            raise ValueError("Captured JUnit does not contain exactly the selected passing test")
    except Exception as error:
        # Only fixed diagnostic text is emitted, never the contents of a report.
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(f"::error::Android TV selected-invocation acceptance failed: {reason}", flush=True)
        return 1
    print("Selected Android TV test passed once without skips; installed app/test APK hashes match. Visual and live-service acceptance are separate.", flush=True)
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--invocation", default="connected-suite", help="Distinct evidence output directory")
    parser.add_argument("--class", dest="test_class", help="One instrumentation class or class#method")
    parser.add_argument("--runner-flag", action="append", default=[], help="Explicit fixture boolean flag to set true")
    parser.add_argument("--check-acceptance", action="store_true", help="Check saved selected-test evidence without running Gradle or adb")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    sys.exit(check_acceptance(root, args.invocation, args.test_class) if args.check_acceptance
             else main(root, args.invocation, args.test_class, args.runner_flag))
