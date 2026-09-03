/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.lang.reflect.InvocationTargetException

/** Test-only access to the store's private transactional fault fixture. */
internal object WakeEventDispatchStoreFaultFixture {
    fun create(
        database: AlarmDatabase,
        onFault: (String) -> Unit,
    ): RoomWakeEventDispatchStore {
        val constructor =
            RoomWakeEventDispatchStore::class
                .java
                .getDeclaredConstructor(
                    AlarmDatabase::class.java,
                    Function1::class.java,
                )
        constructor.isAccessible = true
        return try {
            constructor.newInstance(database, onFault)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }
}
