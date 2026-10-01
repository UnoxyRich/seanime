# Android TV runtime alternatives — 2026-10-01

## Verdict

**API 31 ARM64 Android TV is not a supported alternate AVD on this Linux
x86_64 host through the installed ordinary Android Emulator launcher.**
The launcher rejects ARM64 AVDs at API 28 and above before selecting/executing
the QEMU backend. Disabling acceleration does not remove that architecture
guard. Do not download this image or allocate another AVD for this route.

This is a read-only feasibility result, not a runtime test or app acceptance.
No emulator command (including version checks), AVD creation, image download,
device connection, data reset, or cache cleanup was performed.

## Direct evidence

- Installed SDK metadata: `toolchain/android-sdk/emulator/source.properties`
  reports emulator **37.1.11**, build **15917651**. `file` identifies its
  `emulator` launcher as an x86-64 Linux ELF executable.
- Official [AOSP launcher source](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/emulator/main-emulator.cpp#1047)
  (observed blob `b9aa22a54b75b72e5e876910db2b132c47e9095f`) checks
  `sarch == "arm64" && apiLevel >= 28` under `__x86_64__` and calls `APANIC`.
  This precedes `getQemuExecutablePath` and backend execution.
- Read-only disassembly confirms that guard in the **installed binary**, not
  merely a potentially different upstream version: address `0x180e1f` loads
  the `arm64` string; `0x180e32` compares the API value against `0x1c` (28);
  `0x180e35` conditionally jumps to `0x181ad2`, which loads the rejection
  message and calls the panic routine. The installed message is:
  “Avd's CPU Architecture '%s' is not supported by the QEMU2 emulator on
  x86_64 host. System image must match the host architecture.”
- Launcher SHA-256:
  `d430b26ab9806894b06d6a1d3750d8c8a5125300967b2bd87d76245541bc4ce1`.
  The bundled `qemu-system-aarch64` and `qemu-system-aarch64-headless`
  executables therefore do **not** establish ordinary-launcher support.
- The official [command-line documentation](https://developer.android.com/studio/run/emulator-commandline#common)
  describes `-no-accel` / `-accel off` as disabling acceleration for x86/x86_64
  system images. It does not authorize bypassing the launcher guard.

## Catalog and space

The cached official `toolchain/downloads/tv-images.xml` lists the candidate as
`system-images;android-31;android-tv;arm64-v8a`, revision **4**, archive
`arm64-v8a-31_r04.zip`, compressed size **763,104,604 bytes** (727.75 MiB),
SHA-1 `e8cec4080464d516e3f863d943c24055155f29bf`. An available package and an
existing ARM64 APK are insufficient to make this host/image pairing supported.

The same catalog has Android TV API 31/33/34 images only for x86 and ARM64;
its sole x86_64 Android TV entry is API 36 revision 4. Only API 36 x86_64 is
installed in this SDK. No older x86_64 TV alternative was established.

The host filesystem reported **7.7 GiB available**. The catalog gives no
unpacked size; that size was not verified and should not be guessed as an
installation budget. Because the route is blocked, no fresh-AVD setup or
space allocation is recommended. Existing AVDs, their data, and all caches
were preserved.

## Interpretation and stopping point

An API 31 image would test Android 12 behavior and the ARM64 native libraries,
not the API 36 x86_64 environment. The older
`2026-09-26-api31-arm64.md` record names no host architecture and covers an
earlier UI implementation; it is not evidence of launch support here or of
current app acceptance.

Without a hypervisor, [Android's acceleration documentation](https://developer.android.com/studio/run/emulator-acceleration#accel-vm)
explains that guest code needs block-by-block translation and can be slow.
An older OS alone would not establish better TCG performance or reliable
framework timing. No runtime, speed, stability, or acceptance success is
claimed. The current API 36 framework-ANR limitation remains unresolved;
source/build readiness work can proceed without another unsupported
infrastructure attempt.
