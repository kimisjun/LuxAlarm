/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

enum class WakeDismissal {
    CONFIRM,
    LUX;

    companion object {
        val DEFAULT = CONFIRM
    }
}
