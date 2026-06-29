package app.mls.server

import app.mls.core.crypto.AuthVerifier
import app.mls.core.crypto.B64
import app.mls.core.crypto.KdfParams
import app.mls.core.model.AccountKeyResponse
import app.mls.core.model.EncryptedBlob
import app.mls.core.model.ErrorResponse
import app.mls.core.model.LoginParamsRequest
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NotesResponse
import app.mls.core.model.PasswordChangeRequest
import app.mls.core.model.PutNoteRequest
import app.mls.core.model.RegisterRequest
import app.mls.server.auth.Tokens
import app.mls.server.db.UserRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

private val NOTE_ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

fun Route.authRoutes(deps: AppDeps) = route("/auth") {

    post("/register") {
        val req = call.receive<RegisterRequest>()
        validateRegister(req, deps.config)
        val email = req.email.trim().lowercase()

        // Convert authKey -> Argon2id verifier immediately; never store the authKey itself.
        val authKeyBytes = B64.decode(req.authKey)
        val verifier = AuthVerifier.create(authKeyBytes)
        java.util.Arrays.fill(authKeyBytes, 0)

        deps.users.insert(
            UserRecord(
                id = UUID.randomUUID().toString(),
                email = email,
                salt = req.salt,
                kdfParamsJson = appJson.encodeToString(KdfParams.serializer(), req.kdfParams),
                authVerifier = verifier,
                wrappedAccountKey = appJson.encodeToString(EncryptedBlob.serializer(), req.wrappedAccountKey),
                wrappedAccountKeyRecovery = req.wrappedAccountKeyRecovery
                    ?.let { appJson.encodeToString(EncryptedBlob.serializer(), it) },
                schemeVersion = req.schemeVersion,
                createdAt = deps.now(),
            ),
        )
        call.respond(HttpStatusCode.Created)
    }

    post("/login/params") {
        val req = call.receive<LoginParamsRequest>()
        val email = req.email.trim().lowercase()
        val rlKey = "params|$email|${call.clientIp()}"
        if (!deps.loginLimiter.checkAllowed(rlKey)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many requests", "rate_limited"))
        }
        val user = deps.users.findByEmail(email)
        if (user == null) {
            deps.loginLimiter.recordFailure(rlKey)
            // Email existence is not a protected secret (SECURITY.md §2.3); respond plainly.
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account", "not_found"))
        }
        call.respond(LoginParamsResponse(user.salt, appJson.decodeFromString(KdfParams.serializer(), user.kdfParamsJson)))
    }

    post("/login") {
        val req = call.receive<LoginRequest>()
        val email = req.email.trim().lowercase()
        val rlKey = "login|$email|${call.clientIp()}"
        if (!deps.loginLimiter.checkAllowed(rlKey)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many requests", "rate_limited"))
        }
        val user = deps.users.findByEmail(email)
        val authKeyBytes = B64.decode(req.authKey)
        val ok = user != null && AuthVerifier.verify(user.authVerifier, authKeyBytes)
        java.util.Arrays.fill(authKeyBytes, 0)
        if (!ok) {
            deps.loginLimiter.recordFailure(rlKey)
            return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", "unauthorized"))
        }
        deps.loginLimiter.recordSuccess(rlKey)
        val raw = Tokens.newRawToken()
        val expiresAt = deps.now() + deps.config.tokenTtlSeconds * 1000
        deps.sessions.create(Tokens.hash(raw), user.id, expiresAt, deps.now())
        call.respond(LoginResponse(raw, expiresAt))
    }
}

/** Logout lives under bearer auth; it deletes the presented session. */
fun Route.authedAuthRoutes(deps: AppDeps) = route("/auth") {
    post("/logout") {
        call.bearerToken()?.let { deps.sessions.delete(Tokens.hash(it)) }
        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.accountRoutes(deps: AppDeps) {
    get("/account/key") {
        val user = deps.users.findById(call.userId())
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found", "not_found"))
        call.respond(
            AccountKeyResponse(
                wrappedAccountKey = appJson.decodeFromString(EncryptedBlob.serializer(), user.wrappedAccountKey),
                wrappedAccountKeyRecovery = user.wrappedAccountKeyRecovery
                    ?.let { appJson.decodeFromString(EncryptedBlob.serializer(), it) },
                schemeVersion = user.schemeVersion,
            ),
        )
    }

    post("/account/password") {
        val userId = call.userId()
        val req = call.receive<PasswordChangeRequest>()
        val user = deps.users.findById(userId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not found", "not_found"))

        // Re-verify the CURRENT credential before allowing a change.
        val cur = B64.decode(req.currentAuthKey)
        val authorized = AuthVerifier.verify(user.authVerifier, cur)
        java.util.Arrays.fill(cur, 0)
        if (!authorized) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("current credential invalid", "forbidden"))
        }

        val newAuth = B64.decode(req.newAuthKey)
        val newVerifier = AuthVerifier.create(newAuth)
        java.util.Arrays.fill(newAuth, 0)
        deps.users.updateCredentials(
            userId = userId,
            salt = req.newSalt,
            kdfParamsJson = appJson.encodeToString(KdfParams.serializer(), req.newKdfParams),
            authVerifier = newVerifier,
            wrappedAccountKey = appJson.encodeToString(EncryptedBlob.serializer(), req.newWrappedAccountKey),
        )
        // Re-wrap done; invalidate ALL existing sessions (SECURITY.md §5.2).
        deps.sessions.deleteAllForUser(userId)
        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.noteRoutes(deps: AppDeps) {
    get("/notes") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val serverTime = deps.now()
        val notes = deps.notes.listSince(call.userId(), since)
        call.respond(NotesResponse(notes, serverTime))
    }

    put("/notes/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!NOTE_ID_RE.matches(id)) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid note id", "bad_id"))
        }
        // Reject oversized payloads before reading the whole body.
        val limit = deps.config.maxNoteCiphertextBytes * 2 + 4096
        call.request.contentLength()?.let { if (it > limit) throw PayloadTooLargeException("note too large") }

        val req = call.receive<PutNoteRequest>()
        if (req.ciphertext.length > limit) throw PayloadTooLargeException("note too large")

        val dto = deps.notes.upsert(call.userId(), id, req.ciphertext, req.nonce, req.schemeVersion, deps.now())
        call.respond(dto)
    }

    delete("/notes/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!NOTE_ID_RE.matches(id)) {
            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid note id", "bad_id"))
        }
        val dto = deps.notes.softDelete(call.userId(), id, deps.now())
            ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("not found", "not_found"))
        call.respond(dto)
    }
}

private fun validateRegister(req: RegisterRequest, config: Config) {
    require(req.email.length in 3..320 && req.email.contains('@')) { "invalid email" }
    require(req.salt.length <= 64) { "salt too long" }
    require(req.authKey.length <= 256) { "authKey too long" }
    require(req.wrappedAccountKey.ciphertext.length <= 4096) { "wrapped key too long" }
    require((req.wrappedAccountKeyRecovery?.ciphertext?.length ?: 0) <= 4096) { "recovery key too long" }
}
