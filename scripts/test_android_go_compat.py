#!/usr/bin/env python3
"""Regression tests for the narrowly pinned Android/x86_64 libc overlay.

Run with a Go toolchain and modernc.org/libc@v1.41.0 already downloaded:
    python3 -m unittest discover -s scripts -p 'test_android_go_compat.py' -v

The optional native harness requires Linux/amd64 and cached x/sys@v0.47.0.
It exercises a temporary copy of the helper with only its GOOS guard changed,
never changes the module cache, and never runs alarm or changes rlimits.
"""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("prepare-android-go-compat.py")
PINNED_SHA256 = "bf17852be812991fc8eb227f7e53a6ad24eba5e3baa61a466770efbf13f9c886"
SOURCE_NAME = "libc_linux_amd64.go"
GO = shutil.which("go")


def load_compat():
    spec = importlib.util.spec_from_file_location("seanime_android_go_compat", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def module_cache():
    if os.environ.get("GOMODCACHE"):
        return Path(os.environ["GOMODCACHE"])
    if GO:
        result = subprocess.run([GO, "env", "GOMODCACHE"], capture_output=True, text=True, check=True)
        return Path(result.stdout.strip())
    if os.environ.get("GOPATH"):
        return Path(os.environ["GOPATH"].split(os.pathsep)[0]) / "pkg" / "mod"
    return Path.home() / "go" / "pkg" / "mod"


def pinned_source():
    override = os.environ.get("SEANIME_ANDROID_LIBC_SOURCE")
    path = Path(override) if override else module_cache() / "modernc.org" / "libc@v1.41.0" / SOURCE_NAME
    if not path.is_file():
        raise unittest.SkipTest("download modernc.org/libc@v1.41.0 or set SEANIME_ANDROID_LIBC_SOURCE")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != PINNED_SHA256:
        raise AssertionError("test fixture is not the independently pinned modernc libc v1.41.0 source")
    return data


def tree_snapshot(root):
    """Include metadata as well as bytes, detecting even same-content rewrites."""
    result = {}
    for path in sorted(root.rglob("*")):
        info = path.lstat()
        result[str(path.relative_to(root))] = (
            info.st_mode, info.st_size, info.st_mtime_ns,
            path.read_bytes() if path.is_file() else None,
        )
    return result


class SourcePatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compat = load_compat()

    def test_independently_pinned_version_and_digest(self):
        self.assertEqual("v1.41.0", self.compat.MODULE_VERSION)
        self.assertEqual(PINNED_SHA256, self.compat.PINNED_SOURCE_SHA256)

    def test_rejects_empty_unrelated_and_mutated_sources(self):
        for source in (b"", b"package libc\n", b"unix.Syscall(1, 2, 3, 4)", bytes(18614)):
            with self.subTest(source_length=len(source)):
                with self.assertRaises(ValueError):
                    self.compat.patch_source(source)

    def test_exact_rewrite_leaves_all_other_bytes_and_syscall6_intact(self):
        source = pinned_source()
        expected = source.replace(b"unix.Syscall(", b"seanimeAndroidSyscall(")
        self.assertGreater(source.count(b"unix.Syscall("), 20)
        self.assertEqual(expected, self.compat.patch_source(source))
        self.assertEqual(source.count(b"unix.Syscall6("), expected.count(b"unix.Syscall6("))
        self.assertNotIn(b"unix.Syscall(", expected)
        self.assertEqual(expected, self.compat.patch_source(source))

    def test_rejects_one_byte_drift_and_already_patched_source(self):
        source = pinned_source()
        for changed in (source + b"\n", source[1:], b"!" + source[1:], self.compat.patch_source(source)):
            with self.subTest(digest=hashlib.sha256(changed).hexdigest()):
                with self.assertRaises(ValueError):
                    self.compat.patch_source(changed)

    def test_helper_has_android_guard_and_preserves_host_passthrough(self):
        helper = self.compat.ANDROID_SYSCALL_HELPER
        self.assertIsInstance(helper, str)
        self.assertRegex(helper, r'runtime\.GOOS\s*!=\s*"android"')
        self.assertRegex(helper, r'return\s+unix\.Syscall\(')
        self.assertIn("func seanimeAndroidSyscall(", helper)
        # These LP64 calls are allowed and must remain in the default path.
        self.assertNotRegex(helper, r"case[^:]*unix\.SYS_(?:GETRLIMIT|SETRLIMIT)[^:]*:")

    def test_helper_marks_uintptr_arguments_as_escaping(self):
        self.assertRegex(
            self.compat.ANDROID_SYSCALL_HELPER,
            r"//go:uintptrescapes\s+func seanimeAndroidSyscall\(",
        )

    def test_helper_covers_blocked_legacy_calls_and_modern_equivalents(self):
        helper = self.compat.ANDROID_SYSCALL_HELPER
        mappings = {
            "STAT": "NEWFSTATAT", "LSTAT": "NEWFSTATAT", "OPEN": "OPENAT",
            "READLINK": "READLINKAT", "ACCESS": "FACCESSAT", "UNLINK": "UNLINKAT",
            "RMDIR": "UNLINKAT", "MKDIR": "MKDIRAT", "RENAME": "RENAMEAT",
            "SYMLINK": "SYMLINKAT", "CHMOD": "FCHMODAT", "CHOWN": "FCHOWNAT",
            "LINK": "LINKAT", "MKNOD": "MKNODAT", "PIPE": "PIPE2", "DUP2": "DUP3",
            "UTIME": "UTIMENSAT", "UTIMES": "UTIMENSAT", "ALARM": "SETITIMER",
            "TIME": "CLOCK_GETTIME",
        }
        for legacy, modern in mappings.items():
            with self.subTest(legacy=legacy, modern=modern):
                self.assertRegex(helper, rf"case[^:]*unix\.SYS_{legacy}\b[^:]*:")
                self.assertRegex(helper, rf"\bunix\.SYS_{modern}\b")


class OverlayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compat = load_compat()
        cls.source = pinned_source()

    def make_module(self, root):
        module = root / "module-cache" / "modernc.org" / "libc@v1.41.0"
        module.mkdir(parents=True)
        (module / SOURCE_NAME).write_bytes(self.source)
        (module / "libc_linux_arm64.go").write_text("package libc\n// must stay untouched\n")
        (module / "go.mod").write_text("module modernc.org/libc\n\ngo 1.21\n")
        (module / "unrelated.bin").write_bytes(b"\0\xffdo not modify")
        (module / "nested").mkdir()
        (module / "nested" / "source.go").write_text("package nested\n// recursively staged without edits\n")
        return module

    def test_writes_only_two_overlay_entries_without_touching_module(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            for path in module.rglob("*"):
                if path.is_file():
                    path.chmod(0o444)
            before = tree_snapshot(module.parent.parent)
            output = root / "output"
            overlay = self.compat.prepare_overlay(module, output)
            self.assertIsInstance(overlay, Path)
            self.assertEqual(before, tree_snapshot(module.parent.parent))
            self.assertTrue(overlay.is_file())
            document = json.loads(overlay.read_text())
            self.assertEqual({"Replace"}, set(document))
            replacements = document["Replace"]
            self.assertEqual(2, len(replacements), "exactly one replacement and one helper may be overlaid")
            stage = output / "staged-libc"
            self.assertTrue(stage.is_dir())
            original_files = {str(p.relative_to(module)): p.read_bytes() for p in module.rglob("*") if p.is_file()}
            staged_files = {str(p.relative_to(stage)): p.read_bytes() for p in stage.rglob("*") if p.is_file()}
            expected_files = dict(original_files)
            expected_files[SOURCE_NAME] = self.compat.patch_source(self.source)
            expected_files["seanime_android_compat_linux_amd64.go"] = self.compat.ANDROID_SYSCALL_HELPER.encode()
            self.assertEqual(set(expected_files), set(staged_files))
            for relative, expected in expected_files.items():
                self.assertEqual(expected, staged_files[relative], f"unexpected staged modification: {relative}")
            self.assertFalse(any(path.is_symlink() for path in stage.rglob("*")), "staged sources must be real files")
            source_key = str((stage / SOURCE_NAME).resolve())
            self.assertIn(source_key, replacements)
            self.assertEqual(self.compat.patch_source(self.source), Path(replacements[source_key]).read_bytes())
            helper_keys = set(replacements) - {source_key}
            helper_key = Path(helper_keys.pop())
            self.assertEqual(stage.resolve(), helper_key.parent)
            self.assertEqual(".go", helper_key.suffix)
            self.assertTrue(helper_key.is_file(), "generated stage must build without an overlay flag")
            self.assertFalse((module / helper_key.name).exists(), "helper must never be added to the original module cache")
            self.assertEqual(self.compat.ANDROID_SYSCALL_HELPER, helper_key.read_text())
            self.assertEqual(self.compat.ANDROID_SYSCALL_HELPER, Path(replacements[str(helper_key)]).read_text())
            for original, replacement in replacements.items():
                self.assertTrue(Path(original).is_absolute())
                self.assertTrue(Path(original).is_relative_to(stage.resolve()))
                self.assertFalse(Path(original).is_relative_to(module.parent.parent.resolve()))
                self.assertTrue(Path(replacement).is_absolute())
                self.assertTrue(Path(replacement).is_relative_to(output.resolve()))
            self.assertTrue(overlay.resolve().is_relative_to(output.resolve()))

    def test_output_cannot_write_inside_module_or_module_cache(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            cache = module.parent.parent
            for output in (module, module / "generated", cache, cache / "generated"):
                before = tree_snapshot(cache)
                with self.subTest(output=str(output.relative_to(root))):
                    with self.assertRaises(ValueError):
                        self.compat.prepare_overlay(module, output)
                    self.assertEqual(before, tree_snapshot(cache))

    def test_existing_helper_is_not_silently_shadowed(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            (module / "seanime_android_compat_linux_amd64.go").write_text("package libc\n// preexisting file\n")
            before = tree_snapshot(module)
            with self.assertRaises(ValueError):
                self.compat.prepare_overlay(module, root / "output")
            self.assertEqual(before, tree_snapshot(module))
            self.assertFalse((root / "output").exists())

    def test_refuses_symlinked_module_sources(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            target = root / "outside.txt"
            target.write_text("must not be copied through a link")
            (module / "unexpected-link").symlink_to(target)
            before = tree_snapshot(module)
            with self.assertRaises(ValueError):
                self.compat.prepare_overlay(module, root / "output")
            self.assertEqual(before, tree_snapshot(module))
            self.assertEqual("must not be copied through a link", target.read_text())

    def test_refuses_unexpected_preexisting_stage_files(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            output = root / "output"
            stage = output / "staged-libc"
            stage.mkdir(parents=True)
            (stage / "unexpected.go").write_text("package libc\n// stale source\n")
            before = tree_snapshot(module)
            with self.assertRaises(ValueError):
                self.compat.prepare_overlay(module, output)
            self.assertEqual(before, tree_snapshot(module))
            self.assertFalse((output / "overlay.json").exists())

    def test_manifest_hashes_match_the_written_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            output = root / "output"
            self.compat.prepare_overlay(module, output)
            manifest = json.loads((output / "compatibility.json").read_text())
            self.assertEqual("modernc.org/libc", manifest["module"])
            self.assertEqual("v1.41.0", manifest["version"])
            self.assertEqual(PINNED_SHA256, manifest["source_sha256"])
            self.assertEqual(str(output / "staged-libc"), manifest["staged_module"])
            self.assertEqual(str(output / "overlay.json"), manifest["overlay"])
            self.assertEqual(hashlib.sha256((output / SOURCE_NAME).read_bytes()).hexdigest(), manifest["patched_source_sha256"])
            self.assertEqual(hashlib.sha256(self.compat.ANDROID_SYSCALL_HELPER.encode()).hexdigest(), manifest["helper_sha256"])
            self.assertEqual(self.compat.SYSCALL_MAPPING, manifest["mapping"])
            self.assertEqual(sorted(self.compat.PASSTHROUGH_SYSCALLS), manifest["unchanged_syscalls"])
            self.assertEqual({"cwd": str(output / "driver"), "GOWORK": "off", "package": "seanime/mobile", "overlay_flag_required": False}, manifest["bind_contract"])

    def test_repeated_preparation_is_byte_for_byte_deterministic(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            output = root / "output"
            first = self.compat.prepare_overlay(module, output)
            first_contents = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob("*") if p.is_file()}
            first_snapshot = tree_snapshot(output)
            second = self.compat.prepare_overlay(module, output)
            self.assertEqual(first, second)
            self.assertEqual(first_snapshot, tree_snapshot(output), "unchanged output must not even be rewritten")
            self.assertEqual(first_contents, {str(p.relative_to(output)): p.read_bytes() for p in output.rglob("*") if p.is_file()})

    def test_missing_and_drifted_source_fail_without_changing_module(self):
        with tempfile.TemporaryDirectory(prefix="seanime-overlay-test-") as temporary:
            root = Path(temporary)
            module = self.make_module(root)
            source = module / SOURCE_NAME
            for invalid in (b"package libc\n", None):
                if invalid is None:
                    source.unlink()
                else:
                    source.write_bytes(invalid)
                before = tree_snapshot(module)
                with self.subTest(missing=invalid is None):
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        self.compat.prepare_overlay(module, root / "output")
                    self.assertEqual(before, tree_snapshot(module))
                    self.assertFalse((root / "output").exists(), "invalid sources must fail before output is created")


class DriverTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compat = load_compat()

    def make_repo(self, root):
        repo = root / "repo with spaces"
        repo.mkdir()
        manifest = (
            "module seanime\n\ngo 1.25.0\n\n"
            "require (\n\tmodernc.org/libc v1.41.0\n\texample.org/dependency v1.2.3\n)\n\n"
            "tool golang.org/x/mobile/cmd/gomobile\n\n"
            "replace github.com/anacrolix/torrent v1.54.1 => example.org/torrent v1.54.2\n"
        )
        (repo / "go.mod").write_text(manifest)
        (repo / "go.sum").write_bytes(b"retained-checksum-lines\n\x00test-bytes\n")
        (repo / "untouched.txt").write_text("repository contents stay unchanged")
        return repo

    def test_driver_preserves_manifests_and_adds_only_intended_module_bindings(self):
        with tempfile.TemporaryDirectory(prefix="seanime-driver-test-") as temporary:
            root = Path(temporary)
            repo = self.make_repo(root)
            before = tree_snapshot(repo)
            output = root / "output with spaces"
            metadata = {"Replace": [{"Old": {"Path": "github.com/anacrolix/torrent", "Version": "v1.54.1"}, "New": {"Path": "example.org/torrent", "Version": "v1.54.2"}}]}
            with mock.patch.object(self.compat.subprocess, "check_output", return_value=json.dumps(metadata)) as command:
                driver = self.compat.prepare_driver(repo, output)
            self.assertEqual(1, command.call_count)
            self.assertEqual((["go", "mod", "edit", "-json"],), command.call_args.args)
            self.assertEqual(repo.resolve(), command.call_args.kwargs["cwd"])
            self.assertEqual("off", command.call_args.kwargs["env"]["GOWORK"])
            self.assertTrue(command.call_args.kwargs["text"])
            self.assertEqual(output / "driver", driver)
            original = (repo / "go.mod").read_text()
            generated = (driver / "go.mod").read_text()
            generated_header, _, generated_body = generated.partition("\n")
            self.assertEqual("module seanime.android.build", generated_header)
            self.assertTrue(generated_body.lstrip("\n").startswith(original.partition("\n")[2].lstrip("\n")))
            self.assertIn("require seanime v0.0.0\n", generated)
            self.assertIn("replace seanime => " + json.dumps(str(repo)) + "\n", generated)
            self.assertIn("replace modernc.org/libc => " + json.dumps(str(output / "staged-libc")) + "\n", generated)
            self.assertEqual((repo / "go.sum").read_bytes(), (driver / "go.sum").read_bytes())
            self.assertEqual(before, tree_snapshot(repo))
            first = tree_snapshot(driver)
            with mock.patch.object(self.compat.subprocess, "check_output", return_value=json.dumps(metadata)):
                self.assertEqual(driver, self.compat.prepare_driver(repo, output))
            self.assertEqual(first, tree_snapshot(driver))
            self.assertEqual(before, tree_snapshot(repo))

    def test_relative_local_replacement_fails_before_writing_driver(self):
        with tempfile.TemporaryDirectory(prefix="seanime-driver-test-") as temporary:
            root = Path(temporary)
            repo = self.make_repo(root)
            before = tree_snapshot(repo)
            metadata = {"Replace": [{"Old": {"Path": "example.org/local"}, "New": {"Path": "../local"}}]}
            with mock.patch.object(self.compat.subprocess, "check_output", return_value=json.dumps(metadata)):
                with self.assertRaises(ValueError):
                    self.compat.prepare_driver(repo, root / "output")
            self.assertEqual(before, tree_snapshot(repo))
            self.assertFalse((root / "output").exists())

    def test_absolute_local_replacement_remains_valid(self):
        with tempfile.TemporaryDirectory(prefix="seanime-driver-test-") as temporary:
            root = Path(temporary)
            repo = self.make_repo(root)
            local = str(root / "local dependency")
            with (repo / "go.mod").open("a") as stream:
                stream.write("replace example.org/local => " + json.dumps(local) + "\n")
            metadata = {"Replace": [{"Old": {"Path": "example.org/local"}, "New": {"Path": local}}]}
            with mock.patch.object(self.compat.subprocess, "check_output", return_value=json.dumps(metadata)):
                driver = self.compat.prepare_driver(repo, root / "output")
            self.assertIn("replace example.org/local => " + json.dumps(local), (driver / "go.mod").read_text())

    def test_generated_driver_is_valid_go_module_with_original_graph(self):
        if not GO:
            self.skipTest("driver parser smoke test requires Go on PATH")
        with tempfile.TemporaryDirectory(prefix="seanime-driver-test-") as temporary:
            root = Path(temporary)
            repo = self.make_repo(root)
            before = tree_snapshot(repo)
            driver = self.compat.prepare_driver(repo, root / "output")
            env = dict(os.environ, GOWORK="off", GOPROXY="off", GOSUMDB="off", GOTOOLCHAIN="local")
            metadata = json.loads(subprocess.check_output([GO, "mod", "edit", "-json"], cwd=driver, env=env, text=True))
            self.assertEqual("seanime.android.build", metadata["Module"]["Path"])
            requirements = {entry["Path"]: entry["Version"] for entry in metadata["Require"]}
            self.assertEqual({"modernc.org/libc": "v1.41.0", "example.org/dependency": "v1.2.3", "seanime": "v0.0.0"}, requirements)
            replacements = {entry["Old"]["Path"]: entry["New"] for entry in metadata["Replace"]}
            self.assertEqual({"Path": "example.org/torrent", "Version": "v1.54.2"}, replacements["github.com/anacrolix/torrent"])
            self.assertEqual({"Path": str(repo)}, replacements["seanime"])
            self.assertEqual({"Path": str(root / "output" / "staged-libc")}, replacements["modernc.org/libc"])
            self.assertIn({"Path": "golang.org/x/mobile/cmd/gomobile"}, metadata["Tool"])
            self.assertEqual(before, tree_snapshot(repo))

    def test_unexpected_root_module_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="seanime-driver-test-") as temporary:
            root = Path(temporary)
            repo = self.make_repo(root)
            (repo / "go.mod").write_text("module another.project\n")
            before = tree_snapshot(repo)
            with mock.patch.object(self.compat.subprocess, "check_output") as command:
                with self.assertRaises(ValueError):
                    self.compat.prepare_driver(repo, root / "output")
            command.assert_not_called()
            self.assertEqual(before, tree_snapshot(repo))
            self.assertFalse((root / "output").exists())


class ModuleResolverTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compat = load_compat()

    def metadata(self, **changes):
        data = {"Path": "modernc.org/libc", "Version": "v1.41.0"}
        data.update(changes)
        return data

    def test_warm_cache_resolves_without_downloading(self):
        repo = Path("/tmp/seanime-resolver-test-repo")
        with mock.patch.object(self.compat.subprocess, "check_output", return_value=json.dumps(self.metadata(Dir="/tmp/pinned-libc"))) as command:
            self.assertEqual(Path("/tmp/pinned-libc"), self.compat.resolve_module(repo))
        self.assertEqual(1, command.call_count)
        self.assertEqual((["go", "list", "-mod=readonly", "-m", "-json", "modernc.org/libc"],), command.call_args.args)
        self.assertEqual(repo, command.call_args.kwargs["cwd"])
        self.assertEqual("off", command.call_args.kwargs["env"]["GOWORK"])

    def test_cold_cache_downloads_outside_repo_without_manifest_changes(self):
        with tempfile.TemporaryDirectory(prefix="seanime-resolver-test-") as temporary:
            root = Path(temporary)
            repo = root / "repo"
            repo.mkdir()
            (repo / "go.mod").write_text("module seanime\n")
            (repo / "go.sum").write_text("unchanged sums\n")
            before = tree_snapshot(repo)
            calls = []
            def invoke(command, *, cwd, env, text):
                calls.append((command, Path(cwd)))
                self.assertEqual("off", env["GOWORK"])
                self.assertTrue(text)
                if len(calls) == 1:
                    self.assertEqual(repo, Path(cwd))
                    return json.dumps(self.metadata())
                self.assertEqual(["go", "mod", "download", "-json", "modernc.org/libc@v1.41.0"], command)
                self.assertTrue(Path(cwd).is_dir())
                self.assertNotEqual(repo, Path(cwd))
                self.assertFalse(Path(cwd).is_relative_to(repo))
                return json.dumps(self.metadata(Dir=str(root / "cache")))
            with mock.patch.object(self.compat.subprocess, "check_output", side_effect=invoke):
                self.assertEqual(root / "cache", self.compat.resolve_module(repo))
            self.assertEqual(2, len(calls))
            self.assertEqual(before, tree_snapshot(repo))
            self.assertFalse(calls[1][1].exists(), "temporary download directory must be cleaned up")

    def test_refuses_wrong_module_version_and_existing_replacements(self):
        for changes in ({"Path": "other/libc"}, {"Version": "v1.42.0"}, {"Replace": {"Path": "/tmp/replacement"}}):
            with self.subTest(changes=changes):
                with mock.patch.object(self.compat.subprocess, "check_output", return_value=json.dumps(self.metadata(**changes))) as command:
                    with self.assertRaises(ValueError):
                        self.compat.resolve_module(Path("/tmp/seanime-resolver-test-repo"))
                self.assertEqual(1, command.call_count, "drift must fail before downloading")

    def test_refuses_download_errors_missing_directory_and_drifted_identity(self):
        failures = (
            self.metadata(Error="network download failed"), self.metadata(),
            self.metadata(Path="other/libc", Dir="/tmp/download"),
            self.metadata(Version="v1.42.0", Dir="/tmp/download"),
        )
        for downloaded in failures:
            with self.subTest(downloaded=downloaded):
                with mock.patch.object(self.compat.subprocess, "check_output", side_effect=[json.dumps(self.metadata()), json.dumps(downloaded)]) as command:
                    with self.assertRaises(ValueError):
                        self.compat.resolve_module(Path("/tmp/seanime-resolver-test-repo"))
                self.assertEqual(2, command.call_count)


GO_TEST_SOURCE = r'''package libc

import (
    "bytes"
    "os"
    "path/filepath"
    "syscall"
    "testing"
    "time"
    "unsafe"

    "golang.org/x/sys/unix"
)

// Keep pointer-backed syscall data on the heap for the whole harness. No test
// runs in parallel, and each process exits immediately after this short suite.
var retained []any
func address[T any](value *T) uintptr {
    retained = append(retained, value)
    return uintptr(unsafe.Pointer(value))
}
func cpath(value string) uintptr {
    p, err := unix.BytePtrFromString(value)
    if err != nil { panic(err) }
    return address(p)
}
func okcall(t *testing.T, trap, a1, a2, a3 uintptr) uintptr {
    t.Helper()
    result, _, errno := seanimeAndroidSyscall(trap, a1, a2, a3)
    if errno != 0 { t.Fatalf("syscall %d: %v", trap, errno) }
    return result
}
func errcall(t *testing.T, want syscall.Errno, trap, a1, a2, a3 uintptr) {
    t.Helper()
    result, _, errno := seanimeAndroidSyscall(trap, a1, a2, a3)
    if errno != want { t.Fatalf("syscall %d: got errno %v, want %v (result %#x)", trap, errno, want, result) }
    if result != ^uintptr(0) { t.Fatalf("syscall %d: errno %v without -1 return (got %#x)", trap, errno, result) }
}
func writefile(t *testing.T, name, content string) {
    t.Helper()
    if err := os.WriteFile(name, []byte(content), 0600); err != nil { t.Fatal(err) }
}
func stat(t *testing.T, trap uintptr, path string) unix.Stat_t {
    t.Helper()
    result := new(unix.Stat_t)
    okcall(t, trap, cpath(path), address(result), 0)
    return *result
}

func TestPassthroughAndErrno(t *testing.T) {
    if got := okcall(t, unix.SYS_GETPID, 0, 0, 0); got != uintptr(os.Getpid()) {
        t.Fatalf("passthrough getpid = %d, want %d", got, os.Getpid())
    }
    errcall(t, unix.EBADF, unix.SYS_FSTAT, ^uintptr(0), address(new(unix.Stat_t)), 0)
    // Reading a limit is safe; no test ever writes a process limit.
    limit := new(unix.Rlimit)
    okcall(t, unix.SYS_GETRLIMIT, unix.RLIMIT_NOFILE, address(limit), 0)
    if limit.Cur == 0 { t.Fatal("getrlimit returned an empty limit") }
}

func TestStatLstatAndReadlink(t *testing.T) {
    dir := t.TempDir()
    target, link := filepath.Join(dir, "target"), filepath.Join(dir, "link")
    writefile(t, target, "payload")
    okcall(t, unix.SYS_SYMLINK, cpath("target"), cpath(link), 0)
    if got := stat(t, unix.SYS_STAT, link); got.Mode & unix.S_IFMT != unix.S_IFREG || got.Size != 7 {
        t.Fatalf("stat did not follow symlink: %+v", got)
    }
    if got := stat(t, unix.SYS_LSTAT, link); got.Mode & unix.S_IFMT != unix.S_IFLNK || got.Size != 6 {
        t.Fatalf("lstat did not preserve symlink: %+v", got)
    }
    buffer := bytes.Repeat([]byte{0x7f}, 16)
    got := okcall(t, unix.SYS_READLINK, cpath(link), address(&buffer[0]), uintptr(len(buffer)))
    if got != 6 || string(buffer[:6]) != "target" || buffer[6] != 0x7f {
        t.Fatalf("readlink length/content/terminator mismatch: %d %v", got, buffer)
    }
    short := make([]byte, 3)
    if got := okcall(t, unix.SYS_READLINK, cpath(link), address(&short[0]), 3); got != 3 || string(short) != "tar" {
        t.Fatalf("readlink truncation: %d %q", got, short)
    }
    errcall(t, unix.ENOENT, unix.SYS_STAT, cpath(filepath.Join(dir, "missing")), address(new(unix.Stat_t)), 0)
    errcall(t, unix.EINVAL, unix.SYS_READLINK, cpath(target), address(&buffer[0]), uintptr(len(buffer)))
}

func TestOpenFlagsAndFilesystemOperations(t *testing.T) {
    dir := t.TempDir()
    sub := filepath.Join(dir, "sub")
    okcall(t, unix.SYS_MKDIR, cpath(sub), 0700, 0)
    file, renamed, hardlink := filepath.Join(sub, "a"), filepath.Join(sub, "b"), filepath.Join(sub, "c")
    fd := int(okcall(t, unix.SYS_OPEN, cpath(file), unix.O_CREAT|unix.O_EXCL|unix.O_RDWR|unix.O_CLOEXEC, 0600))
    if flags, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil || flags & unix.FD_CLOEXEC == 0 {
        t.Fatalf("open lost O_CLOEXEC: flags=%#x err=%v", flags, err)
    }
    if _, err := unix.Write(fd, []byte("initial")); err != nil { t.Fatal(err) }
    if err := unix.Close(fd); err != nil { t.Fatal(err) }
    errcall(t, unix.EEXIST, unix.SYS_OPEN, cpath(file), unix.O_CREAT|unix.O_EXCL|unix.O_WRONLY, 0600)
    fd = int(okcall(t, unix.SYS_OPEN, cpath(file), unix.O_WRONLY|unix.O_APPEND, 0))
    if _, err := unix.Write(fd, []byte("+")); err != nil { t.Fatal(err) }
    unix.Close(fd)
    if body, err := os.ReadFile(file); err != nil || string(body) != "initial+" { t.Fatalf("append: %q %v", body, err) }
    fd = int(okcall(t, unix.SYS_OPEN, cpath(file), unix.O_WRONLY|unix.O_TRUNC, 0))
    unix.Close(fd)
    if got := stat(t, unix.SYS_STAT, file); got.Size != 0 { t.Fatalf("O_TRUNC size=%d", got.Size) }
    okcall(t, unix.SYS_CHMOD, cpath(file), 0640, 0)
    if got := stat(t, unix.SYS_STAT, file); got.Mode & 0777 != 0640 { t.Fatalf("chmod: %#o", got.Mode) }
    okcall(t, unix.SYS_ACCESS, cpath(file), unix.F_OK, 0)
    errcall(t, unix.EINVAL, unix.SYS_ACCESS, cpath(file), 8, 0)
    okcall(t, unix.SYS_RENAME, cpath(file), cpath(renamed), 0)
    errcall(t, unix.ENOENT, unix.SYS_ACCESS, cpath(file), unix.F_OK, 0)
    okcall(t, unix.SYS_LINK, cpath(renamed), cpath(hardlink), 0)
    first, second := stat(t, unix.SYS_STAT, renamed), stat(t, unix.SYS_STAT, hardlink)
    if first.Ino != second.Ino || first.Nlink != 2 { t.Fatalf("hard link mismatch: %+v %+v", first, second) }
    errcall(t, unix.ENOTEMPTY, unix.SYS_RMDIR, cpath(sub), 0, 0)
    okcall(t, unix.SYS_UNLINK, cpath(renamed), 0, 0)
    okcall(t, unix.SYS_UNLINK, cpath(hardlink), 0, 0)
    errcall(t, unix.ENOENT, unix.SYS_UNLINK, cpath(hardlink), 0, 0)
    okcall(t, unix.SYS_RMDIR, cpath(sub), 0, 0)
    if _, err := os.Stat(sub); !os.IsNotExist(err) { t.Fatalf("rmdir: %v", err) }
}

func TestSymlinkOpenAndLinkSemantics(t *testing.T) {
    dir := t.TempDir()
    target, link, hard := filepath.Join(dir, "target"), filepath.Join(dir, "link"), filepath.Join(dir, "hard")
    writefile(t, target, "keep")
    okcall(t, unix.SYS_SYMLINK, cpath("target"), cpath(link), 0)
    errcall(t, unix.ELOOP, unix.SYS_OPEN, cpath(link), unix.O_RDONLY|unix.O_NOFOLLOW, 0)
    // Legacy link() hard-links the symlink itself, rather than its target.
    okcall(t, unix.SYS_LINK, cpath(link), cpath(hard), 0)
    a, b := stat(t, unix.SYS_LSTAT, link), stat(t, unix.SYS_LSTAT, hard)
    if a.Ino != b.Ino || b.Mode & unix.S_IFMT != unix.S_IFLNK { t.Fatalf("link followed symlink: %+v %+v", a, b) }
    okcall(t, unix.SYS_UNLINK, cpath(link), 0, 0)
    if body, err := os.ReadFile(target); err != nil || string(body) != "keep" { t.Fatalf("unlink affected target: %q %v", body, err) }
    errcall(t, unix.ENOTDIR, unix.SYS_RMDIR, cpath(hard), 0, 0)
}

func TestRelativePathsUseWorkingDirectory(t *testing.T) {
    t.Chdir(t.TempDir())
    okcall(t, unix.SYS_MKDIR, cpath("sub"), 0700, 0)
    fd := int(okcall(t, unix.SYS_OPEN, cpath("sub/a"), unix.O_CREAT|unix.O_WRONLY, 0600))
    unix.Close(fd)
    okcall(t, unix.SYS_CHMOD, cpath("sub/a"), 0640, 0)
    okcall(t, unix.SYS_ACCESS, cpath("sub/a"), unix.F_OK, 0)
    okcall(t, unix.SYS_RENAME, cpath("sub/a"), cpath("sub/b"), 0)
    okcall(t, unix.SYS_LINK, cpath("sub/b"), cpath("sub/c"), 0)
    okcall(t, unix.SYS_SYMLINK, cpath("b"), cpath("sub/link"), 0)
    if got := stat(t, unix.SYS_STAT, "sub/link"); got.Mode & unix.S_IFMT != unix.S_IFREG { t.Fatalf("relative stat: %+v", got) }
    if got := stat(t, unix.SYS_LSTAT, "sub/link"); got.Mode & unix.S_IFMT != unix.S_IFLNK { t.Fatalf("relative lstat: %+v", got) }
    buffer := make([]byte, 2)
    if got := okcall(t, unix.SYS_READLINK, cpath("sub/link"), address(&buffer[0]), 2); got != 1 || buffer[0] != 'b' { t.Fatalf("relative readlink: %d %q", got, buffer) }
    for _, file := range []string{"sub/b", "sub/c", "sub/link"} { okcall(t, unix.SYS_UNLINK, cpath(file), 0, 0) }
    okcall(t, unix.SYS_RMDIR, cpath("sub"), 0, 0)
}

func TestChownAndFifoMknod(t *testing.T) {
    dir := t.TempDir()
    file := filepath.Join(dir, "owned")
    writefile(t, file, "data")
    // Keep current ownership, entirely within a private temporary directory.
    okcall(t, unix.SYS_CHOWN, cpath(file), uintptr(os.Getuid()), uintptr(os.Getgid()))
    before := stat(t, unix.SYS_STAT, file)
    okcall(t, unix.SYS_CHOWN, cpath(file), ^uintptr(0), ^uintptr(0))
    after := stat(t, unix.SYS_STAT, file)
    if before.Uid != after.Uid || before.Gid != after.Gid { t.Fatalf("chown -1 changed ownership: %+v %+v", before, after) }
    fifo := filepath.Join(dir, "fifo")
    // FIFO creation is unprivileged and does not open or create a device node.
    okcall(t, unix.SYS_MKNOD, cpath(fifo), unix.S_IFIFO|0600, 0)
    if got := stat(t, unix.SYS_LSTAT, fifo); got.Mode & unix.S_IFMT != unix.S_IFIFO { t.Fatalf("mknod FIFO: %+v", got) }
    errcall(t, unix.EEXIST, unix.SYS_MKNOD, cpath(fifo), unix.S_IFIFO|0600, 0)
}

func TestPipeAndDup2(t *testing.T) {
    pipe := new([2]int32)
    okcall(t, unix.SYS_PIPE, address(pipe), 0, 0)
    readfd, writefd := int(pipe[0]), int(pipe[1])
    defer unix.Close(readfd)
    defer unix.Close(writefd)
    if flags, err := unix.FcntlInt(uintptr(readfd), unix.F_GETFD, 0); err != nil || flags & unix.FD_CLOEXEC != 0 {
        t.Fatalf("pipe() must preserve default descriptor flags: %#x %v", flags, err)
    }
    if _, err := unix.Write(writefd, []byte("abc")); err != nil { t.Fatal(err) }
    buffer := make([]byte, 3)
    if count, err := unix.Read(readfd, buffer); err != nil || count != 3 || string(buffer) != "abc" { t.Fatalf("pipe read: %d %q %v", count, buffer, err) }
    if _, err := unix.FcntlInt(uintptr(readfd), unix.F_SETFD, unix.FD_CLOEXEC); err != nil { t.Fatal(err) }
    if got := okcall(t, unix.SYS_DUP2, uintptr(readfd), uintptr(readfd), 0); int(got) != readfd { t.Fatalf("same-fd dup2 = %d", got) }
    if flags, err := unix.FcntlInt(uintptr(readfd), unix.F_GETFD, 0); err != nil || flags & unix.FD_CLOEXEC == 0 {
        t.Fatalf("same-fd dup2 changed close-on-exec: %#x %v", flags, err)
    }
    // Use a valid descriptor owned by this test as the replacement destination.
    destination, err := unix.Open("/dev/null", unix.O_RDONLY|unix.O_CLOEXEC, 0)
    if err != nil { t.Fatal(err) }
    defer unix.Close(destination)
    if got := okcall(t, unix.SYS_DUP2, uintptr(readfd), uintptr(destination), 0); int(got) != destination { t.Fatalf("dup2 = %d, want %d", got, destination) }
    if flags, err := unix.FcntlInt(uintptr(destination), unix.F_GETFD, 0); err != nil || flags & unix.FD_CLOEXEC != 0 {
        t.Fatalf("dup2 must clear close-on-exec: %#x %v", flags, err)
    }
    errcall(t, unix.EBADF, unix.SYS_DUP2, ^uintptr(0), ^uintptr(0), 0)
    errcall(t, unix.EBADF, unix.SYS_DUP2, ^uintptr(0), uintptr(destination), 0)
    closed, err := unix.Dup(readfd)
    if err != nil { t.Fatal(err) }
    unix.Close(closed)
    errcall(t, unix.EBADF, unix.SYS_DUP2, uintptr(closed), uintptr(closed), 0)
}

// This mirrors the unchanged Xtime wrapper in the pinned libc source. Xtime
// writes tloc itself, so the Android helper only needs to return epoch seconds.
func libcTime(t *testing.T, location uintptr) uintptr {
    result := okcall(t, unix.SYS_TIME, location, 0, 0)
    if location != 0 { *(*int64)(unsafe.Pointer(location)) = int64(result) }
    return result
}

func TestAlarmRoundingWithoutSchedulingTimer(t *testing.T) {
    for _, test := range []struct{seconds, microseconds int64; want uintptr}{
        {0, 0, 0}, {0, 1, 1}, {0, 499999, 1}, {0, 500000, 1},
        {1, 0, 1}, {1, 1, 1}, {1, 499999, 1}, {1, 500000, 2},
        {1, 999999, 2}, {100, 499999, 100}, {100, 500000, 101},
    } {
        if got := seanimeAndroidAlarmSeconds(test.seconds, test.microseconds); got != test.want {
            t.Errorf("alarm rounding (%d, %d) = %d, want %d", test.seconds, test.microseconds, got, test.want)
        }
    }
}

func TestTimeAndTimestampConversions(t *testing.T) {
    now := new(int64)
    before := time.Now().Unix()
    result := libcTime(t, address(now))
    after := time.Now().Unix()
    if *now < before || *now > after || int64(result) != *now { t.Fatalf("time result=%d pointed=%d window=%d..%d", result, *now, before, after) }
    if got := int64(libcTime(t, 0)); got < before || got > time.Now().Unix() { t.Fatalf("time(NULL)=%d", got) }
    file := filepath.Join(t.TempDir(), "times")
    writefile(t, file, "test")
    utime := &[2]int64{1234567890, 1234567899}
    okcall(t, unix.SYS_UTIME, cpath(file), address(utime), 0)
    got := stat(t, unix.SYS_STAT, file)
    if got.Atim.Sec != utime[0] || got.Atim.Nsec != 0 || got.Mtim.Sec != utime[1] || got.Mtim.Nsec != 0 { t.Fatalf("utime: %+v", got) }
    timevals := &[2]unix.Timeval{{Sec:1234567800, Usec:123456}, {Sec:1234567801, Usec:987654}}
    okcall(t, unix.SYS_UTIMES, cpath(file), address(timevals), 0)
    got = stat(t, unix.SYS_STAT, file)
    if got.Atim.Sec != timevals[0].Sec || got.Atim.Nsec != timevals[0].Usec*1000 || got.Mtim.Sec != timevals[1].Sec || got.Mtim.Nsec != timevals[1].Usec*1000 { t.Fatalf("utimes microsecond conversion: %+v", got) }
    for _, index := range []int{0, 1} {
        for _, invalid := range []int64{-1, 1000000, 1 << 40} {
            timevals[0].Usec, timevals[1].Usec = 123456, 987654
            timevals[index].Usec = invalid
            errcall(t, unix.EINVAL, unix.SYS_UTIMES, cpath(file), address(timevals), 0)
        }
    }
    for _, trap := range []uintptr{unix.SYS_UTIME, unix.SYS_UTIMES} {
        errcall(t, unix.EFAULT, trap, 0, 0, 0)
        // A valid non-null timestamp pointer does not make a NULL path valid.
        zeroTimes := new([4]int64)
        errcall(t, unix.EFAULT, trap, 0, address(zeroTimes), 0)
        before := time.Now().Unix()
        okcall(t, trap, cpath(file), 0, 0)
        got := stat(t, unix.SYS_STAT, file)
        if got.Atim.Sec < before || got.Atim.Sec > time.Now().Unix() || got.Mtim.Sec < before || got.Mtim.Sec > time.Now().Unix() { t.Fatalf("timestamp syscall %d with NULL: %+v", trap, got) }
        errcall(t, unix.ENOENT, trap, cpath(file+"missing"), 0, 0)
    }
}
'''


class NativeHelperTests(unittest.TestCase):
    def test_native_linux_host_and_forced_android_semantics(self):
        if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
            self.skipTest("native syscall harness requires Linux/amd64")
        if not GO:
            self.skipTest("native syscall harness requires Go on PATH")
        xsys = module_cache() / "golang.org" / "x" / "sys@v0.47.0"
        if not (xsys / "go.mod").is_file():
            self.skipTest("native syscall harness requires cached golang.org/x/sys@v0.47.0")
        compat = load_compat()
        helper = compat.ANDROID_SYSCALL_HELPER
        # Test-only substitution reaches the Android dispatch on a Linux host.
        # It does not alter the committed helper, module cache or Go runtime.
        android, substitutions = re.subn(r'runtime\.GOOS\s*!=\s*"android"', 'runtime.GOOS != "linux"', helper)
        self.assertEqual(1, substitutions, "must force exactly the helper's platform guard")
        with tempfile.TemporaryDirectory(prefix="seanime-syscall-harness-") as temporary:
            root = Path(temporary)
            for label, source in (("host", helper), ("android", android)):
                with self.subTest(branch=label):
                    work = root / label
                    work.mkdir()
                    # Build constraints belong to the shipping helper; test only
                    # its contents in this private, temporary Linux module.
                    source = re.sub(r"(?m)^//(?:go:build| \+build).*\n", "", source)
                    (work / "compat.go").write_text(source)
                    (work / "compat_test.go").write_text(GO_TEST_SOURCE)
                    (work / "go.mod").write_text(
                        "module seanime.test/androidlibc\n\ngo 1.25.0\n\n"
                        "require golang.org/x/sys v0.47.0\n\n"
                        f"replace golang.org/x/sys => {json.dumps(str(xsys))}\n"
                    )
                    environment = os.environ.copy()
                    environment.update({"GOWORK": "off", "GOPROXY": "off", "GOSUMDB": "off", "GOOS": "linux", "GOARCH": "amd64", "CGO_ENABLED": "0", "GOTOOLCHAIN": "local", "GOFLAGS": "-buildvcs=false"})
                    result = subprocess.run(
                        [GO, "test", "-count=1", "-v", "."], cwd=work, env=environment,
                        capture_output=True, text=True, timeout=180,
                    )
                    self.assertEqual(0, result.returncode, f"{label} helper semantics failed:\n{result.stdout}\n{result.stderr}")


if __name__ == "__main__":
    unittest.main()
