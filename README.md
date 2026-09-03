# GentleWake · 부드러운 기상

GentleWake is a 2026 GPLv3-or-later modification of
[Lux Alarm](https://github.com/salmundani/LuxAlarm) 2.4.1 by Daniel Salmun,
based at upstream commit
`147ea5c4ce4ea416a1d02975754cc12496e73433`. See [NOTICE.md](NOTICE.md) and
[LICENSE](LICENSE).

> **Prototype — not wake-ready.** The ramp currently starts when the alarm fires
> and reaches its configured maximum afterward; scheduling the gradual wake
> before the target time is not implemented. Do not use this prototype as your
> only alarm for an important wake-up.

The current slice adds a Korean gentle-wake screen, shared wake-ramp model,
profile settings, and app-managed local audio import. GentleWake bundles no
custom wake sound: users can import an audio document, and playback otherwise
falls back to the device's default alarm sound.

## Build from source

Use JDK 21 and an Android SDK compatible with the checked-in Gradle configuration:

```sh
./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APKs are written under `app/build/outputs/apk/debug/`. A published APK must
come from a clean immutable source tag; its release notes must identify the tag
and commit and publish the APK SHA-256. Untagged files under `artifacts/` are
prototype outputs, not official releases.

Database baseline evidence is in
[LEGACY_SCHEMA_PROVENANCE.md](LEGACY_SCHEMA_PROVENANCE.md), including both known
historical schemas that used SQLite `user_version=2`.

## Upstream Lux Alarm behavior

Lux Alarm is a light-sensitive alarm clock designed to get the user out of bed.
Its alarm can require a configured ambient-light threshold before dismissal.
GentleWake preserves that upstream base while prototyping a gradual light and
audio wake experience.

The badges below describe the **upstream Lux Alarm package**, not a GentleWake
release:

![F-Droid Version](https://img.shields.io/f-droid/v/com.dsalmun.luxalarm)
![Downloads last month](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Fmonthly%2Fcom.dsalmun.luxalarm.json&query=%24.total_downloads&logo=fdroid&label=Downloads%20last%20month)

[<img src="https://f-droid.org/badge/get-it-on.png"
    alt="Get upstream Lux Alarm on F-Droid"
    height="80">](https://f-droid.org/packages/com.dsalmun.luxalarm)
