package app.mls.android.session

import app.mls.core.api.ApiException
import app.mls.core.api.KtorApiClient
import app.mls.core.crypto.B64
import app.mls.core.crypto.CryptoCore
import app.mls.core.crypto.RecoveryCode
import app.mls.core.crypto.Sealed
import app.mls.core.crypto.SecretBytes
import app.mls.core.crypto.KdfParams
import app.mls.core.model.EncryptedBlob
import app.mls.core.model.LoginRequest
import app.mls.core.model.NotePayload
import app.mls.core.model.RegisterRequest
import app.mls.core.store.EncryptedFileNoteStore
import app.mls.core.sync.SyncEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.CharBuffer
import java.util.Arrays
import java.util.Properties

/**
 * The Android session controller — the single point where Compose meets the vetted crypto/sync/API
 * core. Mirrors the desktop `Vault`, but coroutine-native (the core's suspend API is a natural fit,
 * so there is no blocking bridge). Heavy work runs off the main thread via [Dispatchers].
 *
 * Zero-knowledge invariants: the master password is converted to bytes, used, and wiped; only the
 * password-derived `authKey` and ciphertext ever leave; the account key lives only as wipeable
 * [SecretBytes]; the on-disk cache is encrypted.
 */
class AndroidVault(
    private val filesDir: File,
    val serverUrl: String,
    requestHeaders: Map<String, String> = emptyMap(),
) {

    private val api = KtorApiClient(serverUrl, OkHttp.create(), requestHeaders = requestHeaders)

    private var accountKey: SecretBytes? = null
    private var store: EncryptedFileNoteStore? = null
    private var engine: SyncEngine? = null
    private var email: String? = null

    private val cacheFile get() = File(filesDir, "cache.bin")
    private val profileFile get() = File(filesDir, "account.properties")

    val isUnlocked: Boolean get() = accountKey != null
    val isOnline: Boolean get() = api.token != null
    fun hasLocalAccount(): Boolean = profileFile.isFile
    fun rememberedEmail(): String = runCatching { loadProfile().email }.getOrDefault("")

    // ---------- account lifecycle ----------

    /** Create the account on the server, open the session, and return the one-time recovery code. */
    suspend fun register(email: String, password: CharArray): String? = withContext(Dispatchers.IO) {
        require(accountKey == null) { "already unlocked" }
        val pw = utf8(password)
        var authKey: SecretBytes? = null
        val material = try {
            CryptoCore.register(pw)
        } catch (e: Exception) {
            Arrays.fill(pw, 0); throw VaultException("Could not prepare your account keys.")
        }
        var adopted = false
        try {
            authKey = material.authKey
            val authB64 = B64.encode(authKey.bytes())
            try {
                api.register(
                    RegisterRequest(
                        email = email,
                        salt = B64.encode(material.salt),
                        kdfParams = material.kdfParams,
                        authKey = authB64,
                        wrappedAccountKey = material.wrappedAccountKey.toBlob(),
                        wrappedAccountKeyRecovery = material.wrappedAccountKeyRecovery?.toBlob(),
                    ),
                )
                api.login(LoginRequest(email, authB64))
            } catch (e: ApiException) {
                throw friendly("Registration failed", e)
            } catch (e: Exception) {
                throw unreachable()
            }
            this@AndroidVault.email = email
            accountKey = material.accountKey
            openSession()
            saveProfile(email, material.salt, material.kdfParams, material.wrappedAccountKey.toBlob())
            adopted = true
            val rc: RecoveryCode? = material.recoveryCode
            val shown = rc?.display()
            rc?.destroy()
            shown
        } finally {
            authKey?.destroy()
            Arrays.fill(pw, 0)
            if (!adopted) {
                material.accountKey.destroy()
                material.recoveryCode?.destroy()
            }
        }
    }

    /** Online sign-in: authenticate, fetch + unwrap the account key, open the session, cache profile. */
    suspend fun login(email: String, password: CharArray) = withContext(Dispatchers.IO) {
        require(accountKey == null) { "already unlocked" }
        val pw = utf8(password)
        var authKey: SecretBytes? = null
        var unlocked: SecretBytes? = null
        var adopted = false
        try {
            val params = try {
                api.loginParams(email)
            } catch (e: ApiException) {
                throw friendly("Sign-in failed", e)
            } catch (e: Exception) {
                throw unreachable()
            }
            val salt = B64.decode(params.salt)
            authKey = CryptoCore.deriveAuthKeyForLogin(pw, salt, params.kdfParams)
            try {
                api.login(LoginRequest(email, B64.encode(authKey.bytes())))
            } catch (e: ApiException) {
                throw friendly("Sign-in failed", e)
            } catch (e: Exception) {
                throw unreachable()
            }
            val keyResp = try {
                api.getAccountKey()
            } catch (e: ApiException) {
                throw friendly("Could not load your account key", e)
            } catch (e: Exception) {
                throw unreachable()
            }
            unlocked = try {
                CryptoCore.unlockWithPassword(pw, salt, params.kdfParams, Sealed.fromBlob(keyResp.wrappedAccountKey))
            } catch (e: Exception) {
                throw VaultException("Could not unlock your account key.")
            }
            this@AndroidVault.email = email
            accountKey = unlocked
            openSession()
            saveProfile(email, salt, params.kdfParams, keyResp.wrappedAccountKey)
            adopted = true
        } finally {
            authKey?.destroy()
            Arrays.fill(pw, 0)
            if (unlocked != null && !adopted) unlocked.destroy()
        }
    }

    /** Offline unlock from the cached (non-secret) profile. Local-only; sync stays disabled until login. */
    suspend fun unlockOffline(password: CharArray) = withContext(Dispatchers.Default) {
        require(accountKey == null) { "already unlocked" }
        if (!hasLocalAccount()) throw VaultException("No local account to unlock. Sign in online first.")
        val profile = loadProfile()
        val pw = utf8(password)
        var unlocked: SecretBytes? = null
        var adopted = false
        try {
            unlocked = try {
                CryptoCore.unlockWithPassword(pw, profile.salt, profile.kdfParams, Sealed.fromBlob(profile.wrapped))
            } catch (e: Exception) {
                throw VaultException("Wrong password.")
            }
            email = profile.email
            accountKey = unlocked
            openSession()
            adopted = true
        } finally {
            Arrays.fill(pw, 0)
            if (unlocked != null && !adopted) unlocked.destroy()
        }
    }

    /** Open the session directly from an account key recovered via biometric unlock (see [BiometricUnlock]). */
    fun unlockWithAccountKey(rememberedEmail: String, key: ByteArray) {
        require(accountKey == null) { "already unlocked" }
        email = rememberedEmail
        accountKey = SecretBytes.copyOf(key)
        openSession()
    }

    /** A copy of the unlocked account key, to seal under a Keystore-protected biometric key. Caller wipes. */
    fun accountKeyCopy(): ByteArray = (accountKey ?: error("locked")).copyBytes()

    fun lock() {
        engine = null
        store?.close()
        store = null
        accountKey?.destroy()
        accountKey = null
        api.token = null
        email = null
    }

    fun close() {
        lock()
        api.close()
    }

    // ---------- notes ----------

    fun notes(): List<NoteUi> {
        val e = engine ?: return emptyList()
        return e.list()
            .map { NoteUi(it.id, it.payload.title, it.payload.body, it.payload.tags, it.updatedAt, it.dirty) }
            .sortedByDescending { it.updatedAt }
    }

    fun saveNote(id: String?, title: String, body: String, tags: List<String>): String {
        val e = engine ?: throw VaultException("The vault is locked.")
        return e.save(NotePayload(title = title, body = body, tags = tags), id)
    }

    fun deleteNote(id: String) {
        engine?.delete(id)
    }

    // ---------- sync ----------

    suspend fun sync(): SyncSummary = withContext(Dispatchers.IO) {
        val e = engine ?: throw VaultException("The vault is locked.")
        if (api.token == null) throw VaultException("Sign in online to sync.")
        try {
            val r = e.sync()
            SyncSummary(r.pulled, r.pushed, r.deletedPushed, r.conflicts, r.keptBoth)
        } catch (ex: ApiException) {
            if (ex.isUnauthorized) {
                api.token = null
                throw VaultException("Your session expired. Sign in again to sync.")
            }
            throw friendly("Sync failed", ex)
        } catch (ex: Exception) {
            throw unreachable()
        }
    }

    // ---------- internals ----------

    private fun openSession() {
        val key = accountKey ?: error("no key")
        store = EncryptedFileNoteStore(cacheFile, key)
        engine = SyncEngine(api, store!!, key)
    }

    private class Profile(val email: String, val salt: ByteArray, val kdfParams: KdfParams, val wrapped: EncryptedBlob)

    private fun saveProfile(email: String, salt: ByteArray, kdf: KdfParams, wrapped: EncryptedBlob) {
        val p = Properties()
        p["email"] = email
        p["serverUrl"] = serverUrl
        p["salt"] = B64.encode(salt)
        p["kdf.algorithm"] = kdf.algorithm
        p["kdf.memLimitBytes"] = kdf.memLimitBytes.toString()
        p["kdf.opsLimit"] = kdf.opsLimit.toString()
        p["kdf.parallelism"] = kdf.parallelism.toString()
        p["wrap.ciphertext"] = wrapped.ciphertext
        p["wrap.nonce"] = wrapped.nonce
        p["wrap.schemeVersion"] = wrapped.schemeVersion.toString()
        profileFile.outputStream().use { p.store(it, "my-little-secrets account profile (non-secret)") }
    }

    private fun loadProfile(): Profile {
        val p = Properties()
        profileFile.inputStream().use { p.load(it) }
        val kdf = KdfParams(
            algorithm = p.getProperty("kdf.algorithm", "argon2id"),
            memLimitBytes = p.getProperty("kdf.memLimitBytes").toLong(),
            opsLimit = p.getProperty("kdf.opsLimit").toLong(),
            parallelism = p.getProperty("kdf.parallelism", "1").toInt(),
        )
        val wrapped = EncryptedBlob(
            p.getProperty("wrap.ciphertext"),
            p.getProperty("wrap.nonce"),
            p.getProperty("wrap.schemeVersion", "1").toInt(),
        )
        return Profile(p.getProperty("email", ""), B64.decode(p.getProperty("salt")), kdf, wrapped)
    }

    private fun unreachable() =
        VaultException("Could not reach the server. Check your connection and the server URL.")

    private fun friendly(prefix: String, e: ApiException): VaultException {
        val detail = when (e.code) {
            "email_taken" -> "that email is already registered."
            "unauthorized" -> "incorrect email or password."
            "rate_limited" -> "too many attempts — wait a moment and try again."
            "not_found" -> "no account found for that email."
            "forbidden" -> "current password is incorrect."
            "conflict" -> "a newer version exists on the server."
            else -> e.message?.takeIf { it.isNotBlank() }?.let { "$it." } ?: "server error (HTTP ${e.status})."
        }
        return VaultException("$prefix: $detail")
    }

    private fun utf8(chars: CharArray): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) Arrays.fill(bb.array(), 0)
        return out
    }
}
