package app.mls.core.crypto

import com.goterl.lazysodium.LazySodium
import com.sun.jna.NativeLong

/**
 * The single boundary to libsodium (via lazysodium-java). EVERY cryptographic operation in
 * this module flows through here, so the "vetted library only — nothing hand-rolled"
 * invariant is auditable in exactly one file.
 *
 * ## Dependency-isolation design
 *
 * `:core` declares `lazysodium-java` and `jna` as `compileOnly`. The Android release build
 * excludes their transitive resolution (no Android-side libsodium JAR reaches the APK), so
 * the JVM binding classes are NOT on Android's runtime classpath. Constraint:
 *   - `Sodium`'s `<clinit>` MUST NOT trigger class-init of any libsodium-flavored type, and
 *   - `cryptoPwHash(...)`'s algorithm enum constant is resolved via reflection because the
 *     type isn't statically reachable.
 *
 * Implementation:
 *   - [binding] is a nullable `@Volatile` field with no eager initializer.
 *   - A static block attempts to install the JVM binding (`LazySodiumJava(SodiumJava())`) at
 *     class-init time IF `com.goterl.lazysodium.LazySodiumJava` is reachable on the running
 *     classpath. This is the "happy path" for JVM callers (desktop, server, `:core` test).
 *     If the class is not found (Android, where the JVM jar is excluded), the static block
 *     silently does nothing, and platform code is expected to call [useBinding] explicitly.
 *   - [useBinding] resolves and caches `com.goterl.lazysodium.interfaces.PwHash$Alg.
 *     PWHASH_ALG_ARGON2ID13` plus the matching `LazySodium#cryptoPwHash(...)` [Method], so
 *     `Sodium.<clinit>` never needs to mention the enum type. The reflective dispatch in
 *     [pwhash] is hidden inside an Argon2id computation — cost is dwarfed by KDF time.
 *   - The libsodium size constants (AEAD / KDF / PWHASH) are pinned as `const val` so
 *     reading them never triggers interface class-init on the consumer class loader.
 *   - [ls] errors out with a clear message if accessed before any binding (including the
 *     JVM-default static-block attempt) has succeeded.
 *
 * Primitives used (all libsodium):
 *  - Argon2id        : crypto_pwhash / crypto_pwhash_str            (KDF + server-side verifier)
 *  - XChaCha20-Poly1305 IETF : crypto_aead_xchacha20poly1305_ietf_* (authenticated encryption)
 *  - crypto_kdf (BLAKE2b)    : crypto_kdf_derive_from_key            (domain-separated subkeys)
 *  - randombytes_buf         : CSPRNG
 */
object Sodium {

    @Volatile
    private var binding: LazySodium? = null

    @Volatile
    private var argon2IdAlg: Any? = null

    @Volatile
    private var pwhashMethod: java.lang.reflect.Method? = null

    init {
        // Lazy "happy path" install of the JVM binding if the libsodium JVM jar is on the
        // classpath. This is the path `:core`/`:server`/`:desktop` test runners and main
        // processes hit. Android's classpath doesn't include `lazysodium-java` (compileOnly),
        // so this `Class.forName` throws and Android code is expected to call `useBinding`
        // explicitly from `MlsApplication.onCreate`.
        runCatching {
            val loader = Sodium::class.java.classLoader
            val lazyClass = Class.forName("com.goterl.lazysodium.LazySodiumJava", true, loader)
            val sodiumClass = Class.forName("com.goterl.lazysodium.SodiumJava", true, loader)
            val sodiumCtor = sodiumClass.getDeclaredConstructor().apply { isAccessible = true }
            val sodium = sodiumCtor.newInstance()
            val lazyCtor = lazyClass.getDeclaredConstructor(sodiumClass).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val lazy = lazyCtor.newInstance(sodium) as LazySodium
            useBinding(lazy)
        }
    }

    /** Process-wide libsodium handle. Throws if no binding has been installed yet. */
    val ls: LazySodium
        get() = binding ?: error(
            "Sodium binding is not installed. Platform code MUST call Sodium.useBinding(...) " +
                "with a concrete LazySodium (LazySodiumAndroid on Android, LazySodiumJava on " +
                "desktop/server/tests) before any cryptographic operation runs. See AGENTS.md."
        )

    /**
     * Install the platform libsodium binding. MUST be called exactly ONCE at process start
     * before any crypto op runs. Subsequent calls REPLACE the binding.
     *
     * Resolves and caches [com.goterl.lazysodium.interfaces.PwHash.Alg.PWHASH_ALG_ARGON2ID13]
     * reflectively from the active binding's class loader, plus the matching
     * `LazySodium#cryptoPwHash(...)` [Method]. After this call, the hot path of [pwhash]
     * is a single reflection invocation. Idempotent — re-binding just re-caches.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun useBinding(platformBinding: LazySodium) {
        binding = platformBinding
        val loader = platformBinding.javaClass.classLoader ?: Sodium::class.java.classLoader
        val algClass = Class.forName("com.goterl.lazysodium.interfaces.PwHash\$Alg", true, loader)
        argon2IdAlg = algClass.getField("PWHASH_ALG_ARGON2ID13").get(null)
        pwhashMethod = platformBinding.javaClass.methods.first { m ->
            m.name == "cryptoPwHash" &&
                m.parameterTypes.size == 8 &&
                m.parameterTypes[0] == ByteArray::class.java &&
                m.parameterTypes[1] == java.lang.Integer.TYPE &&
                m.parameterTypes[2] == ByteArray::class.java &&
                m.parameterTypes[3] == java.lang.Integer.TYPE &&
                m.parameterTypes[4] == ByteArray::class.java &&
                m.parameterTypes[5] == java.lang.Long.TYPE &&
                m.parameterTypes[6] == NativeLong::class.java &&
                java.lang.Enum::class.java.isAssignableFrom(m.parameterTypes[7])
        }.also { it.isAccessible = true }
    }

    // ---- Sizes (libsodium ABI constants — identical across lazysodium-java and lazysodium-android) ----
    const val AEAD_KEY_BYTES: Int = 32         // XCHACHA20POLY1305_IETF_KEYBYTES
    const val AEAD_NONCE_BYTES: Int = 24       // XCHACHA20POLY1305_IETF_NPUBBYTES
    const val AEAD_TAG_BYTES: Int = 16         // XCHACHA20POLY1305_IETF_ABYTES
    const val KDF_KEY_BYTES: Int = 32          // MASTER_KEY
    const val KDF_CONTEXT_BYTES: Int = 8       // CONTEXTBYTES
    const val PWHASH_SALT_BYTES: Int = 16      // ARGON2ID_SALTBYTES
    const val PWHASH_STR_BYTES: Int = 128      // STRBYTES

    // ---- CSPRNG ----
    fun randomBytes(n: Int): ByteArray = ls.randomBytesBuf(n)

    /** Best-effort native zeroization of an array we own. */
    fun memzero(b: ByteArray) {
        ls.sodiumMemZero(b, b.size)
    }

    // ---- Argon2id (crypto_pwhash) ----
    /**
     * Derive [outLen] bytes of key material from [password] + [salt] with the given Argon2id cost.
     *
     * `cryptoPwHash(...)` takes the algorithm as a [com.goterl.lazysodium.interfaces.PwHash.Alg]
     * enum. To keep `:core` free of any reference to that type, the enum is resolved
     * reflectively once on [useBinding] (cached in [argon2IdAlg]); this method makes one
     * reflective call per invocation. The cost of reflection is dwarfed by Argon2id's own
     * cost at the 256 MiB profile.
     */
    fun pwhash(outLen: Int, password: ByteArray, salt: ByteArray, ops: Long, memBytes: Long): ByteArray {
        require(salt.size == PWHASH_SALT_BYTES) { "salt must be $PWHASH_SALT_BYTES bytes, got ${salt.size}" }
        val method = pwhashMethod
            ?: error("Sodium.pwhash called before Sodium.useBinding installed the binding")
        val alg = argon2IdAlg
            ?: error("Sodium.pwhash called before Sodium.useBinding installed the binding")
        val out = ByteArray(outLen)
        val ok = method.invoke(ls, out, out.size, password, password.size, salt, ops, NativeLong(memBytes), alg) as Boolean
        check(ok) { "crypto_pwhash failed (invalid params or out of memory)" }
        return out
    }

    /** Produce a self-describing PHC verifier string `$argon2id$...` over [password]. */
    fun pwhashStr(password: ByteArray, ops: Long, memBytes: Long): String {
        val out = ByteArray(PWHASH_STR_BYTES)
        val ok = ls.cryptoPwHashStr(out, password, password.size, ops, NativeLong(memBytes))
        check(ok) { "crypto_pwhash_str failed" }
        return out.decodeToString().substringBefore('\u0000')
    }

    /** Constant-time verification of [password] against a stored PHC string. */
    fun pwhashStrVerify(phc: String, password: ByteArray): Boolean {
        val buf = ByteArray(PWHASH_STR_BYTES)
        val bytes = phc.toByteArray(Charsets.US_ASCII)
        require(bytes.size < PWHASH_STR_BYTES) { "verifier string too long" }
        bytes.copyInto(buf)
        return ls.cryptoPwHashStrVerify(buf, password, password.size)
    }

    // ---- crypto_kdf subkey derivation (BLAKE2b) ----
    /** Derive an independent subkey from a 32-byte high-entropy [masterKey]. */
    fun kdfDerive(subkeyLen: Int, subkeyId: Long, context: String, masterKey: ByteArray): ByteArray {
        require(masterKey.size == KDF_KEY_BYTES) { "kdf master key must be $KDF_KEY_BYTES bytes" }
        val ctx = context.toByteArray(Charsets.US_ASCII)
        require(ctx.size == KDF_CONTEXT_BYTES) { "kdf context must be exactly $KDF_CONTEXT_BYTES ASCII bytes, got ${ctx.size}" }
        val sub = ByteArray(subkeyLen)
        val rc = ls.cryptoKdfDeriveFromKey(sub, sub.size, subkeyId, ctx, masterKey)
        check(rc == 0) { "crypto_kdf_derive_from_key failed (rc=$rc)" }
        return sub
    }

    // ---- XChaCha20-Poly1305 IETF (AEAD) ----
    /** Encrypt [message] under [key] with [nonce] (24 random bytes) and associated data [aad]. */
    fun aeadEncrypt(message: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == AEAD_NONCE_BYTES) { "nonce must be $AEAD_NONCE_BYTES bytes" }
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        val c = ByteArray(message.size + AEAD_TAG_BYTES)
        val cLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            c, cLen, message, message.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
        )
        check(ok) { "AEAD encryption failed" }
        return if (cLen[0].toInt() == c.size) c else c.copyOf(cLen[0].toInt())
    }

    /**
     * Decrypt and verify. Throws [AeadAuthException] on ANY authentication failure — a wrong key,
     * a flipped ciphertext bit, a wrong nonce, or mismatched associated data all land here.
     */
    fun aeadDecrypt(ciphertext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == AEAD_NONCE_BYTES) { "nonce must be $AEAD_NONCE_BYTES bytes" }
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        require(ciphertext.size >= AEAD_TAG_BYTES) { "ciphertext shorter than the $AEAD_TAG_BYTES-byte auth tag" }
        val m = ByteArray(ciphertext.size - AEAD_TAG_BYTES)
        val mLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            m, mLen, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), nonce, key,
        )
        if (!ok) {
            java.util.Arrays.fill(m, 0)
            throw AeadAuthException()
        }
        return if (mLen[0].toInt() == m.size) m else m.copyOf(mLen[0].toInt())
    }
}

/** Thrown when AEAD decryption fails its Poly1305 authentication check. */
class AeadAuthException : RuntimeException(
    "AEAD authentication failed: wrong key, tampered ciphertext, wrong nonce, or wrong associated data",
)
