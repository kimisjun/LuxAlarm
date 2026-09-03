-- Copyright (C) 2026 김은준
-- SPDX-License-Identifier: GPL-3.0-or-later
-- Frozen from the Room DDL in the official Lux Alarm v2.3.0 release APK.
PRAGMA user_version = 4;
CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `repeatDays` TEXT NOT NULL, `ringtoneUri` TEXT, `volume` REAL, `vibrationEnabled` INTEGER NOT NULL, `skippedOccurrenceDay` INTEGER);
