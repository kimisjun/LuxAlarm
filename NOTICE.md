# Warmly modification notice

Warmly is based on Lux Alarm 2.4.1 by Daniel Salmun, upstream commit
`147ea5c4ce4ea416a1d02975754cc12496e73433`.

The 2026 Warmly modifications in the current first slice provide a renamed and
separately identified Android application, a localized single-sleep-plan
onboarding flow, duration-based bedtime suggestions, direct bedtime selection,
and Room persistence with a saved-plan home summary. Legacy multi-alarm boot and
time-change rescheduling entry points are disabled while the Warmly single-plan
scheduler is not yet implemented.

The current slice does not claim to schedule or run alarms, import or play a
wake playlist, request final alarm permissions, or survive reboot as a working
wake routine. Those remain later implementation and device-canary work.

Lux Alarm source and Warmly modifications are distributed under the GNU General
Public License, version 3 or (at your option) any later version. The complete
license text is in `LICENSE`.
