# GentleWake modification notice

GentleWake is based on **Lux Alarm 2.4.1** by Daniel Salmun, at upstream commit
`147ea5c4ce4ea416a1d02975754cc12496e73433`.

## Development status

This branch is a prototype and is **not wake-ready**. It adds a Korean
GentleWake preview, a shared wake-ramp model, profile settings, and app-managed
local audio import. The Android ramp currently begins only when the alarm fires;
it is not scheduled to start before the target wake time. Do not rely on this
prototype as the sole alarm for an important wake-up.

GentleWake bundles no custom wake sound. A user may import an audio document into
app-managed storage; when no usable import is available, playback falls back to
the device's default alarm sound.

## Copyright and license inventory

Files inherited from and modified after the upstream base retain Daniel
Salmun's Lux Alarm notice and identify the 2026 GentleWake modification. Files
created for GentleWake carry `Copyright (C) 2026 김은준` and
`SPDX-License-Identifier: GPL-3.0-or-later`. Generated Room schema JSON records
compiler output and is distributed under the repository's GPL terms.

The file-by-file Git provenance and rights classification is recorded in
[COPYRIGHT_INVENTORY.md](COPYRIGHT_INVENTORY.md). AI assistance is development
tooling only and is not treated as a copyright holder.

The Android source and modifications are available under GNU GPL version 3 or,
at your option, any later version. The complete license text is in `LICENSE`.

## Binary/source correspondence policy

A published GentleWake APK must be built from a clean, immutable source tag.
The release must name that tag and commit, publish the APK SHA-256, and retain
build instructions/toolchain inputs needed to reproduce or audit it. Untagged
local APKs in `artifacts/` are prototype build outputs, not releases, and must
not be described as corresponding to an official source tag.

Legacy Lux Alarm release provenance and known database evidence gaps are recorded
in [LEGACY_SCHEMA_PROVENANCE.md](LEGACY_SCHEMA_PROVENANCE.md).
