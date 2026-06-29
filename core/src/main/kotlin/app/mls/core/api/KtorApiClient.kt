package app.mls.core.api

import app.mls.core.model.AccountKeyResponse
import app.mls.core.model.ErrorResponse
import app.mls.core.model.LoginParamsRequest
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NoteDto
import app.mls.core.model.NotesResponse
import app.mls.core.model.PasswordChangeRequest
import app.mls.core.model.PutNoteRequest
import app.mls.core.model.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Ktor-based [MlsApi]. The HTTP engine is injectable (defaults to CIO) so Android can supply
 * OkHttp and tests can supply a MockEngine. [baseUrl] must NOT have a trailing slash.
 *
 * Optional TLS certificate pinning (SECURITY.md) is configured on the injected engine by the
 * platform layer; this class stays transport-agnostic.
 */
class KtorApiClient(
    private val baseUrl: String,
    engine: HttpClientEngine = CIO.create(),
    override var token: String? = null,
) : MlsApi, AutoCloseable {

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    private fun HttpRequestBuilder.auth() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    override suspend fun register(req: RegisterRequest) {
        client.post("$baseUrl/auth/register") { contentType(ContentType.Application.Json); setBody(req) }.ensureSuccess()
    }

    override suspend fun loginParams(email: String): LoginParamsResponse =
        client.post("$baseUrl/auth/login/params") {
            contentType(ContentType.Application.Json); setBody(LoginParamsRequest(email))
        }.ensureSuccess().body()

    override suspend fun login(req: LoginRequest): LoginResponse {
        val resp = client.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json); setBody(req)
        }.ensureSuccess().body<LoginResponse>()
        token = resp.token
        return resp
    }

    override suspend fun logout() {
        client.post("$baseUrl/auth/logout") { auth() }.ensureSuccess()
        token = null
    }

    override suspend fun getAccountKey(): AccountKeyResponse =
        client.get("$baseUrl/account/key") { auth() }.ensureSuccess().body()

    override suspend fun changePassword(req: PasswordChangeRequest) {
        client.post("$baseUrl/account/password") {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.ensureSuccess()
    }

    override suspend fun getNotes(since: Long): NotesResponse =
        client.get("$baseUrl/notes") { auth(); parameter("since", since) }.ensureSuccess().body()

    override suspend fun putNote(id: String, req: PutNoteRequest): NoteDto =
        client.put("$baseUrl/notes/$id") {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.ensureSuccess().body()

    override suspend fun deleteNote(id: String): NoteDto =
        client.delete("$baseUrl/notes/$id") { auth() }.ensureSuccess().body()

    override fun close() = client.close()

    private suspend fun HttpResponse.ensureSuccess(): HttpResponse {
        if (status.isSuccess()) return this
        val err = runCatching { body<ErrorResponse>() }.getOrNull()
        throw ApiException(status.value, err?.code, err?.error ?: "HTTP ${status.value}")
    }
}
