package com.routinepilot.infrastructure.database

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class RoomConverters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    /** Stored as ISO text (yyyy-mm-dd), which sorts and compares correctly. */
    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    /** Stored as the zone ID, such as Europe/Berlin, which keeps the rule's daylight-saving behaviour. */
    @TypeConverter
    fun zoneIdToString(value: ZoneId?): String? = value?.id

    @TypeConverter
    fun stringToZoneId(value: String?): ZoneId? = value?.let(ZoneId::of)
}
