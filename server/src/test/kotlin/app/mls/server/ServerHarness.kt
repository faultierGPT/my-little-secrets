package app.mls.server

import app.mls.core.crypto.B64
import app.mls.core.crypto.CryptoCore
import app.mls.core.model.LoginParamsRequest
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.server.db.Db
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * Mounts the real [module] on the Ktor test host backed by a fresh H2 database and a controllable
 * [TestClock]. The block runs with [ApplicationTestBuilder] as receiver, so `client` is in scope.
 */
fun runServer(
    config: Config = ServerTestSupport.testConfig(),
    block: suspend ApplicationTestBuilder.(db: Db, clock: TestClock) -> Unit,
) {
    val db = ServerTestSupport.freshH2Db()
    val clock = TestClock()
    try {
        testApplication {
            application { module(AppDeps.create(config, db, clock::now)) }
            block(db, clock)
        }
    } finally {
        db.close()
    }
}

/** Convenience: run the full login/params + login dance and return the bearer token. */
suspend fun ApplicationTestBuilder.login(password: String, email: String): String {
    val paramsText = client.post("/auth/login/params") {
        contentType(ContentType.Application.Json)
        setBody(appJson.encodeToString(LoginParamsRequest.serializer(), LoginParamsRequest(email)))
    }.bodyAsText()
    val params = appJson.decodeFromString(LoginParamsResponse.serializer(), paramsText)
    val authKey = CryptoCore.deriveAuthKeyForLogin(password.toByteArray(), B64.decode(params.salt), params.kdfParams)
    val loginText = client.post("/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(appJson.encodeToString(LoginRequest.serializer(), LoginRequest(email, B64.encode(authKey.copyBytes()))))
    }.bodyAsText()
    return appJson.decodeFromString(LoginResponse.serializer(), loginText).token
}
