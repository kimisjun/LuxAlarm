# Legacy database schema provenance

Copyright (C) 2026 김은준  
SPDX-License-Identifier: GPL-3.0-or-later

This ledger freezes every release tag visible in upstream Git plus the known
unreleased `user_version=2` collision. Each row traces one source/release state
through versionCode, commit, database version, Room identity, normalized schema
fingerprint, release APK and migration fixture. `unavailable` is intentional: no
evidence is invented.

## Release-by-release ledger

| Tag / evidence | versionCode | Exact commit | user_version | Room identity hash | Normalized schema fingerprint (SHA-256) | Official APK SHA-256 | Fixture |
|---|---:|---|---:|---|---|---|---|
| v1.0 | 1 | `7a8a539be413383e681ba5584d78de407ffefc1b` | 1 | `bfe6f39b547c7a6fe84d5b02f2c5abe9` (reconstructed from tagged source; no release APK available) | `b2242d2327b56b8b9c28f0186d9dbc738fa90fe8456ec3a69f66c793272b6d9d` | unavailable | v1-alarms |
| v1.0.1 | 2 | `8e8326ea02c02fefa669172b5eb4c4cbc0cf7477` | 1 | `bfe6f39b547c7a6fe84d5b02f2c5abe9` | `b2242d2327b56b8b9c28f0186d9dbc738fa90fe8456ec3a69f66c793272b6d9d` | `1a218c28dc388caa3f58cccf7cc07f8cacfe6f03ee794466a4d70b60606d5522` | v1-alarms |
| v1.0.2 | 3 | `f89cc0ba26cba8d406432eebc6013200ca02f657` | 1 | `bfe6f39b547c7a6fe84d5b02f2c5abe9` | `b2242d2327b56b8b9c28f0186d9dbc738fa90fe8456ec3a69f66c793272b6d9d` | `429da681e5e5a4eba566b3dcd7babeab5075bc9425a27369b3b0eceb7c75792b` | v1-alarms |
| v1.1.0 | 4 | `a6409dcb844648113e52353891fe83152604e4b4` | 1 | `bfe6f39b547c7a6fe84d5b02f2c5abe9` | `b2242d2327b56b8b9c28f0186d9dbc738fa90fe8456ec3a69f66c793272b6d9d` | `8924ab828e81712c3381053665fd7aa59361c14694323a010880d8b113261f47` | v1-alarms |
| unreleased DB-v2 collision | 4 | `2319a135b5fe35e38309c4f20972ca713996486a` | 2 | `945082520722243a5f8909570c86a7e6` (generated from immutable source commit) | `1890170f8f4cd36022479e2b2b8aba2443a28e4528048a79e978dccca6c9a618` | unavailable | v2-ringing-state |
| v2.0.0 | 5 | `0a8cd8cfe4f1786d1e5075ff8438f397d2947140` | 1 | `bfe6f39b547c7a6fe84d5b02f2c5abe9` | `b2242d2327b56b8b9c28f0186d9dbc738fa90fe8456ec3a69f66c793272b6d9d` | `e144c1f77a18bbf0258cfb20eaabc9992d42b4249c7814febacee6c05053b9df` | v1-alarms |
| v2.0.1 | 6 | `0380d1e6104dd9d50ceb46d57f8841bd128c53f7` | 1 | `bfe6f39b547c7a6fe84d5b02f2c5abe9` | `b2242d2327b56b8b9c28f0186d9dbc738fa90fe8456ec3a69f66c793272b6d9d` | `ff9e8dbdc233aa0868ba15ff41e9d1a87636228dd93e7f9c5c44c42557d58879` | v1-alarms |
| v2.1.0 | 7 | `975a924ff54cffaff5c9800f0d727fc5cc019e19` | 2 | `9819c15e4402b0be9853ebb8602904f3` | `6df37eeca1f57dc8ccc7fbc4faec73b444869ab62dbbedd8f36cf722deb1cf7a` | `2f0a9251e4ba18cb99b3c1a3b6931a15103cb939d2bd613772cd5f5b5a3b5d57` | v2-ringtone |
| v2.2.0 | 8 | `5c7dbd01cf03780328854159094b42965a277de2` | 3 | `5c4a2ac04d88cccd72864d05e547b91f` | `a9c64229db194101144da4c50d9e49c5fdad2f7283d47b02c40ea29d891d85e9` | `9af236a09e18b5bbd146f7d01b77dc04e571d6a14a7071639a316d70213f0cc7` | v3-volume-vibration |
| v2.2.1 | 9 | `bdf51d7b4031a607427fa797b24412d331d1ae61` | 3 | `5c4a2ac04d88cccd72864d05e547b91f` | `a9c64229db194101144da4c50d9e49c5fdad2f7283d47b02c40ea29d891d85e9` | `3499ca52805e551cbadde60bec909133823550412ee98fc781ac9c360c88a9f5` | v3-volume-vibration |
| v2.2.2 | 10 | `69388806dbb902f9ac7249c8ad926186e6f0a9db` | 3 | `5c4a2ac04d88cccd72864d05e547b91f` | `a9c64229db194101144da4c50d9e49c5fdad2f7283d47b02c40ea29d891d85e9` | `d1d63df44c8bcc0789cd6eef9a93f5a6105a685ce1119c99f51bef7fd088c772` | v3-volume-vibration |
| v2.2.3 | 11 | `5e88e8e1b7601345e409f027a300af61c82d0c5d` | 3 | `5c4a2ac04d88cccd72864d05e547b91f` | `a9c64229db194101144da4c50d9e49c5fdad2f7283d47b02c40ea29d891d85e9` | `3c3bb865928d27115ed49d4f569419de7c155e42e9206dac38d106d05e2a3225` | v3-volume-vibration |
| v2.3.0 | 12 | `ce3cbd9743c10f42f6ebcd9b3c216947209c73e1` | 4 | `e3d36cf2832ffaf85f0b6c2355715106` | `92e84532900dcd9ec0205f50bad7911bc97cf3eb4596ccaea78ca0abe35adf76` | `56390be2996118e61ee657f2514007242a5c23b82fc8026e2671a07b2c69b5fb` | v4-skipped-occurrence |
| v2.4.0 | 13 | `4f553215088916697df80d239c5c7670558123aa` | 5 | `f1d9f0ed5f09fa336a662262656728b9` | `305863a73c0f94b2833b4997f1cfc85b5852960b54a13b40939a3b76f9d484a0` (exported Room JSON) | `238905889f8a0053e242932d001b8e77e87d2e5a0c2a340ce1c60979500b1d27` | v5-room-export |
| v2.4.1 | 14 | `147ea5c4ce4ea416a1d02975754cc12496e73433` | 5 | `f1d9f0ed5f09fa336a662262656728b9` | `305863a73c0f94b2833b4997f1cfc85b5852960b54a13b40939a3b76f9d484a0` (exported Room JSON) | `ecd3943e6406fc574400817dcbf7c9460c58cfb35fa5f6e7e02f77ca9f753911` | v5-room-export |

`app/schemas/legacy/manifest.tsv` records each available fixture's SHA-256
and the exact instrumented function in `MigrationTest.kt`. The colliding v2
shape is deliberately frozen as unsupported: it reused version 2 for a schema
without `ringtoneUri`, so the registered 2→3 migration cannot safely apply.

## Evidence method and limits

Official APK assets were downloaded from the matching
`salmundani/LuxAlarm` GitHub releases on 2026-09-03. Room identity hashes were
read from `AlarmDatabase_Impl` constructor/`room_master_table` statements; the
first constructor hash is the current identity and the second is Room's legacy
hash. Schema fingerprints are SHA-256 of the committed normalized SQL fixture,
except v5, which hashes the committed Room `5.json`. The v1.0 identity and the
short-lived v2 collision identity were reconstructed by generating Room code
from their immutable source commits because corresponding release APK evidence
was unavailable. No database from a real user device was used, and this ledger
cannot prove APKs that were distributed outside the visible official releases.
