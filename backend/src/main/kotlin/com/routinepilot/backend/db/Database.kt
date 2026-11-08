package com.routinepilot.backend.db

import com.routinepilot.backend.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

fun createDataSource(config: DatabaseConfig): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        maximumPoolSize = config.maxPoolSize
        isAutoCommit = false
        poolName = "routinepilot-backend"
    },
)

/**
 * Runs each block in one transaction on the IO dispatcher. A block that throws rolls everything back, including
 * failed business checks, so a rejected login never leaves a session row behind.
 */
class Database(private val dataSource: DataSource) {
    suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }
}
