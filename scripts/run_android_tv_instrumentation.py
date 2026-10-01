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
COMMAND = ["./gradlew", ":app:connectedDebugAndroidTest", "--no-daemon"]
OUTPUT = "androidtv/build/native-ci-evidence/connected-suite"
REMOTE_VIDEO = "/data/local/tmp/seanime-native-ci-startup.mp4"
VIDEO_SECONDS = 60
VIDEO_BIT_RATE = 2_000_000
OWNED_JOURNEY_VIDEO_SECONDS = 120
OWNED_JOURNEY_VIDEO_BIT_RATE = 1_000_000
VIDEO_MAX_BYTES = 32 * 1024 * 1024
APP_WAIT_SECONDS = 180
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
isolated-go-external-receiver-streaming
isolated-go-external-return-paused
isolated-go-generated-local-media
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
native-torrent-file-priority-return
native-torrent-peers
native-torrent-session-limits-accepted
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
settings-device-last-row-restored
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


def screenshot_command():
    names = " ".join(sorted(EXPECTED_FILES))
    script = (
        "cd cache/native-acceptance-screenshots || exit 2; set --; "
        f"for name in {names}; do "
        '[ -f "$name" ] && [ ! -L "$name" ] && set -- "$@" "$name"; '
        'done; [ "$#" -gt 0 ] || exit 3; exec tar -cf - "$@"'
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
        "Invalid verified observation",
        "Invalid observation enum", "Unexpected owned fixture path",
        "Missing or oversized owned fixture manifest",
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
        files, invalid = validate_screenshots(stream)
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
    if "picturePreferencesRestored" in data:
        if type(data["picturePreferencesRestored"]) is not bool:
            raise ValueError("Invalid boolean observation")
        clean["picturePreferencesRestored"] = data["picturePreferencesRestored"]
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


def collect_journey_manifest():
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
    match = re.fullmatch(r"files/(native-go-fixture-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/fixture\.json", paths[0])
    if match is None:
        raise ValueError("Unexpected owned fixture path")
    result = subprocess.run(exec_out_run_as("head", "-c", "131073", paths[0]),
                            capture_output=True, timeout=5)
    if result.returncode != 0 or not 0 < len(result.stdout) <= 131072:
        raise ValueError("Missing or oversized owned fixture manifest")
    clean = sanitize_journey_manifest(json.loads(result.stdout), match.group(1))
    return {"fixtures/owned-library-journey.json": json_bytes(clean)}, {"status": "captured", "fixtureRoot": match.group(1), "outcome": clean["outcome"]}


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
    try:
        screenshots, manifest["screenshots"] = collect_screenshots()
        files.update(screenshots)
    except Exception as error:
        manifest["screenshots"] = capture_failure(error)
    try:
        files["junit-summary.xml"], manifest["junit"] = collect_junit(root, recording.get("gradleStartedAtMs", 0))
    except Exception as error:
        manifest["junit"] = {"status": "capture-failed", "errorType": type(error).__name__}
    if is_owned_journey(invocation, command or COMMAND):
        try:
            fixture, manifest["ownedJourneyManifest"] = collect_journey_manifest()
            files.update(fixture)
        except Exception as error:
            manifest["ownedJourneyManifest"] = capture_failure(error)
        if manifest["ownedJourneyManifest"]["status"] != "captured":
            print("::warning::Owned journey fixture manifest is missing, ambiguous or invalid; evidence is incomplete", flush=True)
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
        if is_owned_journey(invocation, command):
            try:
                stopped = subprocess.run(adb_command() + ["shell", "am", "force-stop", PACKAGE],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                if stopped.returncode != 0:
                    print("::warning::Unable to force-stop disposable owned-journey app after capture", flush=True)
            except Exception:
                print("::warning::Disposable owned-journey app force-stop failed after capture", flush=True)
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
        junit = manifest.get("junit", {})
        expected = {"tests": 1, "failures": 0, "errors": 0, "skipped": 0, "rejectedReportsOrCases": 0}
        if junit.get("status") != "sanitized" or any(junit.get(key) != value for key, value in expected.items()):
            raise ValueError("Expected exactly one test with zero failures, errors, skips or rejected results")
        identity = manifest.get("installedApkIdentity", {})
        if identity.get("status") != "matched":
            raise ValueError("Installed app/test APK identities were not verified")
        if is_owned_journey(invocation, manifest.get("command", [])) and manifest.get("ownedJourneyManifest", {}).get("status") != "captured":
            raise ValueError("Owned journey fixture manifest evidence was not retained")
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
            if is_owned_journey(invocation, manifest.get("command", [])):
                fixture = archive.read("fixtures/owned-library-journey.json")
                if (manifest.get("fileSha256", {}).get("fixtures/owned-library-journey.json") != hashlib.sha256(fixture).hexdigest()
                        or json.loads(fixture).get("outcome") != "passed"):
                    raise ValueError("Owned journey manifest digest or outcome is not verified")
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
