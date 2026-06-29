package app.mls.server

import app.mls.server.db.Db
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val config = Config.fromEnv()
    val db = Db.create(config.dbUrl, config.dbUser, config.dbPassword, config.dbDriver)
    db.initSchema()
    val deps = AppDeps.create(config, db)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(deps)
    }.start(wait = true)
}

/** Wires plugins and routes. Public so tests can mount the same module on the Ktor test host. */
fun Application.module(deps: AppDeps) {
    configureSerialization()
    configureSecurityHeaders(deps.config)
    configureCallLogging()
    configureStatusPages()
    configureCors(deps.config)
    configureAuth(deps)
    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(deps)
        authenticate("session") {
            authedAuthRoutes(deps)
            accountRoutes(deps)
            noteRoutes(deps)
        }
    }
}
