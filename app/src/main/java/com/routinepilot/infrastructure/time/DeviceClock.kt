package com.routinepilot.infrastructure.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Wall clock that reads the device's time zone on every call. A singleton that captured the zone at startup would
 * keep scheduling in the old zone after the user travels.
 */
class DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)

    override fun instant(): Instant = Instant.now()
}
