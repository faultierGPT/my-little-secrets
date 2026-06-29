package app.mls.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

enum class Dialect { POSTGRES, H2 }

/**
 * Thin JDBC layer over a HikariCP pool. Deliberately NOT an ORM: the schema is three tables of
 * opaque blobs + metadata, so plain typed SQL is the most robust and portable choice (identical
 * code runs on PostgreSQL in production and H2 in tests — no Docker needed to test).
 */
class Db(private val ds: HikariDataSource, val dialect: Dialect) : AutoCloseable {

    /** Run [block] in a transaction; commit on success, roll back on any throwable. */
    fun <T> tx(block: (Connection) -> T): T {
        ds.connection.use { c ->
            val prevAuto = c.autoCommit
            c.autoCommit = false
            try {
                val result = block(c)
                c.commit()
                return result
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            } finally {
                runCatching { c.autoCommit = prevAuto }
            }
        }
    }

    /** Idempotent schema creation. The only dialect difference is the large-text column type. */
    fun initSchema() {
        val bigText = if (dialect == Dialect.H2) "CLOB" else "TEXT"
        val ddl = listOf(
            """CREATE TABLE IF NOT EXISTS users (
                 id VARCHAR(36) PRIMARY KEY,
                 email VARCHAR(320) NOT NULL UNIQUE,
                 salt VARCHAR(64) NOT NULL,
                 kdf_params VARCHAR(512) NOT NULL,
                 auth_verifier VARCHAR(256) NOT NULL,
                 wrapped_account_key VARCHAR(4096) NOT NULL,
                 wrapped_account_key_recovery VARCHAR(4096),
                 scheme_version INT NOT NULL,
                 created_at BIGINT NOT NULL
               )""",
            """CREATE TABLE IF NOT EXISTS notes (
                 id VARCHAR(64) NOT NULL,
                 user_id VARCHAR(36) NOT NULL,
                 ciphertext $bigText NOT NULL,
                 nonce VARCHAR(64) NOT NULL,
                 scheme_version INT NOT NULL,
                 updated_at BIGINT NOT NULL,
                 deleted BOOLEAN NOT NULL DEFAULT FALSE,
                 revision BIGINT NOT NULL,
                 PRIMARY KEY (user_id, id),
                 CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users(id)
               )""",
            "CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at)",
            """CREATE TABLE IF NOT EXISTS sessions (
                 token_hash VARCHAR(64) PRIMARY KEY,
                 user_id VARCHAR(36) NOT NULL,
                 expires_at BIGINT NOT NULL,
                 created_at BIGINT NOT NULL,
                 CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
               )""",
            "CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)",
        )
        tx { c -> ddl.forEach { sql -> c.createStatement().use { it.executeUpdate(sql) } } }
    }

    override fun close() = ds.close()

    companion object {
        fun create(url: String, user: String, password: String, driver: String, poolSize: Int = 10): Db {
            val dialect =
                if (driver.contains("h2", ignoreCase = true) || url.startsWith("jdbc:h2")) Dialect.H2 else Dialect.POSTGRES
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                driverClassName = driver
                maximumPoolSize = poolSize
                poolName = "mls-pool"
            }
            return Db(HikariDataSource(cfg), dialect)
        }
    }
}
