# OTGFormat

An Android app that formats USB mass-storage devices over OTG, without root,
with full control of filesystem, cluster size, label, and partition table.

**Current state: Phase 0 is complete.** The FAT32 + MBR writer exists as a pure
Kotlin/JVM module and is verified against real `dosfstools` at four volume
sizes. There is no Android code yet — by design, and see below for why.

```
./gradlew test
```

68 tests. On a machine with `dosfstools`, `mtools` and `file` installed, that
formats images at 64 MiB, 2 GiB, 8 GiB and 64 GiB, validates every one with
`fsck.vfat`, diffs each boot sector field-by-field against `mkfs.vfat` and
prints the table, and round-trips real files through each volume.

```
sudo apt-get install dosfstools mtools file
```

Without those tools the integration tests skip with a message naming the
missing package; the 41 pure-arithmetic tests still run.

---

## Why no Android code yet

Every bug in a filesystem writer is an off-by-one in a field offset. Debugging
those through *sideload → plug in a stick → observe failure* is agonisingly
slow. Debugging them against a file on disk with `fsck.vfat` as an oracle takes
under a second.

So the formatter core is a plain Kotlin/JVM module with no Android dependencies,
proven correct first. By the time Android code is written, the hard part is
already done and the app layer is permission plumbing and a progress bar.

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `core` | `SectorDevice`, `Mbr`, `Fat32Layout`, `Fat32Formatter`, `Formatter` | nothing |
| `jvm-test` | `FileSectorDevice`, the oracle bridge, the acceptance suite | `core` |
| `android` | *(Phase 1)* `LibaumsSectorDevice`, permissions, UI | `core` |

The dependency arrow points one way. `core` has zero Android imports and must
keep it that way: that property is the entire reason the tests are fast.

Everything in `core` talks to one interface:

```kotlin
interface SectorDevice {
    val blockSize: Int          // honoured, not assumed — some readers report 4096
    val sectorCount: Long
    fun read(sector: Long, dst: ByteArray)
    fun write(sector: Long, src: ByteArray)
    fun flush()
}
```

`read`/`write` transfer `buffer.size / blockSize` consecutive sectors. Batching
is part of the contract, not an optimisation: a USB Bulk-Only-Transport round
trip per 512 bytes would make zeroing a FAT on a large stick take minutes of
pure protocol overhead.

## Plan, then write

`Formatter` is split in two so the user can see the entire outcome before
authorising anything:

```kotlin
val layout = Formatter.plan(device, options)   // touches nothing
println(layout.describe())
val result = Formatter.format(device, options, progress)
```

`plan` computes and validates the complete geometry — cluster size, FAT size,
cluster count, usable capacity, and any warnings — and throws `FormatException`
with an explanation rather than writing an invalid volume. A test asserts this
by passing a device that throws on every access.

## What was decided, and why

Three places where this implementation makes a deliberate choice. Each is
covered by a test that would fail if the choice were quietly reversed.

### Cluster size below 256 MiB

Microsoft's standard table bottoms out at 4 KiB clusters. A FAT32 volume needs
at least 65525 clusters, so 4 KiB clusters require roughly 256 MiB before the
volume is legal — which means the table as written cannot format anything
between its own stated 32 MiB floor and ~256 MiB. A 64 MiB stick would be
refused, and the acceptance criteria require 64 MiB to work.

The 32 MiB floor in that table is really the floor for *512-byte* clusters
(65525 × 512 ≈ 32 MiB). So `ClusterSizeTable.forVolume` starts at the table
value and halves the cluster size until the volume clears 65525 clusters. This
reproduces the table exactly everywhere the table applies, and extends it
sensibly below. Stepping down is reported as a warning, never silent.

Below roughly 32 MiB no cluster size works, and the volume is refused with an
error that says so.

### FAT size is larger than `mkfs.vfat`'s

`Fat32Layout.fatSizeSectorsFor` uses the `fatgen103` closed-form formula, which
over-allocates slightly. `mkfs.vfat` computes the exact minimum, so its
`BPB_FATSz32` is 2–15 sectors smaller in the tested range. Over-allocating
wastes a few clusters and is always safe; under-allocating produces a volume
whose last clusters have no FAT entry to describe them. The oracle test asserts
ours is **never smaller** and prints the delta.

The formula's literal `256` is `bytesPerSector / 2` with 512 assumed. It is
written out, so 4096-byte-sector media get a correct FAT rather than one eight
times too small.

Because the formula is an approximation, the property that actually matters is
asserted rather than assumed: `plan` refuses to proceed unless the FAT can hold
an entry for every cluster.

### `FAT[2]` is `0x0FFFFFFF`, `mkfs.vfat` writes `0x0FFFFFF8`

FAT32 treats any entry at or above `0x0FFFFFF8` as end-of-chain, so both mark
the root directory's single-cluster chain correctly. We write `0x0FFFFFFF`,
which is what the specification recommends and what Windows writes. The test
asserts the semantics — both values are end-of-chain — rather than the bytes.

### Data-area alignment

Reserved sectors are fixed at 32, per the specification. Since `FATSz` is
arbitrary, the data area does not always begin on a cluster boundary, so cluster
writes can straddle flash erase blocks. This is harmless to correctness and
`plan` reports it as a warning. `mkfs.vfat` pads the reserved region to fix it
(which is why the oracle is run with `-a` to disable that, or the two layouts
would not be comparable). If this turns out to matter on real hardware, the
change is local to `Fat32Layout.compute`.

## Things built for trust rather than for the spec

The brief asked for an app the user can trust. Four properties in `core` serve
that directly:

- **Read-back verification.** After flushing, the boot sector, FSInfo, both
  backups and each FAT's first sector are read back and compared. A stick that
  accepts writes and returns something else is not theoretical — it is exactly
  how counterfeit-capacity flash behaves, and it is silent until the data is
  needed. The partition table is verified separately, before the filesystem is
  written, so a failing device costs nothing. Tests simulate a device that
  swallows a specific sector and assert both are caught.

- **Boot sectors are written last.** The reserved area is cleared first, so a
  format interrupted halfway leaves a zeroed boot sector — a device that reads
  as blank. Writing the boot sector early would instead leave a device
  announcing a valid filesystem on top of a half-written FAT, which is the one
  failure mode a user cannot detect. A cancellation test asserts this.

- **Stale signatures are wiped.** The 1 MiB gap before the partition and the
  last 33 sectors are zeroed, removing a GPT header and its backup left by a
  previous format. A stale GPT beside a fresh MBR is a hybrid table: some
  firmware follows one, some the other, and a stick that works on one machine
  silently fails on the next. Controlled by `wipeStaleSignatures`.

- **Refusing loudly.** Invalid geometry, bad labels, impossible cluster sizes
  and GPT all throw before anything is written, with a message that says what
  is wrong and what to do about it. A rejected format is asserted to have
  written zero sectors.

## How correctness is established

`fsck.vfat` runs with `-n` (never write) and `-V` (verification pass), so it can
only report, never quietly repair a defect into passing. Exit code 0 alone is a
weak signal, so the tests parse the geometry `fsck` derives from our BPB and
compare every field to what `core` planned — sector size, cluster size, reserved
sectors, FAT size and location, root cluster, data start, cluster count, hidden
sectors, total sectors. A boot sector can be self-consistent and still describe
the wrong volume; this catches that.

The oracle test classifies every BPB field up front. Fields marked strict must
be byte-identical to `mkfs.vfat`; anything else carries a documented reason, and
a difference that is *not* in the table fails the test. Divergence from the
reference implementation has to be argued for, not discovered later on a stick
that will not boot.

The read/write round-trip uses `mtools`, which operates directly on an image
file with no kernel driver and no privileges. The brief expected this test to
need a loop mount and to be skipped on most machines; `mtools` removes that
compromise and the round-trip runs everywhere. A real kernel mount is attempted
as well and skips cleanly when the host has no `vfat` module (as containers
usually do not).

### Acceptance criteria

| # | Criterion | Where |
|---|---|---|
| 1 | `fsck.vfat -n` clean at 64 MiB, 2 GiB, 8 GiB, 64 GiB | `FsckValidationTest` |
| 2 | `file` identifies DOS/MBR with a FAT32 partition | `FsckValidationTest` |
| 3 | Write, re-check, read back identical | `RoundTripTest` |
| 4 | Boot-sector diff against `mkfs.vfat` | `BootSectorOracleTest` |
| 5 | A 16 MiB image is rejected, not written | `FsckValidationTest` |

Images are sparse — the 64 GiB case costs about 17 MB on disk — and are left in
`jvm-test/build/images/` after a run so a failure can be examined by hand with
the same tools.

---

## Phase 1 — the Android layer

Only start this now that Phase 0 is green.

```kotlin
val devices = UsbMassStorageDevice.getMassStorageDevices(context)
// request permission via UsbManager.requestPermission + PendingIntent
device.init()
val blockDev = device.blockDevice   // libaums BlockDeviceDriver
```

The work is `LibaumsSectorDevice : SectorDevice` adapting that driver, plus the
permission flow, device picker, options UI and progress. Do not reimplement the
transport: `libaums` (Apache-2.0, `me.jahnen.libaums:core`) already does
Bulk-Only Transport over the USB Host API. Use its `BlockDeviceDriver` and
ignore everything above that layer.

`core` is ready for it:

- `Progress` reports a real phase and a real sector count, so the bar is never
  indeterminate. `plannedProgressSectors` includes the verification read-back,
  so the bar lands on exactly 100% instead of overrunning during the final
  phase. `Phase.ZERO_FAT` is essentially the entire wait on a large stick —
  name the phase in the UI or it will look stuck.
- `Progress.isCancelled` is polled between write batches and aborts with
  `FormatCancelledException`. An aborted format leaves the device unmountable;
  say so.
- `Formatter.plan` gives the confirmation screen its content without touching
  the device.
- `blockSize` is honoured throughout, so 4096-byte-sector readers work.

Still to do in the app layer, from the brief:

- Show vendor, product, serial and **capacity in GB** before any write.
- Type-to-confirm for anything over 64 GB — that size is far more likely to be
  someone's external SSD than a boot stick.
- Hold a partial `WakeLock` for the duration.
- Handle `getMassStorageDevices` returning empty (another app holds the
  interface, or Android has already mounted the drive) with a message telling
  the user to eject it in Files first.
- Manifest: `<uses-feature android:name="android.hardware.usb.host" />`.

## Phase 2 — the other filesystems

Do not hand-write exFAT, NTFS and ext4. `mke2fs`, `mkfs.exfat` and `mkfs.ntfs`
all format a regular file happily, with no root and no block device. Run the
real tool against a sparse image sized to the target partition, then stream that
image to the stick sector by sector, skipping runs of zeros. A freshly formatted
image is overwhelmingly zeros, so the transfer is a small fraction of nominal
size.

`PartitionExtractor` in `jvm-test` is already a zero-skipping sector copier in
the right shape to become that streamer.

GPT belongs here too: protective MBR, header at LBA 1, entry array, CRC32 over
header and entries, backup header at the last sector, backup array before it.
`PartitionScheme.GPT` currently throws with an explanation rather than silently
producing MBR — get the CRCs right or every tool rejects it.
