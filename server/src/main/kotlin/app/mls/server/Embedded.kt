package app.mls.server

import app.mls.server.db.Db
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking

/**
 * Boots a fully-wired server in-process and returns a handle exposing the actually-bound port.
 *
 * This lets a JVM caller embed the server without going through `main()` + environment variables —
 * used by the desktop module's end-to-end integration test (real HTTP over loopback, no Docker), and
 * available for a future all-in-one bundle. The database is created and migrated from [config].
 *
 * Java-friendly: [start] / [h2Config] are `@JvmStatic`, the handle is [AutoCloseable].
 */
object Embedded {

    @JvmStatic
    fun start(config: Config): Handle {
        val db = Db.create(config.dbUrl, config.dbUser, config.dbPassword, config.dbDriver)
        db.initSchema()
        val deps = AppDeps.create(config, db)
        val server = embeddedServer(Netty, port = config.port, host = "127.0.0.1") { module(deps) }
        server.start(wait = false)
        val boundPort = runBlocking { server.engine.resolvedConnectors() }.first().port
        return Handle(boundPort) {
            runCatching { server.stop(100, 500) }
            runCatching { db.close() }
        }
    }

    /**
     * An in-memory H2 [Config] for embedding without PostgreSQL (identical SQL runs on both). HSTS is
     * off (plain loopback) and the login limiter is generous so a multi-login test isn't throttled.
     *
     * @param dbName unique in-memory database name (isolates concurrent embeds)
     * @param port   listen port, or 0 to bind an ephemeral one (read back via [Handle.port])
     */
    @JvmStatic
    @JvmOverloads
    fun h2Config(dbName: String, port: Int = 0): Config = Config(
        port = port,
        dbUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1",
        dbUser = "sa",
        dbPassword = "sa",
        dbDriver = "org.h2.Driver",
        tokenTtlSeconds = 3600,
        maxNoteCiphertextBytes = 1_048_576,
        hstsEnabled = false,
        corsHosts = emptyList(),
        loginMaxAttempts = 100,
        loginLockoutSeconds = 60,
        loginEmailMaxAttempts = 200,
    )

    /** Live server handle; [close] stops the server and closes the DB pool. */
    class Handle internal constructor(
        @JvmField val port: Int,
        private val stopFn: () -> Unit,
    ) : AutoCloseable {
        override fun close() = stopFn()
    }
}
