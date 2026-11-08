package com.routinepilot.backend.db

import javax.sql.DataSource

/**
 * Applies the numbered SQL scripts in order. Each script runs in one transaction with its version row, and an advisory
 * lock keeps two instances starting together from racing. Scripts are never edited once released; add a new version.
 */
object Migrations {
    private const val LOCK_KEY = 727_101
    private val scripts = listOf(1 to "initial_schema")

    fun migrate(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.runScript("SELECT pg_advisory_xact_lock($LOCK_KEY)")
                connection.runScript(
                    "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                        "version INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                        "applied_at TIMESTAMPTZ NOT NULL DEFAULT now())",
                )
                val applied = connection.query("SELECT version FROM schema_migrations") { it.getInt("version") }.toSet()
                scripts.filter { (version, _) -> version !in applied }.forEach { (version, name) ->
                    connection.runScript(readScript("db/migrations/V${version}__$name.sql"))
                    connection.update("INSERT INTO schema_migrations (version, name) VALUES (?, ?)", version, name)
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun readScript(path: String): String =
        Migrations::class.java.classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Missing migration script $path")
}
