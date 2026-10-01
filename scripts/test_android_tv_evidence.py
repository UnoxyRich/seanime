import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
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
if args[:4] == ['exec-out', 'run-as', 'app.seanime.tv', 'head']:
    from pathlib import Path
    sys.stdout.buffer.write((Path(os.environ['FAKE_DEVICE']) / args[-1]).read_bytes()[:131073])
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
assert args[:5] == ['exec-out', 'run-as', 'app.seanime.tv', 'sh', '-c'], args
if os.environ.get('FAKE_ADB_FAILURE'):
    sys.exit(9)
sys.exit(subprocess.run(['sh', '-c', shlex.split(args[5])[0]], cwd=os.environ['FAKE_DEVICE']).returncode)
""")
        adb.chmod(0o755)
        self.environment = patch.dict(os.environ, {
            "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
            "ANDROID_SERIAL": "emulator-5554", "FAKE_DEVICE": str(device),
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def write_pair(self):
        (self.screenshots / (SCENARIO + ".png")).write_bytes(png())
        (self.screenshots / (SCENARIO + ".json")).write_text(json.dumps(metadata()))

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
        state, video, calls = {}, {}, []
        fake_mp4 = b"\x00\x00\x00\x10ftypisom0000moovmdat"
        def run(command, **kwargs):
            calls.append((command, kwargs["timeout"]))
            if command[3:] == ["exec-out", "cat", evidence.REMOTE_VIDEO]:
                kwargs["stdout"].write(fake_mp4)
            return subprocess.CompletedProcess(command, 0)
        with patch.object(evidence.subprocess, "run", side_effect=run), patch.object(
                evidence, "capture_installed_apks", return_value={"status": "test-fixture"}):
            evidence.record_startup(threading.Event(), state, video)
        self.assertEqual(state["status"], "captured")
        self.assertEqual(calls[1], (["adb", "-s", "emulator-5554", "shell", "screenrecord",
                                   "--time-limit", "60", "--size", "1280x720", "--bit-rate",
                                   "2000000", evidence.REMOTE_VIDEO], 75))
        self.assertEqual(video["recordings/instrumentation-startup-excerpt.mp4"], fake_mp4)

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
