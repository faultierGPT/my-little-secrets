package app.mls.server

import app.mls.server.auth.RateLimiter
import app.mls.server.db.Db
import app.mls.server.db.NoteRepository
import app.mls.server.db.SessionRepository
import app.mls.server.db.UserRepository
import kotlinx.serialization.json.Json

/** Shared JSON config. `ignoreUnknownKeys` gives forward-compat; `encodeDefaults` keeps wire stable. */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Everything the route handlers need, wired once. `now` is injectable so tests control the clock. */
class AppDeps(
    val config: Config,
    val db: Db,
    val users: UserRepository,
    val notes: NoteRepository,
    val sessions: SessionRepository,
    val loginLimiter: RateLimiter,
    val now: () -> Long,
) {
    companion object {
        fun create(config: Config, db: Db, now: () -> Long = System::currentTimeMillis): AppDeps =
            AppDeps(
                config = config,
                db = db,
                users = UserRepository(db),
                notes = NoteRepository(db),
                sessions = SessionRepository(db),
                loginLimiter = RateLimiter(config.loginMaxAttempts, config.loginLockoutSeconds * 1000, now),
                now = now,
            )
    }
}
