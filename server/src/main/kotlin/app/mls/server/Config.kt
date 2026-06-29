package app.mls.server

/**
 * Server configuration, entirely from environment variables (12-factor; no secrets baked into
 * the image). Defaults target local/self-host. See `docker-compose.yml` and `server/README.md`.
 */
data class Config(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val dbDriver: String,
    val tokenTtlSeconds: Long,
    val maxNoteCiphertextBytes: Long,
    val hstsEnabled: Boolean,
    val corsHosts: List<String>,
    val loginMaxAttempts: Int,
    val loginLockoutSeconds: Long,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): Config {
            fun get(k: String, default: String) = env(k)?.takeIf { it.isNotBlank() } ?: default
            return Config(
                port = get("MLS_PORT", "8080").toInt(),
                dbUrl = get("MLS_DB_URL", "jdbc:postgresql://localhost:5432/mls"),
                dbUser = get("MLS_DB_USER", "mls"),
                dbPassword = get("MLS_DB_PASSWORD", "mls"),
                dbDriver = get("MLS_DB_DRIVER", "org.postgresql.Driver"),
                tokenTtlSeconds = get("MLS_TOKEN_TTL_SECONDS", "3600").toLong(),
                maxNoteCiphertextBytes = get("MLS_MAX_NOTE_BYTES", "1048576").toLong(), // 1 MiB
                hstsEnabled = get("MLS_HSTS", "true").toBooleanStrict(),
                corsHosts = get("MLS_CORS_HOSTS", "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                loginMaxAttempts = get("MLS_LOGIN_MAX_ATTEMPTS", "10").toInt(),
                loginLockoutSeconds = get("MLS_LOGIN_LOCKOUT_SECONDS", "300").toLong(),
            )
        }
    }
}
