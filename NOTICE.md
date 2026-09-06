# Warmly modification notice

Warmly is based on Lux Alarm 2.4.1 by Daniel Salmun, upstream commit
`147ea5c4ce4ea416a1d02975754cc12496e73433`.

The 2026 Warmly modifications provide a renamed and separately identified
Android application, a localized single-sleep-plan onboarding flow,
duration-based bedtime suggestions, direct bedtime selection, Room persistence,
and a local wake-playlist editor with content-addressed app-owned audio copies
and sequential preview playback with default-sound fallback. Legacy multi-alarm
boot and time-change rescheduling entry points remain disabled while the Warmly
single-plan scheduler is not yet implemented.

The current implementation does not claim to schedule or run alarms, execute a
foreground wake ramp, request final alarm permissions, recover alarm schedules
after reboot or time-zone changes, or have passed a physical-device canary or
signed-release process. Those remain later implementation and verification work.

Lux Alarm source and Warmly modifications are distributed under the GNU General
Public License, version 3 or (at your option) any later version. The complete
license text is in `LICENSE`.
