#!/usr/bin/env python3
"""Check required Seanime native tools, ELF alignment and .so ZIP offsets in APKs."""

import argparse
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16 * 1024
ELF_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")
MACHINES = {"arm64-v8a": 183, "x86_64": 62}
REQUIRED_LIBRARIES = {"libgojni.so", "libffmpeg.so", "libffprobe.so", "libc++_shared.so"}


def check_elf(stream, size, abi):
    """Return an error list, reading only the ELF header and program headers."""
    header = stream.read(ELF_HEADER.size)
    if len(header) != ELF_HEADER.size:
        return ["truncated ELF header"]
    fields = ELF_HEADER.unpack(header)
    if fields[0][:7] != b"\x7fELF\x02\x01\x01":
        return ["expected a little-endian ELF64 binary"]
    if fields[2] != MACHINES[abi]:
        return [f"ELF machine {fields[2]} does not match {abi}"]
    offset, stride, count = fields[5], fields[9], fields[10]
    if stride < PROGRAM_HEADER.size or count == 0 or offset + stride * count > size:
        return ["invalid ELF program header table"]
    stream.seek(offset)
    headers = stream.read(stride * count)
    errors = []
    load_count = 0
    for index in range(count):
        segment = PROGRAM_HEADER.unpack_from(headers, index * stride)
        if segment[0] != 1:  # PT_LOAD
            continue
        load_count += 1
        file_offset, virtual_address, alignment = segment[2], segment[3], segment[7]
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            errors.append(f"LOAD {index} alignment is {alignment:#x}; expected a power of two >= {PAGE_SIZE:#x}")
        if (virtual_address - file_offset) % PAGE_SIZE:
            errors.append(f"LOAD {index} file and virtual offsets differ modulo {PAGE_SIZE}")
    if not load_count:
        errors.append("ELF contains no loadable segments")
    return errors


def check_apk(path):
    failures = []
    libraries = 0
    libraries_by_abi = {}
    with zipfile.ZipFile(path) as archive, open(path, "rb") as raw:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            libraries += 1
            abi = parts[1]
            if abi not in MACHINES:
                failures.append(f"{entry.filename}: unexpected ABI {abi}")
                continue
            libraries_by_abi.setdefault(abi, set()).add(parts[2])
            with archive.open(entry) as stream:
                errors = check_elf(stream, entry.file_size, abi)
            # Compressed libraries are extracted by Android's installer. Stored
            # libraries can be mapped directly and need ZIP data alignment too.
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                if len(header) != 30 or header[:4] != b"PK\x03\x04":
                    errors.append("invalid ZIP local header")
                else:
                    name_size, extra_size = struct.unpack_from("<HH", header, 26)
                    data_offset = entry.header_offset + 30 + name_size + extra_size
                    if data_offset % PAGE_SIZE:
                        errors.append(f"uncompressed ZIP data offset {data_offset} is not 16 KiB aligned")
            failures.extend(f"{entry.filename}: {error}" for error in errors)
    for abi, present in sorted(libraries_by_abi.items()):
        for missing in sorted(REQUIRED_LIBRARIES - present):
            failures.append(f"lib/{abi}/{missing}: required native library is missing")
    if not libraries:
        failures.append("APK contains no native libraries")
    return libraries, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    failed = False
    for path in args.apks:
        try:
            libraries, errors = check_apk(path)
        except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
            libraries, errors = 0, [str(error)]
        if errors:
            failed = True
            for error in errors:
                print(f"FAIL {path}: {error}", file=sys.stderr)
        else:
            print(f"PASS {path}: required native tools present; {libraries} native libraries have 16 KiB ELF/ZIP alignment")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
