package app.mls.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveStream
import java.io.ByteArrayOutputStream

/** Thrown when a request body exceeds the configured size limit. Mapped to 413. */
class PayloadTooLargeException(message: String) : RuntimeException(message)

/** Authenticated user id, from the bearer principal. Only valid inside `authenticate("session")`. */
fun ApplicationCall.userId(): String = principal<UserIdPrincipal>()!!.name

/**
 * Client IP for rate-limiting. SECURITY: `X-Forwarded-For` is client-controlled, so trusting it on a
 * directly-exposed server lets an attacker mint a fresh rate-limit key per request and defeat the
 * throttle. We therefore use the real socket peer ([origin].remoteHost) by DEFAULT and only consult
 * XFF when the operator declares how many trusted reverse proxies sit in front ([trustedHops] > 0).
 * In that case the rightmost `trustedHops` entries were appended by our own infrastructure, so the
 * client IP is the entry just to their left; anything further left is attacker-spoofable and ignored.
 */
fun ApplicationCall.clientIp(trustedHops: Int): String {
    val remote = request.origin.remoteHost
    if (trustedHops <= 0) return remote
    val xff = request.headers["X-Forwarded-For"]
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: return remote
    // Take the hop our outermost trusted proxy observed; fall back to the socket peer if the client
    // supplied fewer hops than configured (i.e. it can't be trusted).
    return xff.getOrNull(xff.size - trustedHops) ?: remote
}

/** Raw bearer token from the Authorization header, if present. */
fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()

/**
 * Read the request body as text, enforcing [maxBytes] INDEPENDENTLY of the client-supplied
 * Content-Length (counting bytes as they arrive), so a chunked or lying upload can't exhaust memory
 * before validation runs. Throws [PayloadTooLargeException] (→ 413) the moment the cap is crossed.
 */
suspend fun ApplicationCall.receiveCappedText(maxBytes: Long): String {
    request.contentLength()?.let { if (it > maxBytes) throw PayloadTooLargeException("request body too large") }
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0L
    val input = receiveStream()
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw PayloadTooLargeException("request body too large")
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8)
}
