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
    // Per-account (email-only) throttle: a proxy-independent backstop so a distributed/rotating-IP
    // attacker can't force unbounded Argon2id verifier work against one account. Higher than the
    // per-IP cap to keep targeted lockout (griefing) cost temporary, not permanent.
    val loginEmailMaxAttempts: Int = 50,
    // Application-level cap for auth/account request bodies, enforced regardless of Content-Length.
    val maxAuthBodyBytes: Long = 16_384,
    // Number of trusted reverse proxies in front of the server. 0 = directly exposed; ignore
    // X-Forwarded-For entirely and rate-limit on the real socket peer (see CallExt.clientIp).
    val trustedProxyHops: Int = 0,
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
                loginEmailMaxAttempts = get("MLS_LOGIN_EMAIL_MAX_ATTEMPTS", "50").toInt(),
                maxAuthBodyBytes = get("MLS_MAX_AUTH_BODY_BYTES", "16384").toLong(),
                trustedProxyHops = get("MLS_TRUSTED_PROXY_HOPS", "0").toInt(),
            )
        }
    }
}
