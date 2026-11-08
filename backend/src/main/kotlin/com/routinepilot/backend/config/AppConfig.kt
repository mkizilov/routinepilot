package com.routinepilot.backend.config

import io.ktor.server.config.ApplicationConfig

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

data class RateRule(val limit: Int, val windowSeconds: Long)

data class RateLimitConfig(
    val authPerIp: RateRule,
    val loginPerAccount: RateRule,
    val apiPerSession: RateRule,
)

data class SyncConfig(
    val maxBatchChanges: Int,
    val maxPayloadBytes: Int,
    val maxBodyBytes: Long,
    val pageSize: Int,
)

data class AppConfig(
    val database: DatabaseConfig,
    val sessionDays: Long,
    val passwordIterations: Int,
    val requireHttps: Boolean,
    val trustForwardedHeaders: Boolean,
    val rateLimits: RateLimitConfig,
    val sync: SyncConfig,
) {
    companion object {
        private const val MIN_PASSWORD_ITERATIONS = 100_000
        private const val MAX_SESSION_DAYS = 90L

        /** Reads the `rp.*` block of application.conf and rejects values that would weaken security. */
        fun from(config: ApplicationConfig): AppConfig {
            fun int(path: String) = config.property(path).getString().toInt()
            fun long(path: String) = config.property(path).getString().toLong()
            fun flag(path: String) = config.property(path).getString().toBoolean()
            fun rule(prefix: String) = RateRule(int("$prefix.limit"), long("$prefix.windowSeconds"))

            val appConfig = AppConfig(
                database = DatabaseConfig(
                    url = config.property("rp.database.url").getString(),
                    user = config.property("rp.database.user").getString(),
                    password = config.property("rp.database.password").getString(),
                    maxPoolSize = int("rp.database.maxPoolSize"),
                ),
                sessionDays = long("rp.auth.sessionDays"),
                passwordIterations = int("rp.auth.passwordIterations"),
                requireHttps = flag("rp.security.requireHttps"),
                trustForwardedHeaders = flag("rp.security.trustForwardedHeaders"),
                rateLimits = RateLimitConfig(
                    authPerIp = rule("rp.rateLimit.authPerIp"),
                    loginPerAccount = rule("rp.rateLimit.loginPerAccount"),
                    apiPerSession = rule("rp.rateLimit.apiPerSession"),
                ),
                sync = SyncConfig(
                    maxBatchChanges = int("rp.sync.maxBatchChanges"),
                    maxPayloadBytes = int("rp.sync.maxPayloadBytes"),
                    maxBodyBytes = long("rp.sync.maxBodyBytes"),
                    pageSize = int("rp.sync.pageSize"),
                ),
            )
            require(appConfig.passwordIterations >= MIN_PASSWORD_ITERATIONS) {
                "rp.auth.passwordIterations must be at least $MIN_PASSWORD_ITERATIONS"
            }
            require(appConfig.sessionDays in 1..MAX_SESSION_DAYS) {
                "rp.auth.sessionDays must be between 1 and $MAX_SESSION_DAYS"
            }
            return appConfig
        }
    }
}
