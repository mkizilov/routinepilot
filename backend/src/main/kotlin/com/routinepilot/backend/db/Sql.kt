package com.routinepilot.backend.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Plain JDBC helpers. Parameters are always bound, never concatenated into SQL. */
fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rows ->
            val result = mutableListOf<T>()
            while (rows.next()) result += map(rows)
            result
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeUpdate()
    }

/** For SQL without parameters: schema scripts and locks. */
fun Connection.runScript(sql: String) {
    createStatement().use { it.execute(sql) }
}

fun ResultSet.uuid(column: String): UUID = UUID.fromString(getString(column))

fun ResultSet.uuidOrNull(column: String): UUID? = getString(column)?.let(UUID::fromString)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.jsonObjectOrNull(column: String): JsonObject? =
    getString(column)?.let { Json.parseToJsonElement(it).jsonObject }

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> setNull(position, Types.NULL)
            is String -> setString(position, value)
            is Long -> setLong(position, value)
            is Int -> setInt(position, value)
            is Boolean -> setBoolean(position, value)
            is UUID -> setObject(position, value)
            is Instant -> setObject(position, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            else -> throw IllegalArgumentException("Unsupported SQL parameter type")
        }
    }
}
