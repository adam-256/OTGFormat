# OTGFormat

An Android app that formats USB mass-storage devices over OTG, without root,
with full control of filesystem, cluster size, label, and partition table.

**Current state: Phase 0 complete and verified. Phase 1 written, with its
risky half verified and its UI not yet compiled** — see
[Phase 1](#phase-1--the-android-layer) for exactly which is which.

```
./gradlew test
```

111 tests. On a machine with `dosfstools`, `mtools` and `file` installed, that
formats images at 64 MiB, 2 GiB, 8 GiB and 64 GiB, validates every one with
`fsck.vfat`, diffs each boot sector field-by-field against `mkfs.vfat` and
prints the table, round-trips real files through each volume, and drives the
whole thing again through the real libaums stack.

The suite runs with no Android SDK present; the `:android` module is included
only where one exists (see `settings.gradle.kts`).

```
sudo apt-get install dosfstools mtools file
```

Without those tools the integration tests skip with a message naming the
missing package; the 41 pure-arithmetic tests still run.

---

## Why the code is split this way

Every bug in a filesystem writer is an off-by-one in a field offset, and every
bug in a USB adapter is a wrong unit or a wrong buffer. Debugging either through
*sideload → plug in a stick → observe failure* is agonisingly slow. Debugging
them against a file on disk, with `fsck.vfat` as an oracle, takes under a
second.

So both of those layers are plain Kotlin/JVM modules with no Android
dependencies, proven before anything goes near hardware. What is left in the
Android module is permission plumbing, a service and a screen — the parts that
genuinely cannot exist off-device, and the parts where a mistake shows up
immediately rather than silently corrupting a drive.

## Modules

| Module | Contents | Android-free | Verified |
|---|---|---|---|
| `core` | `SectorDevice`, `Mbr`, `Fat32Layout`, `Fat32Formatter`, `Formatter` | yes | yes |
| `usb` | `LibaumsSectorDevice`, `FormatPlanner`, `ConfirmationPolicy`, `UsbTarget` | yes | yes |
| `jvm-test` | `FileSectorDevice`, the oracle bridge, the acceptance suite | yes | — |
| `android` | permissions, foreground service, one-screen UI | no | **not compiled here** |

The dependency arrow points one way: `android` → `usb` → `core` → nothing.
`core` and `usb` have zero Android imports and must keep it that way. That is
not tidiness — it is the reason the risky code can be tested in milliseconds
instead of through a sideload-and-plug-in-a-stick cycle.

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

Split deliberately, on the same principle as Phase 0: everything where a bug
destroys a drive lives in `usb`, a plain JVM module with no Android imports,
and is tested here. Only the parts that cannot exist without an Android
runtime — permissions, the service, the screen — live in `android`.

### What is verified

`usb` is compiled against the **real** libaums interface. The AAR is fetched
from Maven Central and its `classes.jar` extracted by a Gradle task, because a
JVM module cannot consume an AAR directly. libaums' `BlockDeviceDriver` has no
Android types in its signature, so this works and the adapter is unit-tested
against fakes and against libaums' own `FileBlockDeviceDriver`.

`UsbPathValidationTest` in `jvm-test` then drives a complete format through
that real libaums driver and validates the result with `fsck.vfat`, at three
sizes, and checks the geometry `fsck` derives matches what `core` planned. It
also asserts that formatting through libaums and formatting straight to a file
produce **byte-identical** media. The only link in the Android I/O path that is
not covered is the USB transport itself.

### What libaums actually does, versus what the brief assumed

Four things had to be established from the artifact rather than assumed, and
all four change the code:

**There is no `device.blockDevice`.** `UsbMassStorageDevice` in 0.10.0 exposes
`partitions`, not a raw block device. More importantly its `init()` parses the
partition table and throws when it cannot — which is the normal state of the
blank, corrupt or half-formatted stick someone reaches for this app to fix. So
`UsbAccess` does not use that class at all: it finds the mass-storage interface
and endpoints itself and builds `ScsiBlockDevice` directly, which does only
INQUIRY and READ CAPACITY.

**`blocks` is off by one.** `BlockDeviceDriver.blocks` is documented as "the
block device size in blocks", and `FileBlockDeviceDriver` returns exactly that.
`ScsiBlockDevice` instead returns the SCSI READ CAPACITY(10) *last logical block
address*, one less. The two implementations disagree with each other and one
disagrees with the interface. Taking it at face value loses the final sector —
and the stale-signature wipe targets the last 33 sectors precisely because a
backup GPT header lives in the very last one, so an off-by-one would leave
behind the exact thing the wipe exists to remove.

Hard-coding `+ 1` would be worse: it over-runs any correct driver and breaks if
libaums is fixed. So `probeSectorCount` measures the boundary instead — read the
sector one past the reported count, and see whether it succeeds. Reads are
harmless, both conventions come out right, and where the result is ambiguous
the smaller figure wins, because under-reporting costs one sector while
over-reporting corrupts writes that fall off the end.

**Addressing is per-implementation.** The interface documentation says the
offset "can either be the amount of bytes or a logical block addressing" —
`ScsiBlockDevice` uses blocks, `ByteBlockDevice` and `Partition` use bytes and
are confined to one partition. Passing the wrong one would write at 1/512 of
every intended offset and could never reach sector 0. The type is checkable, so
`LibaumsSectorDevice.open` rejects a `ByteBlockDevice` outright.

**Buffers must be plain.** Every libaums transport reaches for
`buffer.array()`, and `UsbRequestCommunication` carries the comment "UsbRequest
.queue always reads at position 0". A buffer with a non-zero position or array
offset takes a different path in each implementation. The adapter therefore
hands libaums only buffers with position 0, limit equal to the transfer length,
and a backing array of exactly that size — the one shape all of them agree on.
A test asserts this on every transfer.

Two smaller ones: `createUsbCommunication` takes `(outEndpoint, inEndpoint)` in
that order, and the USB permission `PendingIntent` must be `FLAG_MUTABLE` or it
arrives with neither `EXTRA_DEVICE` nor `EXTRA_PERMISSION_GRANTED`.

### Safety behaviour

Everything §5.2 of the brief requires, with the decision logic in `usb` where
it is tested rather than in a dialog's conditional:

- **Identity before any write.** `UsbTarget.describe()` gives vendor, product,
  serial, USB ID and geometry. Capacity is shown in decimal GB, deliberately:
  a stick sold as "64GB" reports about 57 GiB, and a user asked to confirm
  "57.3 GiB" against a label reading 64GB cannot tell whether they picked the
  right device.
- **Type-to-confirm above 64 GB**, from `ConfirmationPolicy`. The threshold is
  decimal, chosen against what devices report rather than what they are sold
  as: a nominal 64GB stick reports ~61.5 GB and stays below the line, so
  routine boot-stick work is not buried under a ritual that trains people to
  dismiss it unread; a 128 GB stick or an external SSD lands above it. The
  phrase is the volume label when there is one, because typing it proves the
  user read the options they set.
- **Read-back verification** is already in `core` and applies unchanged.
- **A partial `WakeLock`**, plus a foreground service of type
  `connectedDevice`. The brief asks only for the wake lock, but a wake lock
  does not stop Android reclaiming the process when the user switches away, and
  a format killed mid-write leaves the same unmountable drive. The work
  therefore does not live in the Activity at all.
- **Real progress**, sectors written against sectors planned, with the phase
  named. On a large stick `ZERO_FAT` is essentially the whole wait; an
  unlabelled bar sitting at 4% for four minutes reads as a hang.
- **Cancellation** is polled between write batches and says plainly that the
  device is left unusable.
- **The empty-device case** tells the user to eject the drive in Files first.

`FormatPlanner` exists because `FormatOptions` validates the volume label in
its constructor and throws. A UI that builds options in a property getter read
during composition would turn a user typing a full stop into a crash, so every
throwing path is funnelled through the planner and comes back as a message.
That was a real bug in the first draft of the ViewModel, and it is now covered
by a test.

### What is *not* verified

**The `:android` module has never been compiled.** This environment has no
Android SDK and no route to Google's Maven repository, so neither the Android
Gradle Plugin nor the platform jars can be fetched. The module is written as
complete, review-ready source and excluded from the build until an SDK is
present.

Expect to fix ordinary build-time things on first compile — dependency
versions, an import, a Compose signature. What has been checked statically is
that every `core` and `usb` symbol the Android sources reference actually
exists in the compiled jars, since API drift between the modules is the failure
this repository can still catch.

Untestable without hardware, and worth attention on a first run: the USB
permission dialog, `forceClaim` against a drive Android has already mounted,
foreground-service behaviour on Android 14+, and real SCSI transfer sizes.

To build it:

```
export ANDROID_HOME=/path/to/android/sdk    # or set sdk.dir in local.properties
./gradlew :android:assembleDebug
```

`compileSdk`/`targetSdk` are 35 and should be raised for Android 17; `minSdk`
is 26, the floor for `startForegroundService` and notification channels.

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
