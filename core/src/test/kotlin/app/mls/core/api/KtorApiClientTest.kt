package app.mls.core.api

import app.mls.core.model.ErrorResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NotesResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KtorApiClientTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val jsonHeader = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `login stores the token and sends it on authenticated requests`() = runTest {
        val seenAuth = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seenAuth += request.headers[HttpHeaders.Authorization]
            when (request.url.encodedPath) {
                "/auth/login" -> respond(json.encodeToString(LoginResponse.serializer(), LoginResponse("tok-123", 999)), HttpStatusCode.OK, jsonHeader)
                "/notes" -> respond(json.encodeToString(NotesResponse.serializer(), NotesResponse(emptyList(), 5)), HttpStatusCode.OK, jsonHeader)
                else -> respond("{}", HttpStatusCode.OK, jsonHeader)
            }
        }
        val api = KtorApiClient("http://x", engine)
        api.login(LoginRequest("e", "ak"))
        assertEquals("tok-123", api.token)

        api.getNotes(0)
        assertEquals("Bearer tok-123", seenAuth.last())
    }

    @Test
    fun `a non-2xx response becomes a typed ApiException`() = runTest {
        val engine = MockEngine {
            respond(json.encodeToString(ErrorResponse.serializer(), ErrorResponse("nope", "rate_limited")), HttpStatusCode.TooManyRequests, jsonHeader)
        }
        val api = KtorApiClient("http://x", engine)
        val ex = assertThrows<ApiException> { api.loginParams("e@x") }
        assertEquals(429, ex.status)
        assertEquals("rate_limited", ex.code)
        assertTrue(ex.isRateLimited)
    }
}
