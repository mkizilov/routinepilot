package com.routinepilot.backend.db

import com.routinepilot.backend.config.DatabaseConfig
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A disposable PostgreSQL 16 started with Testcontainers, which needs a reachable Docker daemon. Without one, the first
 * test that touches the database fails with the Testcontainers error. Nothing is skipped silently.
 */
object TestPostgres {
    private class Container : PostgreSQLContainer<Container>(DockerImageName.parse("postgres:16-alpine"))

    private val container: Container by lazy { Container().also { it.start() } }

    val databaseConfig: DatabaseConfig by lazy {
        DatabaseConfig(
            url = container.jdbcUrl,
            user = container.username,
            password = container.password,
            maxPoolSize = 5,
        )
    }

    private val resetSource by lazy { createDataSource(databaseConfig) }

    /** Migrates, then empties the data tables. Schema stays, so the migrations run once per container. */
    fun reset() {
        Migrations.migrate(resetSource)
        resetSource.connection.use { connection ->
            connection.runScript(
                "TRUNCATE users, devices, sessions, sync_entities, sync_changes RESTART IDENTITY CASCADE",
            )
            connection.commit()
        }
    }
}
