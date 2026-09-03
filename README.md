# GentleWake · 부드러운 기상

GentleWake is a 2026 modification of Lux Alarm 2.4.1 by Daniel Salmun. The
Android code remains licensed under GNU GPLv3; see [LICENSE](LICENSE). Changes
in this branch add the Korean gentle-wake screen, shared wake-ramp model,
profile settings, and app-managed local audio import. The Android ramp currently
starts when the alarm fires and reaches its configured maximum afterward; it is
not yet scheduled to begin before the target alarm time.

![F-Droid Version](https://img.shields.io/f-droid/v/com.dsalmun.luxalarm)
![Downloads last month](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Fmonthly%2Fcom.dsalmun.luxalarm.json&query=%24.total_downloads&logo=fdroid&label=Downloads%20last%20month)

[<img src="https://f-droid.org/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/com.dsalmun.luxalarm)

The upstream **Lux Alarm** behavior is a light-sensitive alarm clock designed to ensure you get out of bed. The alarm remains active until it detects a specific level of ambient light in your room.

## How It Works

To disable the alarm, you must increase the room's brightness, either by opening your blinds or turning on a light. The app utilizes your phone's built-in **ambient light sensor** to measure the brightness level, preventing you from simply hitting "snooze" while remaining in the dark.

## Key Features

* **Light-Based Deactivation:** The alarm only stops once a pre-defined light threshold is met.
* **Adjustable Sensitivity:** Customize the required brightness level to account for different environments or weather conditions.
* **Modern Interface:** A clean, minimal UI built using **Material Design 3**.
