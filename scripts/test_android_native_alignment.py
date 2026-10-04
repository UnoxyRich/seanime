import io
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from verify_android_native_alignment import (
    check_apk, check_elf, ELF_HEADER, PROGRAM_HEADER, PAGE_SIZE, REQUIRED_LIBRARIES,
)


def elf(alignment=PAGE_SIZE, file_offset=0, virtual_address=0, machine=183):
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    header = ELF_HEADER.pack(ident, 3, machine, 1, 0, ELF_HEADER.size, 0, 0, ELF_HEADER.size, PROGRAM_HEADER.size, 1, 0, 0, 0)
    segment = PROGRAM_HEADER.pack(1, 5, file_offset, virtual_address, 0, 120, 120, alignment)
    return header + segment


class AlignmentTests(unittest.TestCase):
    def check_binary(self, binary, abi="arm64-v8a"):
        return check_elf(io.BytesIO(binary), len(binary), abi)

    def write_libraries(self, archive, abi, binary, names=REQUIRED_LIBRARIES,
                        compression=zipfile.ZIP_DEFLATED, aligned_zip=False):
        for name in sorted(names):
            entry = zipfile.ZipInfo(f"lib/{abi}/{name}")
            entry.compress_type = compression
            if aligned_zip:
                offset = archive.fp.tell() + 30 + len(entry.filename.encode())
                padding = (-offset) % PAGE_SIZE
                if 0 < padding < 4:
                    padding += PAGE_SIZE
                if padding:
                    entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
            archive.writestr(entry, binary)

    def check_archive(self, binary=None, compression=zipfile.ZIP_DEFLATED, aligned_zip=False,
                      abi="arm64-v8a", names=REQUIRED_LIBRARIES):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.apk"
            with zipfile.ZipFile(path, "w") as archive:
                if binary is not None:
                    self.write_libraries(archive, abi, binary, names, compression, aligned_zip)
                else:
                    archive.writestr("assets/test.txt", "no libraries")
            return check_apk(path)

    def test_accepts_16k_and_64k_segments_on_both_abis(self):
        self.assertEqual([], self.check_binary(elf()))
        self.assertEqual([], self.check_binary(elf(alignment=65536)))
        self.assertEqual([], self.check_binary(elf(machine=62), "x86_64"))

    def test_rejects_4k_and_non_power_of_two_alignment(self):
        self.assertIn("alignment", self.check_binary(elf(alignment=4096))[0])
        self.assertIn("alignment", self.check_binary(elf(alignment=24576))[0])

    def test_rejects_incongruent_offsets_even_if_alignment_field_says_16k(self):
        self.assertIn("offsets differ", self.check_binary(elf(virtual_address=4096))[0])

    def test_rejects_wrong_abi_and_truncated_headers(self):
        self.assertIn("does not match", self.check_binary(elf(machine=62))[0])
        self.assertEqual(["truncated ELF header"], self.check_binary(b"bad"))
        self.assertIn("program header", self.check_binary(elf()[:100])[0])

    def test_accepts_compressed_libraries_without_zip_alignment(self):
        self.assertEqual((4, []), self.check_archive(elf()))
        self.assertEqual((4, []), self.check_archive(elf(machine=62), abi="x86_64"))

    def test_requires_zip_alignment_for_stored_libraries(self):
        count, errors = self.check_archive(elf(), zipfile.ZIP_STORED)
        self.assertEqual(4, count)
        self.assertIn("ZIP data offset", errors[0])
        self.assertEqual((4, []), self.check_archive(elf(), zipfile.ZIP_STORED, aligned_zip=True))

    def test_rejects_apks_without_native_libraries(self):
        self.assertEqual((0, ["APK contains no native libraries"]), self.check_archive())

    def test_rejects_historical_partial_x86_payload_despite_valid_alignment(self):
        present = {"libgojni.so", "libc++_shared.so", "libass.so", "libother.so"}
        count, errors = self.check_archive(elf(machine=62), abi="x86_64", names=present)
        self.assertEqual(len(present), count)
        self.assertEqual([
            "lib/x86_64/libffmpeg.so: required native library is missing",
            "lib/x86_64/libffprobe.so: required native library is missing",
        ], errors)

    def test_rejects_each_missing_required_library_for_each_abi(self):
        for abi, machine in [("arm64-v8a", 183), ("x86_64", 62)]:
            for missing in REQUIRED_LIBRARIES:
                with self.subTest(abi=abi, missing=missing):
                    count, errors = self.check_archive(
                        elf(machine=machine), abi=abi, names=REQUIRED_LIBRARIES - {missing})
                    self.assertEqual(3, count)
                    self.assertEqual([f"lib/{abi}/{missing}: required native library is missing"], errors)

    def test_complete_abi_does_not_hide_incomplete_second_abi(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.apk"
            with zipfile.ZipFile(path, "w") as archive:
                self.write_libraries(archive, "arm64-v8a", elf())
                self.write_libraries(archive, "x86_64", elf(machine=62), {"libgojni.so"})
            count, errors = check_apk(path)
        self.assertEqual(5, count)
        self.assertEqual(3, len(errors))
        self.assertTrue(all(error.startswith("lib/x86_64/") for error in errors))

    def test_cli_returns_failure_for_incomplete_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "app-x86_64-debug.apk"
            with zipfile.ZipFile(path, "w") as archive:
                self.write_libraries(archive, "x86_64", elf(machine=62), {"libgojni.so"})
            result = subprocess.run([
                sys.executable, str(Path(__file__).with_name("verify_android_native_alignment.py")), str(path),
            ], capture_output=True, text=True, check=False)
        self.assertEqual(1, result.returncode)
        self.assertIn("lib/x86_64/libffmpeg.so: required native library is missing", result.stderr)
        self.assertNotIn("PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
