import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from verify_android_native_alignment import check_apk, check_elf, ELF_HEADER, PROGRAM_HEADER, PAGE_SIZE


def elf(alignment=PAGE_SIZE, file_offset=0, virtual_address=0, machine=183):
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    header = ELF_HEADER.pack(ident, 3, machine, 1, 0, ELF_HEADER.size, 0, 0, ELF_HEADER.size, PROGRAM_HEADER.size, 1, 0, 0, 0)
    segment = PROGRAM_HEADER.pack(1, 5, file_offset, virtual_address, 0, 120, 120, alignment)
    return header + segment


class AlignmentTests(unittest.TestCase):
    def check_binary(self, binary, abi="arm64-v8a"):
        return check_elf(io.BytesIO(binary), len(binary), abi)

    def check_archive(self, binary=None, compression=zipfile.ZIP_DEFLATED, aligned_zip=False):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.apk"
            with zipfile.ZipFile(path, "w") as archive:
                if binary is not None:
                    entry = zipfile.ZipInfo("lib/arm64-v8a/test.so")
                    entry.compress_type = compression
                    if aligned_zip:
                        padding = PAGE_SIZE - (30 + len(entry.filename.encode())) % PAGE_SIZE
                        entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
                    archive.writestr(entry, binary)
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
        self.assertEqual((1, []), self.check_archive(elf()))

    def test_requires_zip_alignment_for_stored_libraries(self):
        count, errors = self.check_archive(elf(), zipfile.ZIP_STORED)
        self.assertEqual(1, count)
        self.assertIn("ZIP data offset", errors[0])
        self.assertEqual((1, []), self.check_archive(elf(), zipfile.ZIP_STORED, aligned_zip=True))

    def test_rejects_apks_without_native_libraries(self):
        self.assertEqual((0, ["APK contains no native libraries"]), self.check_archive())


if __name__ == "__main__":
    unittest.main()
