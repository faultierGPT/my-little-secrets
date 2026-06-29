package app.mls.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin

/** Thrown when a request body exceeds the configured ciphertext limit. Mapped to 413. */
class PayloadTooLargeException(message: String) : RuntimeException(message)

/** Authenticated user id, from the bearer principal. Only valid inside `authenticate("session")`. */
fun ApplicationCall.userId(): String = principal<UserIdPrincipal>()!!.name

/** Best-effort client IP for rate-limiting (honours a single proxy hop). */
fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
        ?: request.origin.remoteHost

/** Raw bearer token from the Authorization header, if present. */
fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()
