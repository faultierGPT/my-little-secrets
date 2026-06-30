package app.mls.server

import app.mls.core.model.ErrorResponse
import app.mls.server.auth.Tokens
import app.mls.server.db.NoteRepository
import app.mls.server.db.UserRepository
import kotlinx.serialization.SerializationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.event.Level

fun Application.configureSerialization() {
    install(ContentNegotiation) { json(appJson) }
}

fun Application.configureSecurityHeaders(config: Config) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Cache-Control", "no-store")
        if (config.hstsEnabled) {
            header("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
        }
    }
}

/**
 * Logs ONLY method, path, and status code. Never request/response bodies, headers, or query
 * strings — those could carry ciphertext, tokens, emails, or authKeys (SECURITY.md §5).
 */
fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()?.value?.toString() ?: "-"
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<UserRepository.EmailTakenException> { call, _ ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse("email already registered", "email_taken"))
        }
        exception<PayloadTooLargeException> { call, e ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse(e.message ?: "payload too large", "payload_too_large"))
        }
        exception<NoteRepository.RevisionConflictException> { call, _ ->
            // Optimistic-concurrency miss: the client's base revision is stale. It re-pulls and retries.
            call.respond(HttpStatusCode.Conflict, ErrorResponse("revision conflict", "conflict"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad request", "bad_request"))
        }
        exception<SerializationException> { call, _ ->
            // Malformed JSON body (we decode manually after a size-capped read).
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad request", "bad_request"))
        }
        exception<IllegalArgumentException> { call, _ ->
            // Field validation (`require(...)`) failures — never echo the message back.
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request", "invalid"))
        }
        exception<Throwable> { call, cause ->
            // Log the type only — never the message/body, which may contain request data.
            call.application.log.error("Unhandled ${cause::class.simpleName} on ${call.request.path()}")
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error", "internal"))
        }
    }
}

fun Application.configureCors(config: Config) {
    if (config.corsHosts.isEmpty()) return // native clients don't need CORS; default-deny browsers
    install(CORS) {
        config.corsHosts.forEach { allowHost(it, schemes = listOf("https")) }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }
}

fun Application.configureAuth(deps: AppDeps) {
    install(Authentication) {
        bearer("session") {
            authenticate { credential ->
                val userId = deps.sessions.findValidUserId(Tokens.hash(credential.token), deps.now())
                userId?.let { UserIdPrincipal(it) }
            }
        }
    }
}
