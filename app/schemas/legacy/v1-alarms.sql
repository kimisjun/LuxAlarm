-- Copyright (C) 2026 김은준
-- SPDX-License-Identifier: GPL-3.0-or-later
-- Frozen from the Room DDL in official Lux Alarm v1.x/v2.0.x release APKs.
PRAGMA user_version = 1;
CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `repeatDays` TEXT NOT NULL);
