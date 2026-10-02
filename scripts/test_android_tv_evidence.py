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
    from pathlib import Path
    if os.environ.get('FAKE_PACKAGE_QUERY_ERROR'):
        print('PRIVATE_SENTINEL', file=sys.stderr)
        sys.exit(1)
    if os.environ.get('FAKE_APP_MISSING') or (os.environ.get('FAKE_ENFORCE_INSTALL_STATE') and
            not (Path(os.environ['FAKE_DEVICE']) / '.installed').exists()):
        sys.exit(1)
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
from pathlib import Path
if os.environ.get('FAKE_RUN_AS_DENIED'):
    print('run-as: PRIVATE_SENTINEL', file=sys.stderr)
    sys.exit(1)
if os.environ.get('FAKE_APP_MISSING') or (os.environ.get('FAKE_ENFORCE_INSTALL_STATE') and
        not (Path(os.environ['FAKE_DEVICE']) / '.installed').exists()):
    print('run-as: unknown package: app.seanime.tv', file=sys.stderr)
    sys.exit(1)
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

    def test_absent_or_empty_screenshot_directory_is_not_a_corrupt_archive(self):
        for exists in (True, False):
            with self.subTest(directory_exists=exists):
                if not exists:
                    self.screenshots.rmdir()
                files, state = evidence.collect_screenshots()
                self.assertEqual(files, {})
                self.assertEqual(state["status"], "not-created")
                self.assertEqual(state["captured"], [])
                self.assertEqual(state["notCaptured"], sorted(evidence.SCENARIOS))

    def test_screenshot_directory_symlink_never_reads_its_target(self):
        self.write_pair()
        alternate = self.screenshots.with_name("private-target")
        self.screenshots.rename(alternate)
        self.screenshots.symlink_to(alternate, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Empty screenshot archive"):
            evidence.collect_screenshots()

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

    def test_app_access_distinguishes_missing_package_transport_and_run_as_failures(self):
        self.assertEqual(evidence.probe_app_evidence_access(), {"status": "available"})
        for environment, expected in (
                ({"FAKE_APP_MISSING": "1"}, "package-not-installed"),
                ({"FAKE_RUN_AS_DENIED": "1"}, "run-as-unavailable"),
                ({"FAKE_ADB_FAILURE": "1"}, "run-as-transport-failed"),
                ({"FAKE_PACKAGE_QUERY_ERROR": "1"}, "package-query-failed"),
                ({"FAKE_MALFORMED_APK_PATH": "1"}, "unexpected-package-path")):
            with self.subTest(expected=expected), patch.dict(os.environ, environment):
                state = evidence.probe_app_evidence_access()
                self.assertEqual(state["status"], expected)
                self.assertNotIn("PRIVATE_SENTINEL", json.dumps(state))

    def test_unavailable_app_skips_private_reads_and_retains_safe_diagnostics(self):
        command = self.owned_command("owned-library-journey")
        for environment, expected in (({"FAKE_APP_MISSING": "1"}, "package-not-installed"),
                                      ({"FAKE_RUN_AS_DENIED": "1"}, "run-as-unavailable")):
            with self.subTest(expected=expected), patch.dict(os.environ, environment), patch.object(
                    evidence, "collect_screenshots", side_effect=AssertionError("Must not read unavailable app")), patch.object(
                    evidence, "collect_owned_manifest", side_effect=AssertionError("Must not read unavailable app")):
                evidence.collect(self.root, 0, {"status": "app-process-not-observed"}, {}, "owned-library-journey", command)
            with zipfile.ZipFile(self.root / Path(evidence.OUTPUT).parent / "owned-library-journey/evidence.zip") as saved:
                state = json.loads(saved.read("collection-status.json"))
                self.assertEqual(state["appEvidenceAccess"]["status"], expected)
                self.assertEqual(state["screenshots"]["reason"], expected)
                self.assertEqual(state["ownedJourneyManifest"]["reason"], expected)
                self.assertNotIn(b"PRIVATE_SENTINEL", b"".join(saved.read(name) for name in saved.namelist()))

    def test_keep_apks_option_preserves_private_evidence_through_gradle_teardown(self):
        manifest = self.journey_manifest()
        gradle = self.root / "androidtv/gradlew"
        # Model AGP's default uninstall after the test: app data is gone before
        # Gradle exits unless its actual keep-installed option was supplied.
        gradle.write_text("#!/usr/bin/env python3\n" +
                          "import os, shutil, sys\nfrom pathlib import Path\n" +
                          "device = Path(os.environ['FAKE_DEVICE'])\n" +
                          "(device / '.installed').touch()\n" +
                          "screens = device / 'cache/native-acceptance-screenshots'\n" +
                          "screens.mkdir(parents=True, exist_ok=True)\n" +
                          f"(screens / {SCENARIO + '.png'!r}).write_bytes({png()!r})\n" +
                          f"(screens / {SCENARIO + '.json'!r}).write_text({json.dumps(metadata())!r})\n" +
                          f"fixture = device / 'files' / {manifest['root'].split('/')[-1]!r} / 'fixture.json'\n" +
                          "fixture.parent.mkdir(parents=True, exist_ok=True)\n" +
                          f"fixture.write_text({json.dumps(manifest)!r})\n" +
                          "if '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' not in sys.argv:\n" +
                          "    (device / '.installed').unlink()\n" +
                          "    shutil.rmtree(device / 'cache')\n    shutil.rmtree(device / 'files')\n")
        gradle.chmod(0o755)
        flags = [evidence.OWNED_FIXTURES["owned-library-journey"]["flag"], "freshInstrumentationProcess"]
        with patch.dict(os.environ, {"FAKE_ENFORCE_INSTALL_STATE": "1"}):
            self.assertEqual(evidence.main(self.root, "owned-library-journey", evidence.OWNED_JOURNEY, flags), 0)
            output = self.root / Path(evidence.OUTPUT).parent / "owned-library-journey"
            with zipfile.ZipFile(output / "evidence.zip") as saved:
                state = json.loads(saved.read("collection-status.json"))
                self.assertIn(evidence.KEEP_APKS_ARGUMENT, state["command"])
                self.assertEqual(state["appEvidenceAccess"]["status"], "available")
                self.assertEqual(state["screenshots"]["status"], "captured")
                self.assertEqual(state["ownedJourneyManifest"]["status"], "captured")
                self.assertIn(f"screenshots/{SCENARIO}.png", saved.namelist())
                self.assertIn("fixtures/owned-library-journey.json", saved.namelist())
            # Omitting the option reproduces the independent teardown failure.
            subprocess.run([str(gradle)], check=True, timeout=5)
            self.assertEqual(evidence.probe_app_evidence_access()["status"], "package-not-installed")
            with self.assertRaises(tarfile.ReadError):
                evidence.collect_screenshots()
            with self.assertRaisesRegex(ValueError, "Unexpected owned fixture path"):
                evidence.collect_journey_manifest()

    def test_retention_never_runs_gradle_without_explicit_emulator_serial(self):
        with patch.dict(os.environ, {"ANDROID_SERIAL": "personal-device"}), patch.object(
                evidence.subprocess, "run", side_effect=AssertionError("Must reject before commands")), self.assertRaises(ValueError):
            evidence.main(self.root)

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

    def lifecycle_evidence(self):
        stage = {"id": "initial-autoplay", "result": "ready", "playerPresent": True,
                 "playbackState": 3, "positionMs": 4000, "durationMs": 12000,
                 "paused": True, "mediaMatches": True, "readyForMs": 0}
        failed = dict(stage, id="resume-after-stop", result="error", playbackState=1, readyForMs=0,
                      errorCode=1004, errorSummary="category=other causes=androidx.media3.exoplayer.ExoPlaybackException>java.lang.IllegalStateException cycle=false truncated=false playerCode=1004",
                      causeFrames=[{"causeDepth": 0, "className": "androidx.media3.exoplayer.ExoPlayerImplInternal",
                                    "methodName": "handleMessage", "lineNumber": 700},
                                   {"causeDepth": 1, "className": "androidx.media3.exoplayer.audio.DefaultAudioSink",
                                    "methodName": "flush", "lineNumber": 1200}])
        # Throw sites are synthetic validator fixtures, not an R30 diagnosis.
        return {"schemaVersion": 1, "scenario": "player-lifecycle-recreation", "outcome": "failed",
                "startedAtMs": 1100, "completedAtMs": 1500, "stages": [stage, failed]}

    def write_lifecycle_evidence(self, data):
        path = self.root / "device" / evidence.PLAYER_LIFECYCLE_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))
        return path

    def test_lifecycle_retains_typed_failure_stage_and_cause_frames(self):
        original = self.lifecycle_evidence()
        self.write_lifecycle_evidence(original)
        files, state = evidence.collect_player_lifecycle(1000, 1600)
        self.assertEqual(state["status"], "captured")
        self.assertEqual(state["outcome"], "failed")
        self.assertEqual(json.loads(files["diagnostics/player-lifecycle-recreation.json"]), original)
        self.assertEqual(state["stageCount"], 2)

    def test_lifecycle_running_and_complete_pass_require_their_actual_checkpoints(self):
        data = self.lifecycle_evidence()
        data["outcome"] = "running"
        data.pop("completedAtMs")
        data["stages"] = [data["stages"][0]]
        self.assertEqual(evidence.sanitize_player_lifecycle(data, 1000, 1600), data)
        data["outcome"] = "passed"
        data["completedAtMs"] = 1500
        with self.assertRaises(ValueError):
            evidence.sanitize_player_lifecycle(data, 1000, 1600)
        data["stages"] = [dict(data["stages"][0], id=stage) for stage in evidence.PLAYER_LIFECYCLE_STAGES]
        self.assertEqual(evidence.sanitize_player_lifecycle(data, 1000, 1600), data)

    def test_lifecycle_dns_policy_retains_only_fixed_reasons_and_saturated_counts(self):
        suffixes = [
            "dnsReason=invalid_host dnsAnswers=0 dnsRejected=0 dnsFamilies=none dnsKinds=none",
            "dnsReason=empty_answers dnsAnswers=0 dnsRejected=0 dnsFamilies=none dnsKinds=none",
            "dnsReason=nonpublic_answers dnsAnswers=2 dnsRejected=1 dnsFamilies=ipv4 dnsKinds=benchmark",
            "dnsReason=nonpublic_answers dnsAnswers=2 dnsRejected=2 dnsFamilies=ipv4+ipv6 dnsKinds=nat64+private",
            "dnsReason=nonpublic_answers dnsAnswers=255 dnsRejected=255 dnsFamilies=ipv4+ipv6+other dnsKinds=nat64+transition+private+local+multicast+benchmark+non_global",
        ]
        for suffix in suffixes:
            data = self.lifecycle_evidence()
            stage = data["stages"][1]
            stage["errorSummary"] = stage["errorSummary"].replace("category=other", "category=provider_dns_policy") + " " + suffix
            with self.subTest(suffix=suffix):
                self.assertEqual(evidence.sanitize_player_lifecycle(data, 1000, 1600), data)

    def test_lifecycle_dns_policy_rejects_unbounded_inconsistent_or_private_fields(self):
        valid = "dnsReason=nonpublic_answers dnsAnswers=2 dnsRejected=1 dnsFamilies=ipv4 dnsKinds=benchmark"
        invalid = [
            valid.replace("nonpublic_answers", "PRIVATE_SENTINEL"),
            valid.replace("dnsAnswers=2", "dnsAnswers=256"),
            valid.replace("dnsRejected=1", "dnsRejected=3"),
            valid.replace("dnsRejected=1", "dnsRejected=0"),
            valid.replace("ipv4", "ipv4+ipv4"),
            valid.replace("ipv4", "ipv6+ipv4"),
            valid.replace("ipv4", "none"),
            valid.replace("benchmark", "none"),
            valid.replace("benchmark", "private+nat64"),
            valid.replace("benchmark", "benchmark+benchmark"),
            valid.replace("benchmark", "https://PRIVATE_SENTINEL"),
            valid.replace("nonpublic_answers", "empty_answers"),
            valid + " dnsHost=PRIVATE_SENTINEL",
            "dnsReason=empty_answers",
        ]
        for suffix in invalid:
            data = self.lifecycle_evidence()
            stage = data["stages"][1]
            stage["errorSummary"] = stage["errorSummary"].replace("category=other", "category=provider_dns_policy") + " " + suffix
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                evidence.sanitize_player_lifecycle(data, 1000, 1600)
        data = self.lifecycle_evidence()
        data["stages"][1]["errorSummary"] += " " + valid
        with self.assertRaises(ValueError):
            evidence.sanitize_player_lifecycle(data, 1000, 1600)

    def test_lifecycle_stale_future_and_missing_invocation_times_are_rejected(self):
        for start, finish in ((1101, 1600), (1, 1099), (0, 1600), (1000, 0), (True, 1600)):
            with self.subTest(start=start, finish=finish), self.assertRaises(ValueError):
                evidence.sanitize_player_lifecycle(self.lifecycle_evidence(), start, finish)
        data = self.lifecycle_evidence()
        data["completedAtMs"] = 2000
        with self.assertRaises(ValueError):
            evidence.sanitize_player_lifecycle(data, 1000, 1600)

    def test_lifecycle_rejects_unknown_fields_secret_text_and_invalid_types(self):
        mutations = [
            (("url",), "https://private.invalid/PRIVATE_SENTINEL"),
            (("schemaVersion",), True), (("startedAtMs",), True), (("completedAtMs",), 1500.0),
            (("scenario",), "PRIVATE_SENTINEL"), (("outcome",), "PRIVATE_SENTINEL"),
            (("stages", 1, "message"), "PRIVATE_SENTINEL"),
            (("stages", 1, "id"), "PRIVATE_SENTINEL"), (("stages", 1, "result"), "PRIVATE_SENTINEL"),
            (("stages", 0, "readyForMs"), -1), (("stages", 0, "mediaMatches"), False),
            (("stages", 1, "playbackState"), True), (("stages", 1, "positionMs"), -1),
            (("stages", 1, "durationMs"), float("nan")), (("stages", 1, "paused"), 1),
            (("stages", 1, "errorCode"), True), (("stages", 1, "errorCode"), 2001),
            (("stages", 1, "errorSummary"), "category=PRIVATE_SENTINEL causes=java.lang.RuntimeException cycle=false truncated=false playerCode=1004"),
            (("stages", 1, "errorSummary"), "category=other causes=java.lang.RuntimeException: PRIVATE_SENTINEL cycle=false truncated=false playerCode=1004"),
            (("stages", 1, "errorSummary"), "category=other causes=https://private.invalid/PRIVATE_SENTINEL cycle=false truncated=false playerCode=1004"),
            (("stages", 1, "errorSummary"), "category=other causes=java.lang.RuntimeException cycle=false truncated=false playerCode=1004 Authorization=PRIVATE_SENTINEL"),
            (("stages", 1, "causeFrames", 0, "fileName"), "/private/PRIVATE_SENTINEL.java"),
            (("stages", 1, "causeFrames", 0, "className"), "private.PRIVATE_SENTINEL"),
            (("stages", 1, "causeFrames", 0, "methodName"), "https://private.invalid/PRIVATE_SENTINEL"),
            (("stages", 1, "causeFrames", 0, "lineNumber"), True),
            (("stages", 1, "causeFrames", 0, "causeDepth"), 8),
            (("stages", 1, "causeFrames", 0, "causeDepth"), 2),
            (("stages", 1, "causeFrames"), self.lifecycle_evidence()["stages"][1]["causeFrames"] * 17),
        ]
        for path, value in mutations:
            data = self.lifecycle_evidence()
            target = data
            for field in path[:-1]:
                target = target[field]
            target[path[-1]] = value
            with self.subTest(path=path, value=value), self.assertRaises(ValueError) as rejected:
                evidence.sanitize_player_lifecycle(data, 1000, 1600)
            state = evidence.capture_failure(rejected.exception)
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(state))
            self.assertEqual(state["reason"], "Invalid player lifecycle evidence")

    def test_lifecycle_read_is_fixed_bounded_and_rejects_symlinks_and_duplicate_keys(self):
        path = self.write_lifecycle_evidence(self.lifecycle_evidence())
        path.write_bytes(b" " * (evidence.PLAYER_LIFECYCLE_MAX_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "Missing or oversized player lifecycle evidence"):
            evidence.collect_player_lifecycle(1000, 1600)
        path.write_text('{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaisesRegex(ValueError, "Invalid player lifecycle evidence"):
            evidence.collect_player_lifecycle(1000, 1600)
        path.unlink()
        private = self.root / "private.json"
        private.write_text("PRIVATE_SENTINEL")
        path.symlink_to(private)
        files, state = evidence.collect_player_lifecycle(1000, 1600)
        self.assertFalse(files)
        self.assertEqual(state["status"], "missing-or-unreadable")
        path.unlink()
        path.parent.rmdir()
        path.parent.symlink_to(self.root, target_is_directory=True)
        files, state = evidence.collect_player_lifecycle(1000, 1600)
        self.assertFalse(files)
        self.assertEqual(state["status"], "missing-or-unreadable")

    def test_only_connected_suite_collects_lifecycle_and_missing_evidence_does_not_change_result(self):
        with patch.object(evidence, "collect_player_lifecycle", return_value=({}, {"status": "missing-or-unreadable"})) as collect:
            for invocation in ("connected-suite", "owned-library-journey"):
                command = list(evidence.COMMAND)
                if invocation != "connected-suite":
                    command += [evidence.RUNNER_ARGUMENT + "class=" + evidence.OWNED_JOURNEY]
                evidence.collect(self.root, 19, {"gradleStartedAtMs": 1000, "gradleFinishedAtMs": 1600}, {}, invocation, command)
                manifest = json.loads((self.root / Path(evidence.OUTPUT).parent / invocation / "collection-status.json").read_text())
                self.assertEqual(manifest["gradleExitCode"], 19)
                self.assertEqual("playerLifecycle" in manifest, invocation == "connected-suite")
            collect.assert_called_once_with(1000, 1600)

    def plugin_startup_evidence(self, test_name=None):
        return {"schemaVersion": 1, "scenario": "plugin-presentation-startup", "outcome": "failed",
                "testName": test_name or evidence.PLUGIN_STARTUP_TESTS[0], "startedAtMs": 1100,
                "completedAtMs": 1500, "elapsedMs": 400, "libraryReady": True, "socket": True,
                "connected": False, "eventTypes": ["other", "screen:changed"]}

    def write_plugin_startup_evidence(self, data):
        path = self.root / "device/cache/native-acceptance-diagnostics" / f"plugin-startup-{data['testName']}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))
        return path

    def test_plugin_startup_keeps_each_failure_in_hashed_archive_without_changing_gradle_result(self):
        originals = [self.plugin_startup_evidence(name) for name in evidence.PLUGIN_STARTUP_TESTS]
        originals[1]["eventTypes"] = []
        for data in originals:
            self.write_plugin_startup_evidence(data)
        private = self.root / "device/cache/native-acceptance-diagnostics/private.json"
        private.write_text("PRIVATE_SENTINEL")
        evidence.collect(self.root, 19, {"status": "app-process-not-observed",
                         "gradleStartedAtMs": 1000, "gradleFinishedAtMs": 1600}, {})
        with zipfile.ZipFile(self.root / evidence.OUTPUT / "evidence.zip") as saved:
            manifest = json.loads(saved.read("collection-status.json"))
            self.assertEqual(manifest["gradleExitCode"], 19)
            self.assertEqual(manifest["pluginStartup"]["snapshotCount"], 3)
            self.assertEqual(manifest["pluginStartup"]["status"], "captured")
            for original in originals:
                path = f"diagnostics/plugin-startup-{original['testName']}.json"
                raw = saved.read(path)
                self.assertEqual(json.loads(raw), original)
                self.assertEqual(manifest["fileSha256"][path], hashlib.sha256(raw).hexdigest())
            self.assertNotIn(b"PRIVATE_SENTINEL", b"".join(saved.read(name) for name in saved.namelist()))
        self.assertEqual(private.read_text(), "PRIVATE_SENTINEL")

    def test_plugin_startup_rejects_unknown_fields_private_event_types_and_invalid_types(self):
        mutations = [
            ("payload", {"token": "PRIVATE_SENTINEL"}), ("url", "https://private.invalid/PRIVATE_SENTINEL"),
            ("message", "PRIVATE_SENTINEL"), ("testName", "PRIVATE_SENTINEL"), ("schemaVersion", True),
            ("scenario", "PRIVATE_SENTINEL"), ("outcome", "passed"), ("startedAtMs", True),
            ("completedAtMs", 1500.0), ("completedAtMs", 1000), ("completedAtMs", 1601),
            ("elapsedMs", True), ("elapsedMs", -1), ("elapsedMs", float("nan")),
            ("elapsedMs", 9_007_199_254_740_992), ("libraryReady", 1), ("socket", "true"),
            ("connected", None), ("eventTypes", "screen:changed"), ("eventTypes", ["PRIVATE_SENTINEL"]),
            ("eventTypes", ["screen:changed", "screen:changed"]), ("eventTypes", ["screen:changed", "other"]),
            ("eventTypes", [{"type": "screen:changed", "payload": "PRIVATE_SENTINEL"}]),
            ("eventTypes", ["other"] * (len(evidence.PLUGIN_STARTUP_EVENT_TYPES) + 1)),
        ]
        for field, value in mutations:
            data = self.plugin_startup_evidence()
            data[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError) as rejected:
                evidence.sanitize_plugin_startup(data, evidence.PLUGIN_STARTUP_TESTS[0], 1000, 1600)
            state = evidence.capture_failure(rejected.exception)
            self.assertEqual(state["reason"], "Invalid plugin startup evidence")
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(state))

    def test_plugin_startup_rejects_stale_times_and_wrong_method_without_discarding_other_failures(self):
        for start, finish in ((1101, 1600), (1, 1099), (0, 1600), (1000, 0), (True, 1600)):
            with self.subTest(start=start, finish=finish), self.assertRaises(ValueError):
                evidence.sanitize_plugin_startup(self.plugin_startup_evidence(), evidence.PLUGIN_STARTUP_TESTS[0], start, finish)
        with self.assertRaises(ValueError):
            evidence.sanitize_plugin_startup(self.plugin_startup_evidence(), evidence.PLUGIN_STARTUP_TESTS[1], 1000, 1600)
        stale = self.plugin_startup_evidence()
        stale.update(startedAtMs=1, completedAtMs=2)
        self.write_plugin_startup_evidence(stale)
        fresh = self.plugin_startup_evidence(evidence.PLUGIN_STARTUP_TESTS[1])
        self.write_plugin_startup_evidence(fresh)
        files, state = evidence.collect_plugin_startup(1000, 1600)
        self.assertEqual(set(files), {f"diagnostics/plugin-startup-{fresh['testName']}.json"})
        self.assertEqual(state["snapshotCount"], 1)
        self.assertEqual(state["tests"][stale["testName"]]["reason"], "Stale plugin startup evidence")

    def test_plugin_startup_read_rejects_oversize_duplicate_keys_private_stdout_and_symlinks(self):
        original = self.plugin_startup_evidence()
        path = self.write_plugin_startup_evidence(original)
        for raw, reason in ((b" " * (evidence.PLUGIN_STARTUP_MAX_BYTES + 1), "Missing or oversized plugin startup evidence"),
                            (b'{"schemaVersion":1,"schemaVersion":1}', "Invalid plugin startup evidence"),
                            (b"run-as: PRIVATE_SENTINEL", "Invalid plugin startup evidence")):
            path.write_bytes(raw)
            files, state = evidence.collect_plugin_startup(1000, 1600)
            self.assertFalse(files)
            self.assertEqual(state["tests"][original["testName"]]["reason"], reason)
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(state))
        path.unlink()
        private = self.root / "private.json"
        private.write_text("PRIVATE_SENTINEL")
        path.symlink_to(private)
        files, state = evidence.collect_plugin_startup(1000, 1600)
        self.assertFalse(files)
        self.assertEqual(state["tests"][original["testName"]]["status"], "missing-or-unreadable")
        path.unlink()
        path.parent.rmdir()
        path.parent.symlink_to(self.root, target_is_directory=True)
        files, state = evidence.collect_plugin_startup(1000, 1600)
        self.assertFalse(files)
        self.assertEqual(state["tests"][original["testName"]]["status"], "missing-or-unreadable")

    def test_only_connected_suite_collects_plugin_startup_and_missing_diagnostics_preserve_result(self):
        with patch.object(evidence, "collect_plugin_startup", return_value=({}, {"status": "not-captured"})) as collect:
            for invocation in ("connected-suite", "owned-library-journey"):
                command = list(evidence.COMMAND)
                if invocation != "connected-suite":
                    command += [evidence.RUNNER_ARGUMENT + "class=" + evidence.OWNED_JOURNEY]
                evidence.collect(self.root, 19, {"status": "app-process-not-observed",
                                 "gradleStartedAtMs": 1000, "gradleFinishedAtMs": 1600}, {}, invocation, command)
                manifest = json.loads((self.root / Path(evidence.OUTPUT).parent / invocation / "collection-status.json").read_text())
                self.assertEqual(manifest["gradleExitCode"], 19)
                self.assertEqual("pluginStartup" in manifest, invocation == "connected-suite")
            collect.assert_called_once_with(1000, 1600)

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

    def test_canonical_journey_observations_keep_only_exact_owned_paths(self):
        original = self.journey_manifest()
        for prefix in ("/data/user/0/", "/data/data/"):
            data = json.loads(json.dumps(original).replace("/data/user/0/", prefix))
            directory = data["root"].split("/")[-1]
            data.update(rootCanonical=True, appFilesAliasObserved=prefix == "/data/data/",
                        explorerFilePath=data["mediaPath"], explorerIndexedPath=data["mediaPath"])
            clean = evidence.sanitize_journey_manifest(data, directory)
            self.assertTrue(clean["rootCanonical"])
            self.assertEqual(clean["explorerFilePath"], clean["mediaPath"])
            self.assertEqual(clean["explorerIndexedPath"], clean["mediaPath"])
            self.assertNotIn("/data/", json.dumps(clean))
            for key, bad in (("rootCanonical", 1), ("appFilesAliasObserved", "true"),
                             ("explorerFilePath", "/private/PRIVATE_SENTINEL"), ("explorerIndexedPath", [])):
                with self.subTest(prefix=prefix, key=key), self.assertRaises(ValueError):
                    evidence.sanitize_journey_manifest({**data, key: bad}, directory)

    def test_canonical_management_observations_bound_explorer_membership_targets(self):
        invocation = "owned-library-management"
        data = self.owned_manifest(invocation)
        directory = data["root"].split("/")[-1]
        targets = [data["root"] + "/library/Renamed owned copy.mp4", data["mediaPath"]]
        data.update(rootCanonical=True, appFilesAliasObserved=False, explorerOwnedPaths=targets)
        clean = evidence.sanitize_owned_manifest(data, directory, invocation)
        self.assertEqual(clean["explorerOwnedPaths"], ["library/Renamed owned copy.mp4", "library/Original generated video.mp4"])
        self.assertTrue(clean["rootCanonical"])
        self.assertFalse(clean["appFilesAliasObserved"])
        for key, bad in (("rootCanonical", "true"), ("appFilesAliasObserved", 0),
                         ("explorerOwnedPaths", targets + ["/private/PRIVATE_SENTINEL"]),
                         ("explorerOwnedPaths", [targets[0], targets[0]]), ("explorerOwnedPaths", [True, targets[1]])):
            with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                evidence.sanitize_owned_manifest({**data, key: bad}, directory, invocation)

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
                flags = [evidence.OWNED_FIXTURES[invocation]["flag"], "freshInstrumentationProcess"] if selected else []
                self.assertEqual(evidence.main(self.root, invocation, selected, flags), 0)
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
                flags = [evidence.OWNED_FIXTURES[invocation]["flag"], "freshInstrumentationProcess"] if selected else []
                self.assertEqual(evidence.main(self.root, invocation, selected, flags), 17)
            self.assertEqual(events, expected)

    def write_acceptance_fixture(self, *, skipped=0, tests=1, failures=0, errors=0, installed_status="matched", gradle_status=0,
                                 invocation="owned-library-journey"):
        spec = evidence.OWNED_FIXTURES[invocation]
        selected = spec["selector"]
        classname, method = selected.split("#")
        xml = (f'<testsuites><testsuite><testcase classname="{classname}" name="{method}">'
               + ("<skipped/>" if skipped else "") + "</testcase></testsuite></testsuites>").encode()
        digest = hashlib.sha256(b"fixture APK bytes").hexdigest()
        manifest = {
            "invocation": invocation, "gradleExitCode": gradle_status,
            "command": self.owned_command(invocation),
            "junit": {"status": "sanitized", "tests": tests, "failures": failures, "errors": errors,
                      "skipped": skipped, "rejectedReportsOrCases": 0},
            "installedApkIdentity": {"status": installed_status, "packages": {
                package: {"status": "matched", "matchesBuiltApk": True, "sha256": digest, "builtApk": relative}
                for package, relative in ((evidence.PACKAGE, evidence.APK_PATHS[1]), (evidence.TEST_PACKAGE, evidence.APK_PATHS[2]))}},
            "apkSha256": {path: digest for path in evidence.APK_PATHS},
            "fileSha256": {"junit-summary.xml": hashlib.sha256(xml).hexdigest()},
            spec["statusKey"]: {"status": "captured", "outcome": "passed"},
        }
        output = self.root / Path(evidence.OUTPUT).parent / invocation
        output.mkdir(parents=True, exist_ok=True)
        fixture = evidence.json_bytes({"outcome": "passed"})
        manifest["fileSha256"][f"fixtures/{invocation}.json"] = hashlib.sha256(fixture).hexdigest()
        status = evidence.json_bytes(manifest)
        (output / "collection-status.json").write_bytes(status)
        with zipfile.ZipFile(output / "evidence.zip", "w") as archive:
            archive.writestr("collection-status.json", status)
            archive.writestr("junit-summary.xml", xml)
            archive.writestr(f"fixtures/{invocation}.json", fixture)
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

    def owned_command(self, invocation):
        spec = evidence.OWNED_FIXTURES[invocation]
        return evidence.COMMAND + [evidence.RUNNER_ARGUMENT + "class=" + spec["selector"],
                                   evidence.RUNNER_ARGUMENT + spec["flag"] + "=true",
                                   evidence.RUNNER_ARGUMENT + "freshInstrumentationProcess=true"]

    def owned_manifest(self, invocation):
        directory = "native-go-fixture-12345678-1234-4123-8123-123456789abc"
        root = f"/data/user/0/{evidence.PACKAGE}/files/{directory}"
        data = {"kind": evidence.OWNED_FIXTURES[invocation]["kind"], "root": root, "dataDir": root + "/data",
                "cacheDir": root + "/cache", "libraryDir": root + "/library", "requiresColdRestart": True,
                "stage": "stopped-awaiting-force-stop-and-reviewed-cleanup", "outcome": "passed",
                "hostStatusAfterRun": "stopped", "failure": "PRIVATE_SENTINEL https://private.invalid",
                "retainedDataDir": "/private/PRIVATE_SENTINEL", "processId": 123,
                "recoveryPath": "/private/PRIVATE_SENTINEL", "headers": {"Authorization": "PRIVATE_SENTINEL"},
                "streamUrl": "https://private.invalid/?token=PRIVATE_SENTINEL", "generatedVideoProbe": {"filename": "PRIVATE_SENTINEL"}}
        if invocation == "owned-library-management":
            owned = [root + "/library/" + name for name in ("Owned editable copy.mp4", "Owned retained copy.mp4", "Renamed owned copy.mp4")]
            data.update(mediaPath=root + "/library/Original generated video.mp4", indexPath=root + "/library/Owned unmatched index.json",
                        ownedCopyPaths=owned, originalSha256="a" * 64, originalPreserved=True, retainedCopyPreserved=True,
                        recoveryAbsent=True, verified=["generated-owned-video-copies", "existing-index-import-api", "main-native-library-route",
                            "multi-file-ignore", "native-rename", "explorer-tree", "native-delete", "signed-go-index-readback"])
            data["indexReadbacks"] = [{"stage": stage, "files": [
                {"path": path, "name": path.rsplit("/", 1)[1], "mediaId": 0, "locked": False, "ignored": index > 1,
                 "parsedInfo": {"title": "PRIVATE_SENTINEL"}, "metadata": {"token": "PRIVATE_SENTINEL"}}
                for path in paths]} for index, (stage, paths) in enumerate((
                    ("fresh-empty-index", []), ("imported", owned[:2]), ("native-bulk-ignore", owned[:2]),
                    ("native-rename", owned[1:]), ("native-delete", owned[1:2])))]
        else:
            data.update(mediaPath=root + "/library/Generated owned raw video.mp4", rangeStatus=206, rangeBytes=256,
                        pausedPositionMs=10_123, renderedWidth=160, renderedHeight=90,
                        recoverySourceValidated=True, recoveryClearedByPlayer=True,
                        verified=["generated-h264", "signed-go-range-get", "library-root-boundary",
                                  "main-coordinator-media3-rendered-frame", "remote-play-pause-seek", "owned-recovery-write-and-dismissal"])
            if invocation == "owned-external-player":
                data.update(externalReceiverDifferentUid=True, externalAnonymousGoRanges=3, foregroundHostVerified=True,
                            externalReturnPaused=True, externalHostReleased=True)
        return data

    def test_owned_classification_requires_exact_selector_opt_in_and_fresh_process(self):
        for invocation, spec in evidence.OWNED_FIXTURES.items():
            command = self.owned_command(invocation)
            with self.subTest(invocation=invocation):
                self.assertEqual(evidence.owned_fixture_profile(invocation, command), spec)
                for bad in (command[:-1], command[:-2], command + [command[-1]], command + [command[-3]],
                            command + [evidence.RUNNER_ARGUMENT + "isolatedNativeGoLocalPlaybackFixture=true"],
                            [arg.replace(spec["selector"], spec["selector"].split("#")[0]) for arg in command],
                            [arg.replace("=true", "=false") for arg in command], None, [True]):
                    self.assertIsNone(evidence.owned_fixture_profile(invocation, bad))
                self.assertIsNone(evidence.owned_fixture_profile("other-invocation", command))
                with patch.object(evidence.subprocess, "run", side_effect=AssertionError("Must reject before commands")):
                    for flags in ([], [spec["flag"]], ["freshInstrumentationProcess"], [spec["flag"], "freshInstrumentationProcess"] * 2):
                        with self.assertRaises(ValueError):
                            evidence.main(self.root, invocation, spec["selector"], flags)
                    with self.assertRaises(ValueError):
                        evidence.main(self.root, "other-invocation", spec["selector"], [spec["flag"], "freshInstrumentationProcess"])

    def test_new_owned_manifests_capture_only_typed_owned_observations(self):
        for invocation in list(evidence.OWNED_FIXTURES)[1:]:
            manifest = self.owned_manifest(invocation)
            directory = manifest["root"].rsplit("/", 1)[1]
            path = self.root / "device/files" / directory / "fixture.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(manifest))
            with self.subTest(invocation=invocation):
                files, state = evidence.collect_owned_manifest(invocation)
                self.assertEqual(state, {"status": "captured", "fixtureRoot": directory, "outcome": "passed"})
                raw = files[f"fixtures/{invocation}.json"]
                clean = json.loads(raw)
                self.assertEqual(clean["kind"], manifest["kind"])
                self.assertEqual(clean["root"], directory)
                self.assertEqual(clean["mediaPath"], manifest["mediaPath"].removeprefix(manifest["root"] + "/"))
                for excluded in (b"PRIVATE_SENTINEL", b"https://", b"/data/user/", b"/private/", b"Authorization", b"generatedVideoProbe", b"processId"):
                    self.assertNotIn(excluded, raw)
                if invocation == "owned-library-management":
                    self.assertEqual(len(clean["indexReadbacks"]), 5)
                    self.assertEqual(clean["indexReadbacks"][-1]["files"][0]["path"], "library/Owned retained copy.mp4")
                    self.assertNotIn("metadata", clean["indexReadbacks"][-1]["files"][0])
                elif invocation == "owned-external-player":
                    self.assertEqual(clean["externalAnonymousGoRanges"], 3)
                    self.assertTrue(clean["externalReturnPaused"])

    def external_resolution(self):
        return {"sdk": 36, "scheme": "http", "mimeType": "video/*", "candidateCount": 1,
                "targetContextOwnsCallerUid": True, "testContextOwnsCallerUid": False,
                "targetAndTestPackagesDiffer": True, "componentEnabledSetting": 0, "applicationEnabledSetting": 0,
                "receiver": {"enabled": True, "exported": True, "applicationEnabled": True,
                             "receiverOwnsCallerUid": False, "receiverOwnsTestUid": True,
                             "testOnly": False, "stopped": True},
                "scopedWildcardResolves": False, "concreteVideoResolves": False,
                "scopedConcreteVideoResolves": False, "declaredFilterMatches": True}

    def test_external_resolution_failure_facts_survive_owned_fixture_capture(self):
        invocation = "owned-external-player"
        manifest = self.owned_manifest(invocation)
        manifest.update(outcome="failed", failedAt="signed-go-native-frame-pause-seek-verified",
                        externalResolution=self.external_resolution())
        directory = manifest["root"].rsplit("/", 1)[1]
        path = self.root / "device/files" / directory / "fixture.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest))
        files, state = evidence.collect_owned_manifest(invocation)
        self.assertEqual(state["outcome"], "failed")
        raw = files[f"fixtures/{invocation}.json"]
        clean = json.loads(raw)
        self.assertEqual(clean["externalResolution"], manifest["externalResolution"])
        self.assertEqual(clean["failedAt"], manifest["failedAt"])
        for excluded in (b"PRIVATE_SENTINEL", b"https://", b"/data/user/", b"Authorization", b"streamUrl", b"processId"):
            self.assertNotIn(excluded, raw)

    def test_external_resolution_retains_only_normalized_probe_unavailability(self):
        data = self.external_resolution()
        for key in ("receiver", "componentEnabledSetting", "applicationEnabledSetting", "scopedWildcardResolves",
                    "concreteVideoResolves", "scopedConcreteVideoResolves", "declaredFilterMatches"):
            data[key] = "unavailable"
        self.assertEqual(evidence.sanitize_external_resolution(data), data)

    def test_external_resolution_rejects_unknown_fields_values_and_type_confusion(self):
        base = self.external_resolution()
        private = "https://private.invalid/video?token=PRIVATE_SENTINEL"
        header = "Authorization: PRIVATE_SENTINEL"
        cases = [(key, value) for key in ("sdk", "candidateCount")
                 for value in (True, "36", -1, 10_000, float("nan"), float("inf"), None)]
        cases += [("sdk", 22), ("candidateCount", 4097)]
        cases += [(key, value) for key in ("scheme", "mimeType")
                  for value in (private, header, [], {}, True, None)]
        cases += [(key, value) for key in ("targetContextOwnsCallerUid", "testContextOwnsCallerUid", "targetAndTestPackagesDiffer")
                  for value in (1, 0, "true", "unavailable", private, header, None)]
        cases += [(key, value) for key in ("scopedWildcardResolves", "concreteVideoResolves",
                                         "scopedConcreteVideoResolves", "declaredFilterMatches")
                  for value in (1, 0, "false", "NameNotFoundException", private, header, {}, None)]
        cases += [(key, value) for key in ("componentEnabledSetting", "applicationEnabledSetting")
                  for value in (True, False, -1, 5, 0.0, "0", "SecurityException", private, header, None)]
        cases += [("receiver", value) for value in ([], {}, True, private, header, "NameNotFoundException", None)]
        for field, value in cases:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                evidence.sanitize_external_resolution({**base, field: value})
        for field in base:
            data = dict(base)
            del data[field]
            with self.subTest(missing=field), self.assertRaises(ValueError):
                evidence.sanitize_external_resolution(data)
        for field in ("source", "url", "headers", "component", "exception"):
            for nested in (False, True):
                data = copy.deepcopy(base)
                (data["receiver"] if nested else data)[field] = private
                with self.subTest(extra=field, nested=nested), self.assertRaises(ValueError):
                    evidence.sanitize_external_resolution(data)
        for field in base["receiver"]:
            for value in (1, "true", "unavailable", private, header, {}, None):
                data = copy.deepcopy(base)
                data["receiver"][field] = value
                with self.subTest(receiver=field, value=value), self.assertRaises(ValueError):
                    evidence.sanitize_external_resolution(data)

    def test_external_resolution_is_limited_to_failed_external_fixture(self):
        for invocation in evidence.OWNED_FIXTURES:
            for outcome in ("passed", "running", "failed"):
                if invocation == "owned-external-player" and outcome == "failed":
                    continue
                manifest = self.owned_manifest(invocation)
                manifest.update(outcome=outcome, externalResolution=self.external_resolution())
                directory = manifest["root"].rsplit("/", 1)[1]
                with self.subTest(invocation=invocation, outcome=outcome), self.assertRaises(ValueError):
                    evidence.sanitize_owned_manifest(manifest, directory, invocation)

    def test_new_owned_manifests_reject_path_schema_type_and_enum_confusion(self):
        for invocation in list(evidence.OWNED_FIXTURES)[1:]:
            base = self.owned_manifest(invocation)
            directory = base["root"].rsplit("/", 1)[1]
            cases = [("kind", "other-kind"), ("root", "/data/user/0/app.seanime.tv/files/../private"),
                     ("mediaPath", base["root"] + "/library/../private"), ("requiresColdRestart", 1),
                     ("stage", "PRIVATE_SENTINEL"), ("stage", {}), ("outcome", []),
                     ("hostStatusAfterRun", ["stopped"]), ("failedAt", "PRIVATE_SENTINEL"),
                     ("verified", ["PRIVATE_SENTINEL"]), ("verified", [base["verified"][0]] * 2), ("verified", [True])]
            if invocation == "owned-library-management":
                cases += [("ownedCopyPaths", [base["ownedCopyPaths"][0]] * 3), ("ownedCopyPaths", [None] * 3),
                          ("originalSha256", "a" * 65), ("originalSha256", True), ("originalPreserved", 1),
                          ("indexReadbacks", base["indexReadbacks"] * 2), ("indexReadbacks", [{"stage": "imported", "files": []}])]
            else:
                cases += [(key, value) for key in ("rangeStatus", "rangeBytes", "pausedPositionMs", "renderedWidth", "renderedHeight")
                          for value in (True, "160", float("nan"), float("inf"), -1, 100_000_001)]
                cases += [("recoverySourceValidated", 1)]
                if invocation == "owned-external-player":
                    cases += [("externalAnonymousGoRanges", 2), ("externalReturnPaused", "true")]
            for field, value in cases:
                with self.subTest(invocation=invocation, field=field, value=value), self.assertRaises(ValueError):
                    evidence.sanitize_owned_manifest({**base, field: value}, directory, invocation)
            for invalid in ("native-go-fixture-12345678-1234-1123-8123-123456789abc", directory.upper(), directory + "/../private", "native-go-fixture-invalid"):
                with self.subTest(directory=invalid), self.assertRaises(ValueError):
                    evidence.sanitize_owned_manifest(base, invalid, invocation)

    def test_management_index_rows_reject_foreign_paths_types_duplicates_and_oversize(self):
        invocation = "owned-library-management"
        base = self.owned_manifest(invocation)
        directory = base["root"].rsplit("/", 1)[1]
        for field, value in (("path", "/private/PRIVATE_SENTINEL"), ("path", base["mediaPath"]), ("name", "PRIVATE_SENTINEL"),
                             ("mediaId", True), ("mediaId", 1), ("mediaId", float("nan")), ("ignored", 1), ("locked", "false")):
            data = copy.deepcopy(base)
            data["indexReadbacks"][1]["files"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                evidence.sanitize_owned_manifest(data, directory, invocation)
        for count in (2, 3):
            data = copy.deepcopy(base)
            data["indexReadbacks"][1]["files"] = [data["indexReadbacks"][1]["files"][0]] * count
            with self.subTest(count=count), self.assertRaises(ValueError):
                evidence.sanitize_owned_manifest(data, directory, invocation)

    def test_new_owned_capture_rejects_oversize_symlinks_ambiguous_roots_and_wrong_kind(self):
        directory = "native-go-fixture-12345678-1234-4123-8123-123456789abc"
        fixture = self.root / "device/files" / directory / "fixture.json"
        fixture.parent.mkdir(parents=True)
        other = fixture.parent.parent / "native-go-fixture-87654321-1234-4123-8123-123456789abc/fixture.json"
        private = self.root / "private.json"
        private.write_text("PRIVATE_SENTINEL")
        for invocation in list(evidence.OWNED_FIXTURES)[1:]:
            with self.subTest(invocation=invocation):
                fixture.write_bytes(b" " * 131073)
                with self.assertRaisesRegex(ValueError, "Missing or oversized owned fixture manifest"):
                    evidence.collect_owned_manifest(invocation)
                fixture.unlink()
                fixture.symlink_to(private)
                self.assertEqual(evidence.collect_owned_manifest(invocation), ({}, {"status": "missing-or-unreadable"}))
                fixture.unlink()
                fixture.write_text(json.dumps(self.owned_manifest(invocation)))
                other.parent.mkdir()
                other.write_text("{}")
                self.assertEqual(evidence.collect_owned_manifest(invocation)[1]["status"], "ambiguous-owned-roots")
                other.unlink()
                other.parent.rmdir()
                data = self.owned_manifest(invocation)
                data["kind"] = evidence.JOURNEY_KIND
                fixture.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, "Invalid fixture schema or path relationships"):
                    evidence.collect_owned_manifest(invocation)

    def rewrite_acceptance(self, invocation, change, fixture_change=None):
        output = self.root / Path(evidence.OUTPUT).parent / invocation
        with zipfile.ZipFile(output / "evidence.zip") as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
        manifest = json.loads(files["collection-status.json"])
        change(manifest)
        if fixture_change:
            fixture_path = f"fixtures/{invocation}.json"
            fixture = json.loads(files[fixture_path])
            fixture_change(fixture)
            files[fixture_path] = evidence.json_bytes(fixture)
            manifest["fileSha256"][fixture_path] = hashlib.sha256(files[fixture_path]).hexdigest()
        files["collection-status.json"] = evidence.json_bytes(manifest)
        (output / "collection-status.json").write_bytes(files["collection-status.json"])
        with zipfile.ZipFile(output / "evidence.zip", "w") as archive:
            for name, raw in files.items():
                archive.writestr(name, raw)

    def test_each_owned_acceptance_rejects_skips_wrong_flags_missing_manifest_or_failed_fixture(self):
        with patch.object(evidence.subprocess, "run", side_effect=AssertionError("Acceptance must be read-only")):
            for invocation, spec in evidence.OWNED_FIXTURES.items():
                with self.subTest(invocation=invocation):
                    _, selected = self.write_acceptance_fixture(invocation=invocation)
                    self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 0)
                    for invalid in ({"skipped": 1}, {"tests": 0}, {"tests": 2}, {"failures": 1}, {"errors": 1},
                                    {"installed_status": "mismatch"}, {"gradle_status": 23}):
                        self.write_acceptance_fixture(invocation=invocation, **invalid)
                        self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 1)
                    for change in (lambda value: value["command"].pop(),
                                   lambda value: value["command"].append(value["command"][-1]),
                                   lambda value: value.pop(spec["statusKey"]),
                                   lambda value: value["fileSha256"].update({f"fixtures/{invocation}.json": "0" * 64})):
                        self.write_acceptance_fixture(invocation=invocation)
                        self.rewrite_acceptance(invocation, change)
                        self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 1)
                    self.write_acceptance_fixture(invocation=invocation)
                    self.rewrite_acceptance(invocation, lambda value: None, lambda fixture: fixture.update(outcome="failed"))
                    self.assertEqual(evidence.check_acceptance(self.root, invocation, selected), 1)

    def test_new_owned_collect_and_postcapture_force_stop_preserve_original_exit_status(self):
        gradle = self.root / "androidtv/gradlew"
        gradle.write_text("#!/bin/sh\nexit 17\n")
        gradle.chmod(0o755)
        original_run = evidence.subprocess.run
        for invocation, spec in list(evidence.OWNED_FIXTURES.items())[1:]:
            for fails in (False, True):
                events = []
                def collect(*args, **kwargs):
                    events.append("collect")
                    if fails:
                        raise OSError("PRIVATE_SENTINEL")
                def run(command, **kwargs):
                    if command[3:] == ["shell", "am", "force-stop", evidence.PACKAGE]:
                        events.append("force-stop")
                        return subprocess.CompletedProcess(command, 1)
                    return original_run(command, **kwargs)
                with self.subTest(invocation=invocation, fails=fails), patch.object(evidence, "collect", side_effect=collect), patch.object(
                        evidence.subprocess, "run", side_effect=run), patch.object(evidence.threading, "Thread") as thread:
                    thread.return_value.is_alive.return_value = False
                    self.assertEqual(evidence.main(self.root, invocation, spec["selector"], [spec["flag"], "freshInstrumentationProcess"]), 17)
                    self.assertEqual(events, ["collect", "force-stop"])
                    profile = thread.call_args.kwargs["args"][3]
                    self.assertEqual((profile["maxSeconds"], profile["bitRateBitsPerSecond"], profile["maxBytes"]), (60, 2_000_000, 32 * 1024 * 1024))
                    self.assertIn("bounded excerpt only", profile["coverage"])

    def test_new_owned_collect_saves_exact_manifest_and_does_not_touch_other_invocations(self):
        for relative in evidence.APK_PATHS:
            apk = self.root / relative
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.write_bytes(b"fixture APK bytes")
        for invocation, spec in list(evidence.OWNED_FIXTURES.items())[1:]:
            data = self.owned_manifest(invocation)
            fixture = self.root / "device/files" / data["root"].rsplit("/", 1)[1] / "fixture.json"
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(json.dumps(data))
            with self.subTest(invocation=invocation):
                evidence.collect(self.root, 0, {"status": "app-process-not-observed", "installedApks": evidence.capture_installed_apks()},
                                 {}, invocation, self.owned_command(invocation))
                output = self.root / Path(evidence.OUTPUT).parent / invocation
                with zipfile.ZipFile(output / "evidence.zip") as archive:
                    saved = json.loads(archive.read("collection-status.json"))
                    self.assertEqual(saved[spec["statusKey"]]["status"], "captured")
                    self.assertEqual(saved["installedApkIdentity"]["status"], "matched")
                    self.assertEqual({name for name in archive.namelist() if name.startswith("fixtures/")}, {f"fixtures/{invocation}.json"})
                    self.assertNotIn(b"PRIVATE_SENTINEL", b"".join(archive.read(name) for name in archive.namelist()))
        for invocation in list(evidence.OWNED_FIXTURES)[1:]:
            self.assertTrue((self.root / Path(evidence.OUTPUT).parent / invocation / "evidence.zip").is_file())

    def test_literal_capture_names_are_in_allowlist(self):
        sources = Path(__file__).resolve().parent.parent / "androidtv/app/src/androidTest"
        for source in sources.rglob("*.kt"):
            names = re.findall(r'NativeScreenshotEvidence\.capture\("([a-z0-9_-]+)"\)', source.read_text())
            self.assertFalse(set(names) - evidence.SCENARIOS, source)


if __name__ == "__main__":
    unittest.main()
