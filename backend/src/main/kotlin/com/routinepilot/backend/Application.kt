package com.routinepilot.backend

import com.routinepilot.backend.api.ApiJson
import com.routinepilot.backend.api.HealthResponse
import com.routinepilot.backend.api.configureAuthentication
import com.routinepilot.backend.api.configureErrors
import com.routinepilot.backend.api.transportSecurity
import com.routinepilot.backend.api.v1Routes
import com.routinepilot.backend.config.AppConfig
import com.routinepilot.backend.db.Database
import com.routinepilot.backend.db.Migrations
import com.routinepilot.backend.db.createDataSource
import com.routinepilot.backend.persistence.AccountStore
import com.routinepilot.backend.persistence.SyncStore
import com.routinepilot.backend.security.FixedWindowRateLimiter
import com.routinepilot.backend.security.PasswordHasher
import com.routinepilot.backend.service.AccountService
import com.routinepilot.backend.service.SyncService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Clock

fun main(args: Array<String>) = EngineMain.main(args)

/** Entry point named in application.conf. It reads the configuration from the environment. */
fun Application.module() = backendModule(AppConfig.from(environment.config))

/**
 * Builds the whole API. Tests call this directly with their own configuration. Migrations run before any request is
 * served, so a database that is not ready stops the process rather than failing requests later.
 */
fun Application.backendModule(config: AppConfig, clock: Clock = Clock.systemUTC()) {
    val dataSource = createDataSource(config.database)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    Migrations.migrate(dataSource)

    val db = Database(dataSource)
    val accounts = AccountService(db, AccountStore(), PasswordHasher(config.passwordIterations), config, clock)
    val sync = SyncService(db, SyncStore(), clock)
    val limiter = FixedWindowRateLimiter { clock.millis() }

    install(ContentNegotiation) { json(ApiJson) }
    install(transportSecurity(config.requireHttps, config.trustForwardedHeaders))
    configureErrors()
    configureAuthentication(accounts)

    routing {
        get("/health") { call.respond(HttpStatusCode.OK, HealthResponse("ok")) }
        v1Routes(accounts, sync, limiter, config)
    }
}
