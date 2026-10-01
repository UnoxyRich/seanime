import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile
import zlib

import run_android_tv_instrumentation as evidence


SCENARIO = "player-fresh-hud-play-focus"


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + chunk(b"IEND", b""))


def metadata():
    return {"scenario": SCENARIO, "width": 1, "height": 1, "sdk": 36, "capturedAtMs": 123,
            "note": "PRIVATE_SENTINEL", "device": "PRIVATE_SENTINEL", "extra": "PRIVATE_SENTINEL"}


def archive(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as tar:
        for name, data, kind in entries:
            entry = tarfile.TarInfo(name)
            entry.type = kind
            entry.size = len(data)
            tar.addfile(entry, io.BytesIO(data))
    stream.seek(0)
    return stream


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        device = self.root / "device"
        self.screenshots = device / "cache/native-acceptance-screenshots"
        self.screenshots.mkdir(parents=True)
        (self.root / "androidtv").mkdir()
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        adb = fake_bin / "adb"
        adb.write_text("""#!/usr/bin/env python3
import os, shlex, subprocess, sys
assert sys.argv[1:3] == ['-s', 'emulator-5554']
args = sys.argv[3:]
if args == ['shell', 'pidof', 'app.seanime.tv']:
    sys.exit(1)
if args == ['shell', 'getprop', 'ro.product.cpu.abi']:
    print('x86_64')
    sys.exit(0)
if args == ['shell', 'am', 'force-stop', 'app.seanime.tv']:
    sys.exit(0)
if args[:3] == ['shell', 'pm', 'path']:
    package = args[3]
    if os.environ.get('FAKE_MALFORMED_APK_PATH'):
        print('package:/data/app/' + package + '-fixture/../../private.db;echo-bad')
    else:
        print('package:/data/app/~~fixture/' + package + '-fixture==/base.apk')
    sys.exit(0)
if args[:2] == ['shell', 'sha256sum']:
    import hashlib
    digest = '0' * 64 if os.environ.get('FAKE_APK_DIGEST_MISMATCH') else hashlib.sha256(b'fixture APK bytes').hexdigest()
    print(digest + '  ' + args[2])
    sys.exit(0)
assert args[:3] == ['exec-out', 'run-as', 'app.seanime.tv'], args
if os.environ.get('FAKE_ADB_FAILURE'):
    sys.exit(9)
# AOSP client/commandline.cpp escapes all exec-out arguments after argv[1],
# then adbd executes the resulting command through a shell. The raw exec
# service merges stderr into stdout and does NOT forward remote exit status.
command = args[1] + ''.join(' ' + shlex.quote(arg) for arg in args[2:])
subprocess.run(['sh', '-c', command], stderr=subprocess.STDOUT)
sys.exit(0)
""")
        adb.chmod(0o755)
        run_as = fake_bin / "run-as"
        run_as.write_text("""#!/usr/bin/env python3
import os, sys
assert sys.argv[1] == 'app.seanime.tv'
os.chdir(os.environ['FAKE_DEVICE'])
os.execvp(sys.argv[2], sys.argv[2:])
""")
        run_as.chmod(0o755)
        self.environment = patch.dict(os.environ, {
            "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
            "ANDROID_SERIAL": "emulator-5554", "FAKE_DEVICE": str(device),
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def write_pair(self):
        (self.screenshots / (SCENARIO + ".png")).write_bytes(png())
        (self.screenshots / (SCENARIO + ".json")).write_text(json.dumps(metadata()))

    def test_exec_out_shell_roundtrip_preserves_script_and_merges_errors_without_exit_status(self):
        script = ('set -- "fixture with spaces" "quoted\'value"; printf "%s\\n" "$@"; '
                  'printf "remote-error\\n" >&2; exit 17')
        result = subprocess.run(evidence.exec_out_run_as("sh", "-c", script), capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"fixture with spaces\nquoted'value\nremote-error\n")
        self.assertEqual(result.stderr, b"")

    def test_extra_script_quoting_reproduces_ci_archive_and_manifest_failures(self):
        self.write_pair()
        original = evidence.exec_out_run_as
        def incorrectly_quoted(*arguments):
            if arguments[:2] == ("sh", "-c"):
                arguments = (*arguments[:2], shlex.quote(arguments[2]))
            return original(*arguments)
        with patch.object(evidence, "exec_out_run_as", side_effect=incorrectly_quoted):
            with self.assertRaises(tarfile.ReadError):
                evidence.collect_screenshots()
            with self.assertRaisesRegex(ValueError, "Unexpected owned fixture path"):
                evidence.collect_journey_manifest()
            self.assertEqual(evidence.clear_prior_screenshots(), "app-cache-not-available")
        self.assertTrue((self.screenshots / (SCENARIO + ".png")).is_file())

    def test_cleanup_requires_remote_receipt_despite_zero_transport_status(self):
        self.screenshots.rmdir()
        self.assertEqual(evidence.clear_prior_screenshots(), "app-cache-not-available")
        self.screenshots.mkdir()
        # rm fails for an unexpected directory with an otherwise allowed name.
        (self.screenshots / (SCENARIO + ".png")).mkdir()
        self.assertEqual(evidence.clear_prior_screenshots(), "app-cache-not-available")
        (self.screenshots / (SCENARIO + ".png")).rmdir()
        self.write_pair()
        self.assertEqual(evidence.clear_prior_screenshots(), "cleared")
        self.assertFalse(list(self.screenshots.iterdir()))

    def test_zero_exit_remote_errors_are_rejected_without_retaining_shell_output(self):
        command = evidence.exec_out_run_as("sh", "-c", 'printf "PRIVATE_SENTINEL\\n" >&2; exit 17')
        with patch.object(evidence, "screenshot_command", return_value=command):
            evidence.collect(self.root, 0, {"status": "app-process-not-observed"}, {})
        with zipfile.ZipFile(self.root / evidence.OUTPUT / "evidence.zip") as saved:
            state = json.loads(saved.read("collection-status.json"))
            self.assertEqual(state["screenshots"], {"status": "capture-failed", "errorType": "ReadError",
                                                   "reason": "Invalid or truncated screenshot archive"})
            self.assertNotIn(b"PRIVATE_SENTINEL", b"".join(saved.read(name) for name in saved.namelist()))

    def test_capture_failure_retains_only_allowlisted_reason_text(self):
        errors = [ValueError("PRIVATE_SENTINEL"), OSError("PRIVATE_SENTINEL"),
                  json.JSONDecodeError("PRIVATE_SENTINEL", "PRIVATE_SENTINEL", 0),
                  subprocess.TimeoutExpired("PRIVATE_SENTINEL", 5, output=b"PRIVATE_SENTINEL")]
        for error in errors:
            with self.subTest(error=type(error).__name__):
                self.assertNotIn("PRIVATE_SENTINEL", json.dumps(evidence.capture_failure(error)))
        self.assertEqual(evidence.capture_failure(ValueError("Unexpected owned fixture path"))["reason"],
                         "Unexpected owned fixture path")

    def test_fake_adb_collects_only_exact_regular_fixture_pairs(self):
        self.write_pair()
        (self.screenshots / "private.db").write_text("PRIVATE_SENTINEL")
        (self.screenshots / "unknown.png").write_bytes(png())
        (self.screenshots / "source-provider-torrent-focused.png").symlink_to(self.screenshots / "private.db")
        files, state = evidence.collect_screenshots()
        self.assertEqual(state["captured"], [SCENARIO])
        self.assertEqual(set(files), {f"screenshots/{SCENARIO}.png", f"screenshots/{SCENARIO}.json"})
        self.assertNotIn(b"PRIVATE_SENTINEL", b"".join(files.values()))

    def test_rejects_unknown_paths_links_and_duplicate_archive_members(self):
        for name, kind in (("../private.db", tarfile.REGTYPE), ("private.db", tarfile.REGTYPE),
                           (SCENARIO + ".png", tarfile.SYMTYPE), (SCENARIO + ".png", tarfile.LNKTYPE)):
            with self.subTest(name=name, kind=kind), self.assertRaises(ValueError):
                evidence.validate_screenshots(archive([(name, b"data", kind)]))
        entry = (SCENARIO + ".png", png(), tarfile.REGTYPE)
        with self.assertRaises(ValueError):
            evidence.validate_screenshots(archive([entry, entry]))

    def test_rejects_mismatched_and_incomplete_pairs(self):
        bad = {**metadata(), "width": 2}
        for entries in (
                [(SCENARIO + ".png", png(), tarfile.REGTYPE)],
                [(SCENARIO + ".png", png(), tarfile.REGTYPE),
                 (SCENARIO + ".json", json.dumps(bad).encode(), tarfile.REGTYPE)]):
            files, invalid = evidence.validate_screenshots(archive(entries))
            self.assertFalse(files)
            self.assertEqual(invalid, [SCENARIO])

    def test_junit_retains_test_outcomes_without_raw_logs_or_properties(self):
        results = self.root / "androidtv/app/build/outputs/androidTest-results/connected/debug/device"
        results.mkdir(parents=True)
        (results / "TEST-device.xml").write_text('''<testsuite>
          <properties><property name="password" value="PRIVATE_SENTINEL"/></properties>
          <testcase name="plays" classname="app.seanime.tv.PlayerTest" time="1.25"/>
          <testcase name="seeks" classname="app.seanime.tv.PlayerTest"><failure message="PRIVATE_SENTINEL">PRIVATE_SENTINEL</failure></testcase>
          <testcase name="skips" classname="app.seanime.tv.PlayerTest"><skipped>PRIVATE_SENTINEL</skipped></testcase>
          <system-out>PRIVATE_SENTINEL</system-out><system-err>PRIVATE_SENTINEL</system-err>
        </testsuite>''')
        data, state = evidence.collect_junit(self.root)
        self.assertEqual(state["sourceReports"], 1)
        self.assertNotIn(b"PRIVATE_SENTINEL", data)
        suite = ET.fromstring(data).find("testsuite")
        self.assertEqual(suite.get("tests"), "3")
        self.assertEqual(suite.get("failures"), "1")
        self.assertEqual(suite.get("skipped"), "1")
        self.assertEqual(state["skipped"], 1)
        self.assertEqual(state["tests"], 3)
        old_report_time = (results / "TEST-device.xml").stat().st_mtime_ns // 1_000_000
        _, stale_state = evidence.collect_junit(self.root, old_report_time + 10)
        self.assertEqual(stale_state["status"], "missing")
        self.assertEqual(stale_state["tests"], 0)

    def test_junit_keeps_safe_exception_frames_without_messages_urls_or_headers(self):
        results = self.root / "androidtv/app/build/outputs/androidTest-results/connected/debug"
        results.mkdir(parents=True)
        (results / "TEST-device.xml").write_text('''<testsuite><testcase name="remote" classname="app.seanime.tv.PlayerTest">
          <failure type="java.lang.AssertionError" message="PRIVATE_SENTINEL https://secret.invalid/?token=PRIVATE_SENTINEL">
java.lang.AssertionError: Authorization: Bearer PRIVATE_SENTINEL
    at app.seanime.tv.PlayerTest.remote(PlayerTest.kt:123)
Caused by: java.lang.IllegalStateException: https://secret.invalid/path
    at androidx.compose.ui.focus.FocusOwnerImpl.moveFocus(FocusOwnerImpl.kt:88)
    at okhttp3.internal.RealCall.execute(RealCall.kt:40)
    at app.seanime.tv.PlayerTest.bad(/private/PRIVATE_SENTINEL.kt:99)
          </failure></testcase></testsuite>''')
        data, state = evidence.collect_junit(self.root)
        self.assertEqual(state["failures"], 1)
        self.assertIn(b'java.lang.AssertionError', data)
        self.assertIn(b'java.lang.IllegalStateException', data)
        self.assertIn(b'app.seanime.tv.PlayerTest.remote(PlayerTest.kt:123)', data)
        self.assertIn(b'androidx.compose.ui.focus.FocusOwnerImpl.moveFocus(FocusOwnerImpl.kt:88)', data)
        for excluded in (b"PRIVATE_SENTINEL", b"https://", b"Authorization", b"okhttp3", b"/private/"):
            self.assertNotIn(excluded, data)

    def journey_manifest(self, directory="native-go-fixture-12345678-1234-4123-8123-123456789abc"):
        root = f"/data/user/0/{evidence.PACKAGE}/files/{directory}"
        return {"kind": evidence.JOURNEY_KIND, "root": root, "dataDir": root + "/data", "cacheDir": root + "/cache",
                "libraryDir": root + "/library", "mediaPath": root + "/library/Owned generated multitrack fixture.mkv",
                "indexPath": root + "/library/Owned unmatched index.json", "requiresColdRestart": True,
                "stage": "stopped-awaiting-force-stop-and-reviewed-cleanup", "outcome": "passed",
                "hostStatusAfterRun": "stopped", "recoveryClearedByPlayer": True, "audioDecodedBuffers": 10,
                "selectedAudioLanguage": "fra", "keyTrace": [{"key": "KEYCODE_DPAD_CENTER",
                "focused": "library-file-play-" + root + "/library/Owned generated multitrack fixture.mkv"}],
                "failureBody": "PRIVATE_SENTINEL", "retainedDataDir": "/private/PRIVATE_SENTINEL",
                "videoPaintAfterSeek": {"positionMs": 10000, "meanRgb": [100, 80, 60], "expectedRgb": [101, 81, 61], "channelTolerance": 25}}

    def test_journey_manifest_retains_only_owned_relative_paths_and_bounded_observations(self):
        directory = "native-go-fixture-12345678-1234-4123-8123-123456789abc"
        fixture = self.root / "device/files" / directory / "fixture.json"
        fixture.parent.mkdir(parents=True)
        fixture.write_text(json.dumps(self.journey_manifest(directory)))
        files, state = evidence.collect_journey_manifest()
        self.assertEqual(state["status"], "captured")
        data = files["fixtures/owned-library-journey.json"]
        clean = json.loads(data)
        self.assertEqual(clean["root"], directory)
        self.assertEqual(clean["mediaPath"], "library/Owned generated multitrack fixture.mkv")
        self.assertEqual(clean["keyTrace"][0]["focused"], "library-file-play-library/Owned generated multitrack fixture.mkv")
        self.assertEqual(clean["audioDecodedBuffers"], 10)
        for excluded in (b"PRIVATE_SENTINEL", b"/data/user/", b"/private/"):
            self.assertNotIn(excluded, data)
        second = fixture.parent.parent / "native-go-fixture-87654321-1234-4123-8123-123456789abc/fixture.json"
        second.parent.mkdir()
        second.write_text("{}")
        files, state = evidence.collect_journey_manifest()
        self.assertFalse(files)
        self.assertEqual(state["status"], "ambiguous-owned-roots")

    def test_journey_manifest_rejects_wrong_kind_paths_and_key_enums(self):
        directory = "native-go-fixture-12345678-1234-4123-8123-123456789abc"
        for field, value in (("kind", "other-fixture"), ("mediaPath", "/outside/private.mkv"),
                             ("keyTrace", [{"key": "PRIVATE_SENTINEL", "focused": "native-player-play"}])):
            with self.subTest(field=field), self.assertRaises(ValueError):
                evidence.sanitize_journey_manifest({**self.journey_manifest(directory), field: value}, directory)

    def picture_manifest(self):
        manifest = self.journey_manifest()
        paint = {"positionMs": 10000, "meanRgb": [100, 80, 60], "expectedRgb": [101, 81, 61],
                 "channelTolerance": 25, "region": "PRIVATE_SENTINEL"}
        aspect = {"viewportWidth": 1280, "viewportHeight": 720, "markerLeft": 820, "markerTop": 210,
                  "markerWidth": 72, "markerHeight": 72, "expectedLeft": 820.0, "expectedTop": 210.0,
                  "expectedSide": 72.0, "pillarboxWidth": 160.0, "maxBarChannel": 8, "path": "PRIVATE_SENTINEL"}
        manifest.update(picturePreferencesRestored=True,
                        stage="remote-picture-round-trip-and-checkpoint-verified",
                        verified=["remote-picture-round-trip"],
                        generatedVideoProbe={"streams": [{"codec_name": "h264", "width": 320, "height": 240,
                                                          "tags": {"title": "PRIVATE_SENTINEL"}}],
                                             "filename": "/private/PRIVATE_SENTINEL"},
                        pictureRoundTrip={"preset": "mode-c", "positionMs": 10000, "offWidth": 320, "offHeight": 240,
                                          "ownerRetained": True, "sourceRetained": True, "checkpointRetained": True,
                                          "audioTrackRetained": True, "subtitlesDisabledRetained": True,
                                          "enhancedPaint": paint, "directPaint": copy.deepcopy(paint),
                                          "enhancedAspect": aspect, "directAspect": copy.deepcopy(aspect),
                                          "sourcePath": "PRIVATE_SENTINEL"})
        manifest["keyTrace"] += [{"key": "KEYCODE_DPAD_CENTER", "focused": focused}
                                 for focused in ("native-player-picture", "native-player-choice-mode-c", "native-player-choice-off")]
        return manifest

    def test_picture_manifest_roundtrip_retains_only_typed_observations_and_known_tags(self):
        manifest = self.picture_manifest()
        fixture = self.root / "device/files" / manifest["root"].split("/")[-1] / "fixture.json"
        fixture.parent.mkdir(parents=True)
        fixture.write_text(json.dumps(manifest))
        files, state = evidence.collect_journey_manifest()
        self.assertEqual(state["status"], "captured")
        raw = files["fixtures/owned-library-journey.json"]
        clean = json.loads(raw)
        self.assertTrue(clean["picturePreferencesRestored"])
        self.assertEqual(clean["verified"], ["remote-picture-round-trip"])
        self.assertEqual(clean["stage"], "remote-picture-round-trip-and-checkpoint-verified")
        picture = clean["pictureRoundTrip"]
        self.assertEqual(picture["preset"], "mode-c")
        self.assertEqual((picture["offWidth"], picture["offHeight"]), (320, 240))
        self.assertEqual(picture["directAspect"]["pillarboxWidth"], 160.0)
        self.assertEqual(picture["enhancedPaint"]["meanRgb"], [100, 80, 60])
        self.assertEqual([event["focused"] for event in clean["keyTrace"][-3:]],
                         ["native-player-picture", "native-player-choice-mode-c", "native-player-choice-off"])
        self.assertEqual(clean["unrecognizedFocusObservations"], 0)
        self.assertNotIn("generatedVideoProbe", clean)
        self.assertNotIn(b"PRIVATE_SENTINEL", raw)
        self.assertNotIn(b"/data/user/", raw)

    def test_picture_manifest_rejects_malformed_or_untrusted_typed_fields(self):
        base = self.picture_manifest()
        mutations = [
            (("picturePreferencesRestored",), 1),
            (("verified",), ["remote-picture-round-trip", "PRIVATE_SENTINEL"]),
            (("verified",), ["remote-picture-round-trip"] * 2),
            (("verified",), [{"token": "PRIVATE_SENTINEL"}]),
            (("pictureRoundTrip",), []),
            (("pictureRoundTrip", "preset"), "PRIVATE_SENTINEL"),
            (("pictureRoundTrip", "positionMs"), 60_001),
            (("pictureRoundTrip", "positionMs"), True),
            (("pictureRoundTrip", "offWidth"), 1920),
            (("pictureRoundTrip", "offHeight"), 240.0),
            *[(("pictureRoundTrip", field), "PRIVATE_SENTINEL") for field in
              ("ownerRetained", "sourceRetained", "checkpointRetained", "audioTrackRetained", "subtitlesDisabledRetained")],
            (("pictureRoundTrip", "enhancedPaint", "meanRgb"), [100, 80, 256]),
            (("pictureRoundTrip", "directPaint", "channelTolerance"), 25.0),
            (("pictureRoundTrip", "directPaint"), None),
            (("pictureRoundTrip", "enhancedAspect", "viewportWidth"), 8193),
            (("pictureRoundTrip", "enhancedAspect", "markerWidth"), True),
            (("pictureRoundTrip", "enhancedAspect", "markerLeft"), 1279),
            (("pictureRoundTrip", "enhancedAspect", "expectedTop"), "PRIVATE_SENTINEL"),
            (("pictureRoundTrip", "enhancedAspect", "expectedLeft"), float("nan")),
            (("pictureRoundTrip", "directAspect", "expectedSide"), float("inf")),
            (("pictureRoundTrip", "directAspect", "expectedSide"), 0),
            (("pictureRoundTrip", "directAspect", "pillarboxWidth"), 641),
            (("pictureRoundTrip", "directAspect", "maxBarChannel"), -1),
            (("pictureRoundTrip", "directAspect"), None),
        ]
        for path, value in mutations:
            data = copy.deepcopy(base)
            target = data
            for field in path[:-1]:
                target = target[field]
            target[path[-1]] = value
            with self.subTest(path=path, value=value), self.assertRaises(ValueError) as rejected:
                evidence.sanitize_journey_manifest(data, data["root"].split("/")[-1])
            diagnostic = evidence.capture_failure(rejected.exception)
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(diagnostic))
            self.assertNotEqual(diagnostic["reason"], "Unexpected collector failure")

    def test_picture_manifest_preserves_false_observations_and_terminal_stage(self):
        data = self.picture_manifest()
        data["picturePreferencesRestored"] = False
        data["pictureRoundTrip"]["ownerRetained"] = False
        data["failedAt"] = data["stage"]
        data["stage"] += "-terminal"
        data["outcome"] = "failed"
        clean = evidence.sanitize_journey_manifest(data, data["root"].split("/")[-1])
        self.assertFalse(clean["picturePreferencesRestored"])
        self.assertFalse(clean["pictureRoundTrip"]["ownerRetained"])
        self.assertEqual(clean["failedAt"], "remote-picture-round-trip-and-checkpoint-verified")
        self.assertEqual(clean["outcome"], "failed")

    def test_journey_manifest_rejects_oversize_data_and_symbolic_links(self):
        directory = "native-go-fixture-12345678-1234-4123-8123-123456789abc"
        fixture = self.root / "device/files" / directory / "fixture.json"
        fixture.parent.mkdir(parents=True)
        fixture.write_bytes(b" " * 131073)
        with self.assertRaisesRegex(ValueError, "Missing or oversized owned fixture manifest"):
            evidence.collect_journey_manifest()
        fixture.unlink()
        private = self.root / "private.json"
        private.write_text("PRIVATE_SENTINEL")
        fixture.symlink_to(private)
        files, state = evidence.collect_journey_manifest()
        self.assertFalse(files)
        self.assertEqual(state["status"], "missing-or-unreadable")
        fixture.unlink()
        fixture.parent.rmdir()
        fixture.parent.symlink_to(self.root, target_is_directory=True)
        files, state = evidence.collect_journey_manifest()
        self.assertFalse(files)
        self.assertEqual(state["status"], "missing-or-unreadable")

    def test_gradle_exit_status_survives_missing_adb_evidence(self):
        with patch.dict(os.environ, {"FAKE_ADB_FAILURE": "1"}):
            for status in (0, 23, 137):
                with self.subTest(status=status):
                    gradle = self.root / "androidtv/gradlew"
                    gradle.write_text(f"#!/bin/sh\nexit {status}\n")
                    gradle.chmod(0o755)
                    self.assertEqual(evidence.main(self.root), status)
                    output = self.root / evidence.OUTPUT
                    state = json.loads((output / "collection-status.json").read_text())
                    self.assertEqual(state["gradleExitCode"], status)
                    self.assertEqual(state["screenshots"]["status"], "missing-or-adb-failed")
                    with zipfile.ZipFile(output / "evidence.zip") as saved:
                        self.assertEqual(set(saved.namelist()), {"junit-summary.xml", "collection-status.json"})
                        self.assertIsNone(saved.testzip())

    def test_collector_exception_does_not_replace_gradle_failure(self):
        gradle = self.root / "androidtv/gradlew"
        gradle.write_text("#!/bin/sh\nexit 19\n")
        gradle.chmod(0o755)
        with patch.object(evidence, "collect", side_effect=OSError("PRIVATE_SENTINEL")):
            self.assertEqual(evidence.main(self.root), 19)
        state = json.loads((self.root / evidence.OUTPUT / "collection-status.json").read_text())
        self.assertEqual(state["status"], "collection-failed")
        self.assertEqual(state["gradleExitCode"], 19)

    def test_recording_uses_bounded_settings_and_labels_no_audio(self):
        fake_mp4 = b"\x00\x00\x00\x10ftypisom0000moovmdat"
        for invocation, selected, seconds, bitrate in (
                ("connected-suite", None, 60, 2_000_000),
                ("owned-library-journey", evidence.OWNED_JOURNEY, 120, 1_000_000)):
            state, video, calls = {"audioRecorded": False}, {}, []
            command = evidence.COMMAND + (["-Pandroid.testInstrumentationRunnerArguments.class=" + selected] if selected else [])
            profile = evidence.recording_profile(invocation, command)
            def run(command, **kwargs):
                calls.append((command, kwargs["timeout"]))
                if command[3:] == ["exec-out", "cat", evidence.REMOTE_VIDEO]:
                    kwargs["stdout"].write(fake_mp4)
                return subprocess.CompletedProcess(command, 0)
            with self.subTest(invocation=invocation), patch.object(evidence.subprocess, "run", side_effect=run), patch.object(
                    evidence, "capture_installed_apks", return_value={"status": "test-fixture"}):
                evidence.record_startup(threading.Event(), state, video, profile)
            self.assertEqual(state["status"], "captured")
            self.assertEqual(calls[1], (["adb", "-s", "emulator-5554", "shell", "screenrecord",
                                       "--time-limit", str(seconds), "--size", "1280x720", "--bit-rate",
                                       str(bitrate), evidence.REMOTE_VIDEO], seconds + 15))
            self.assertEqual(calls[2][1], 15)
            self.assertEqual(state["maxSeconds"], seconds)
            self.assertEqual(state["bitRateBitsPerSecond"], bitrate)
            self.assertEqual(state["maxBytes"], 32 * 1024 * 1024)
            self.assertEqual(state["fileBytes"], len(fake_mp4))
            self.assertFalse(state["audioRecorded"])
            self.assertIn("bounded excerpt only", state["coverage"])
            self.assertEqual(video["recordings/instrumentation-startup-excerpt.mp4"], fake_mp4)

    def test_recording_profile_requires_exact_owned_journey_invocation_and_method(self):
        for invocation, selected, seconds in (
                ("owned-library-journey", evidence.OWNED_JOURNEY, 120),
                ("other-journey", evidence.OWNED_JOURNEY, 60),
                ("owned-library-journey", evidence.OWNED_JOURNEY + "Other", 60),
                ("owned-library-journey", evidence.OWNED_JOURNEY.split("#")[0], 60),
                ("owned-library-journey", None, 60),
                ("connected-suite", None, 60)):
            command = evidence.COMMAND + (["-Pandroid.testInstrumentationRunnerArguments.class=" + selected] if selected else [])
            with self.subTest(invocation=invocation, selected=selected):
                profile = evidence.recording_profile(invocation, command)
                self.assertEqual(profile["maxSeconds"], seconds)
                self.assertEqual(profile["recordCommandTimeoutSeconds"], seconds + 15)
                self.assertEqual(profile["workerJoinTimeoutSeconds"], seconds + 75)
                self.assertEqual(profile["appWaitMaxSeconds"], 180)

    def test_main_forwards_selected_recording_settings_and_wait_budget(self):
        gradle = self.root / "androidtv/gradlew"
        gradle.write_text("#!/bin/sh\nexit 0\n")
        gradle.chmod(0o755)
        for invocation, selected, seconds in (
                ("connected-suite", None, 60),
                ("owned-library-journey", evidence.OWNED_JOURNEY, 120)):
            with self.subTest(invocation=invocation), patch.object(evidence.threading, "Thread") as thread, patch.object(
                    evidence, "collect") as collect:
                thread.return_value.is_alive.return_value = False
                self.assertEqual(evidence.main(self.root, invocation, selected), 0)
                profile = thread.call_args.kwargs["args"][3]
                self.assertEqual(profile["maxSeconds"], seconds)
                self.assertEqual(thread.call_args.kwargs["target"], evidence.record_startup)
                thread.return_value.join.assert_called_once_with(seconds + 75)
                recording = collect.call_args.args[2]
                self.assertEqual(recording["maxSeconds"], seconds)
                self.assertEqual(recording["workerJoinTimeoutSeconds"], seconds + 75)
                self.assertFalse(recording["audioRecorded"])

    def test_recording_rejects_oversized_and_unfinalized_mp4_for_both_profiles(self):
        for invocation, oversized in (("connected-suite", False), ("connected-suite", True),
                                      ("owned-library-journey", False), ("owned-library-journey", True)):
            state, video = {}, {}
            command = evidence.COMMAND + ["-Pandroid.testInstrumentationRunnerArguments.class=" + evidence.OWNED_JOURNEY]
            profile = evidence.recording_profile(invocation, command)
            def run(command, **kwargs):
                if command[3:] == ["exec-out", "cat", evidence.REMOTE_VIDEO]:
                    if oversized:
                        kwargs["stdout"].seek(32 * 1024 * 1024)
                    kwargs["stdout"].write(b"\x00\x00\x00\x10ftypisom0000mdat")
                return subprocess.CompletedProcess(command, 0)
            with self.subTest(invocation=invocation, oversized=oversized), patch.object(
                    evidence.subprocess, "run", side_effect=run), patch.object(
                    evidence, "capture_installed_apks", return_value={"status": "test-fixture"}):
                evidence.record_startup(threading.Event(), state, video, profile)
            self.assertEqual(state["status"], "capture-failed")
            self.assertFalse(video)
            self.assertNotIn("fileBytes", state)

    def test_installed_app_and_test_apks_match_relevant_build_hashes(self):
        snapshot = evidence.capture_installed_apks()
        hashes = {path: hashlib.sha256(b"fixture APK bytes").hexdigest() for path in evidence.APK_PATHS}
        matched = evidence.match_installed_apks(snapshot, hashes)
        self.assertEqual(matched["status"], "matched")
        self.assertEqual(matched["abi"], "x86_64")
        self.assertEqual(matched["packages"][evidence.PACKAGE]["builtApk"], evidence.APK_PATHS[1])
        self.assertEqual(matched["packages"][evidence.TEST_PACKAGE]["builtApk"], evidence.APK_PATHS[2])
        self.assertTrue(all(entry["matchesBuiltApk"] for entry in matched["packages"].values()))

    def test_installed_apk_malformed_paths_are_rejected_before_hashing(self):
        with patch.dict(os.environ, {"FAKE_MALFORMED_APK_PATH": "1"}):
            snapshot = evidence.capture_installed_apks()
        self.assertTrue(all(entry["status"] == "unexpected-package-path" for entry in snapshot["packages"].values()))
        self.assertNotIn("installedPath", snapshot["packages"][evidence.PACKAGE])
        self.assertNotIn("sha256", snapshot["packages"][evidence.PACKAGE])

    def test_installed_apk_digest_mismatch_is_not_reported_as_match(self):
        with patch.dict(os.environ, {"FAKE_APK_DIGEST_MISMATCH": "1"}):
            snapshot = evidence.capture_installed_apks()
        hashes = {path: hashlib.sha256(b"fixture APK bytes").hexdigest() for path in evidence.APK_PATHS}
        matched = evidence.match_installed_apks(snapshot, hashes)
        self.assertEqual(matched["status"], "mismatch")
        self.assertTrue(all(entry["matchesBuiltApk"] is False for entry in matched["packages"].values()))

    def test_success_archive_contains_only_checked_evidence_and_apk_hashes(self):
        self.write_pair()
        for relative in evidence.APK_PATHS:
            apk = self.root / relative
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.write_bytes(b"fixture APK bytes")
        evidence.collect(self.root, 0, {"status": "app-process-not-observed"}, {})
        with zipfile.ZipFile(self.root / evidence.OUTPUT / "evidence.zip") as saved:
            state = json.loads(saved.read("collection-status.json"))
            self.assertEqual(set(state["apkSha256"]), set(evidence.APK_PATHS))
            self.assertEqual(state["missingApks"], [])
            self.assertEqual(state["realServiceAcceptance"], "not-established")
            self.assertEqual(set(saved.namelist()), {
                f"screenshots/{SCENARIO}.png", f"screenshots/{SCENARIO}.json",
                "junit-summary.xml", "collection-status.json"})
            self.assertNotIn(b"PRIVATE_SENTINEL", b"".join(saved.read(name) for name in saved.namelist()))

    def test_recording_wait_is_bounded_and_never_falls_back_to_default_device(self):
        state, video = {}, {}
        with patch.object(evidence.time, "monotonic", side_effect=[0, evidence.APP_WAIT_SECONDS + 1]):
            evidence.record_startup(threading.Event(), state, video)
        self.assertEqual(state["status"], "app-process-not-observed-within-budget")
        self.assertFalse(video)
        with patch.dict(os.environ, {"ANDROID_SERIAL": "personal-phone"}), self.assertRaises(ValueError):
            evidence.adb_command()

    def test_named_invocation_preserves_suite_artifact_and_records_exact_arguments(self):
        self.write_pair()
        (self.screenshots / "private.db").write_text("PRIVATE_SENTINEL")
        suite_output = self.root / evidence.OUTPUT
        suite_output.mkdir(parents=True)
        (suite_output / "evidence.zip").write_bytes(b"prior suite evidence")
        gradle = self.root / "androidtv/gradlew"
        gradle.write_text("#!/bin/sh\nexit 0\n")
        gradle.chmod(0o755)
        selected = "app.seanime.tv.AndroidIsolatedLibraryPlaybackJourneyTest#remoteOnlyLibraryFilesAndExplorerPlayOwnedMultitrackVideo"
        flags = ["isolatedNativeGoLibraryPlaybackJourneyFixture", "freshInstrumentationProcess"]
        self.assertEqual(evidence.main(self.root, "owned-library-journey", selected, flags), 0)
        self.assertFalse((self.screenshots / (SCENARIO + ".png")).exists())
        self.assertEqual((self.screenshots / "private.db").read_text(), "PRIVATE_SENTINEL")
        self.assertEqual((suite_output / "evidence.zip").read_bytes(), b"prior suite evidence")
        status_path = suite_output.parent / "owned-library-journey/collection-status.json"
        state = json.loads(status_path.read_text())
        self.assertEqual(state["invocation"], "owned-library-journey")
        self.assertEqual(state["command"], evidence.COMMAND + [
            "-Pandroid.testInstrumentationRunnerArguments.class=" + selected,
            *["-Pandroid.testInstrumentationRunnerArguments." + flag + "=true" for flag in flags]])
        self.assertEqual(state["junit"]["tests"], 0)
        with self.assertRaises(ValueError):
            evidence.main(self.root, "connected-suite", selected, flags)

    def test_force_stop_is_after_capture_only_for_exact_journey_and_preserves_failure(self):
        gradle = self.root / "androidtv/gradlew"
        gradle.write_text("#!/bin/sh\nexit 17\n")
        gradle.chmod(0o755)
        original_run = evidence.subprocess.run
        for invocation, selected, expected in (
                ("owned-library-journey", evidence.OWNED_JOURNEY, ["collect", "force-stop"]),
                ("connected-suite", None, ["collect"])):
            events = []
            def collect(*args, **kwargs):
                events.append("collect")
                raise OSError("Expected evidence failure")
            def run(command, **kwargs):
                if command[3:] == ["shell", "am", "force-stop", evidence.PACKAGE]:
                    events.append("force-stop")
                    return subprocess.CompletedProcess(command, 1)
                return original_run(command, **kwargs)
            with self.subTest(invocation=invocation), patch.object(evidence, "collect", side_effect=collect), patch.object(
                    evidence.subprocess, "run", side_effect=run):
                self.assertEqual(evidence.main(self.root, invocation, selected), 17)
            self.assertEqual(events, expected)

    def write_acceptance_fixture(self, *, skipped=0, tests=1, failures=0, errors=0, installed_status="matched", gradle_status=0):
        invocation = "owned-library-journey"
        classname = "app.seanime.tv.AndroidIsolatedLibraryPlaybackJourneyTest"
        method = "remoteOnlyLibraryFilesAndExplorerPlayOwnedMultitrackVideo"
        selected = classname + "#" + method
        xml = (f'<testsuites><testsuite><testcase classname="{classname}" name="{method}">'
               + ("<skipped/>" if skipped else "") + "</testcase></testsuite></testsuites>").encode()
        digest = hashlib.sha256(b"fixture APK bytes").hexdigest()
        manifest = {
            "invocation": invocation, "gradleExitCode": gradle_status,
            "command": evidence.COMMAND + ["-Pandroid.testInstrumentationRunnerArguments.class=" + selected],
            "junit": {"status": "sanitized", "tests": tests, "failures": failures, "errors": errors,
                      "skipped": skipped, "rejectedReportsOrCases": 0},
            "installedApkIdentity": {"status": installed_status, "packages": {
                package: {"status": "matched", "matchesBuiltApk": True, "sha256": digest, "builtApk": relative}
                for package, relative in ((evidence.PACKAGE, evidence.APK_PATHS[1]), (evidence.TEST_PACKAGE, evidence.APK_PATHS[2]))}},
            "apkSha256": {path: digest for path in evidence.APK_PATHS},
            "fileSha256": {"junit-summary.xml": hashlib.sha256(xml).hexdigest()},
            "ownedJourneyManifest": {"status": "captured", "outcome": "passed"},
        }
        output = self.root / Path(evidence.OUTPUT).parent / invocation
        output.mkdir(parents=True, exist_ok=True)
        fixture = evidence.json_bytes({"outcome": "passed"})
        manifest["fileSha256"]["fixtures/owned-library-journey.json"] = hashlib.sha256(fixture).hexdigest()
        status = evidence.json_bytes(manifest)
        (output / "collection-status.json").write_bytes(status)
        with zipfile.ZipFile(output / "evidence.zip", "w") as archive:
            archive.writestr("collection-status.json", status)
            archive.writestr("junit-summary.xml", xml)
            archive.writestr("fixtures/owned-library-journey.json", fixture)
        return invocation, selected

    def test_selected_acceptance_requires_one_unskipped_test_and_matching_installed_pair(self):
        invocation, selected = self.write_acceptance_fixture()
        with patch.object(evidence.subprocess, "run", side_effect=AssertionError("Acceptance must be read-only")):
            self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 0)
            for invalid in ({"skipped": 1}, {"tests": 0}, {"tests": 2}, {"failures": 1}, {"errors": 1},
                            {"installed_status": "mismatch"}, {"gradle_status": 23}):
                with self.subTest(invalid=invalid):
                    self.write_acceptance_fixture(**invalid)
                    self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 1)

    def test_selected_acceptance_rejects_missing_or_inconsistent_artifacts(self):
        invocation, selected = self.write_acceptance_fixture()
        output = self.root / Path(evidence.OUTPUT).parent / invocation
        (output / "collection-status.json").write_text("{}")
        self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 1)
        self.write_acceptance_fixture()
        (output / "evidence.zip").unlink()
        self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 1)

    def test_literal_capture_names_are_in_allowlist(self):
        sources = Path(__file__).resolve().parent.parent / "androidtv/app/src/androidTest"
        for source in sources.rglob("*.kt"):
            names = re.findall(r'NativeScreenshotEvidence\.capture\("([a-z0-9_-]+)"\)', source.read_text())
            self.assertFalse(set(names) - evidence.SCENARIOS, source)


if __name__ == "__main__":
    unittest.main()
