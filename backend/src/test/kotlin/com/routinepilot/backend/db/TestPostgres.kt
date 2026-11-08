package com.routinepilot.backend.db

import com.routinepilot.backend.config.DatabaseConfig
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The PostgreSQL 16 the database and API tests run against.
 *
 * With RP_TEST_DATABASE_URL, RP_TEST_DATABASE_USER, and RP_TEST_DATABASE_PASSWORD set, the tests use that existing
 * server. Use this where Docker is not available, and point it at a disposable database. Otherwise a disposable
 * PostgreSQL 16 starts with Testcontainers, which needs a reachable Docker daemon. Without one, the first test that
 * touches the database fails with the Testcontainers error. Nothing is skipped silently.
 */
object TestPostgres {
    private class Container : PostgreSQLContainer<Container>(DockerImageName.parse("postgres:16-alpine"))

    private val external: DatabaseConfig? by lazy {
        System.getenv("RP_TEST_DATABASE_URL")?.let { url ->
            DatabaseConfig(
                url = url,
                user = requireNotNull(System.getenv("RP_TEST_DATABASE_USER")) { "RP_TEST_DATABASE_USER is required" },
                password = requireNotNull(System.getenv("RP_TEST_DATABASE_PASSWORD")) {
                    "RP_TEST_DATABASE_PASSWORD is required"
                },
                maxPoolSize = 5,
            )
        }
    }

    private val container: Container? by lazy {
        if (external != null) null else Container().also { it.start() }
    }

    val databaseConfig: DatabaseConfig by lazy {
        external ?: container!!.let {
            DatabaseConfig(
                url = it.jdbcUrl,
                user = it.username,
                password = it.password,
                maxPoolSize = 5,
            )
        }
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
