-- Copyright (C) 2026 김은준
-- SPDX-License-Identifier: GPL-3.0-or-later
-- Frozen from upstream commit 2319a135; no surviving official release APK was found.
PRAGMA user_version = 2;
CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `repeatDays` TEXT NOT NULL);
CREATE TABLE ringing_alarm (id INTEGER NOT NULL PRIMARY KEY, hour INTEGER NOT NULL, minute INTEGER NOT NULL);
