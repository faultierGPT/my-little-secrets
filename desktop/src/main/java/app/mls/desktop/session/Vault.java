package app.mls.desktop.session;

import app.mls.core.api.ApiException;
import app.mls.core.api.KtorApiClient;
import app.mls.core.crypto.B64;
import app.mls.core.crypto.CryptoCore;
import app.mls.core.crypto.KdfParams;
import app.mls.core.crypto.RecoveryCode;
import app.mls.core.crypto.RegistrationMaterial;
import app.mls.core.crypto.RewrapResult;
import app.mls.core.crypto.Sealed;
import app.mls.core.crypto.SecretBytes;
import app.mls.core.jvm.BlockingApi;
import app.mls.core.jvm.BlockingSync;
import app.mls.core.model.AccountKeyResponse;
import app.mls.core.model.LoginParamsResponse;
import app.mls.core.model.LoginRequest;
import app.mls.core.model.NotePayload;
import app.mls.core.model.PasswordChangeRequest;
import app.mls.core.model.RegisterRequest;
import app.mls.core.store.EncryptedFileNoteStore;
import app.mls.core.sync.DecryptedNote;
import app.mls.core.sync.SyncEngine;
import app.mls.core.sync.SyncResult;
import app.mls.desktop.DesktopConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The desktop session controller — the single point where the pure-Java UI meets the vetted Kotlin
 * crypto/sync/API core. It owns the in-memory account key and the encrypted local store while
 * unlocked, and tears them down on {@link #lock()} / {@link #close()}.
 *
 * Zero-knowledge invariants upheld here:
 * <ul>
 *   <li>The master password is converted to bytes, used, and wiped; it is never stored.</li>
 *   <li>Only the {@code authKey} (a password-derived credential) and ciphertext ever leave; the
 *       master key, KEK and account key never do.</li>
 *   <li>The account key lives only as wipeable {@link SecretBytes}; the on-disk cache is encrypted.</li>
 * </ul>
 *
 * All methods are blocking and {@code synchronized}; the UI invokes them off the JavaFX thread.
 */
public final class Vault implements AutoCloseable {

    private final DesktopConfig config;
    private final KtorApiClient api;
    private final BlockingApi rpc;

    // Session state — non-null only while unlocked.
    private SecretBytes accountKey;
    private EncryptedFileNoteStore store;
    private SyncEngine engine;
    private String email;

    public Vault(DesktopConfig config) {
        this.config = config;
        this.api = new KtorApiClient(config.serverUrl());
        this.rpc = new BlockingApi(api);
    }

    // ---------- state queries ----------

    public synchronized boolean isUnlocked() {
        return accountKey != null;
    }

    /** True if a bearer session is held, i.e. sync / password-change are possible. */
    public synchronized boolean isOnline() {
        return rpc.getToken() != null;
    }

    public boolean hasLocalAccount() {
        return AccountProfile.exists(config.profileFile());
    }

    /** Email of the locally remembered account (for prefilling the sign-in field), or "". */
    public String rememberedEmail() {
        if (!hasLocalAccount()) {
            return "";
        }
        try {
            String e = AccountProfile.load(config.profileFile()).email;
            return e == null ? "" : e;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------- account lifecycle ----------

    /** Create a new account on the server and open the session. Returns the one-time recovery code. */
    public synchronized RegistrationOutcome register(String email, char[] password) {
        requireLocked();
        byte[] pw = Passwords.utf8(password);
        RegistrationMaterial material = null;
        SecretBytes authKey = null;
        boolean adopted = false;
        try {
            material = CryptoCore.INSTANCE.register(pw, KdfParams.Companion.getDEFAULT(), true);
            authKey = material.getAuthKey();
            String authKeyB64 = B64.INSTANCE.encode(authKey.bytes());
            RegisterRequest req = new RegisterRequest(
                    email,
                    B64.INSTANCE.encode(material.getSalt()),
                    material.getKdfParams(),
                    authKeyB64,
                    material.getWrappedAccountKey().toBlob(),
                    material.getWrappedAccountKeyRecovery() == null
                            ? null : material.getWrappedAccountKeyRecovery().toBlob(),
                    1);
            try {
                rpc.register(req);
                rpc.login(new LoginRequest(email, authKeyB64)); // sets the bearer token on `api`
            } catch (ApiException e) {
                throw friendly("Registration failed", e);
            } catch (RuntimeException e) {
                throw unreachable(e);
            }

            this.email = email;
            this.accountKey = material.getAccountKey();
            openSession();
            new AccountProfile(email, config.serverUrl(), material.getSalt(), material.getKdfParams(),
                    material.getWrappedAccountKey().toBlob(), 1).save(config.profileFile());
            adopted = true;

            String recovery = null;
            RecoveryCode rc = material.getRecoveryCode();
            if (rc != null) {
                recovery = rc.display();
                rc.destroy();
            }
            return new RegistrationOutcome(recovery);
        } finally {
            if (authKey != null) {
                authKey.destroy();
            }
            Arrays.fill(pw, (byte) 0);
            // If we failed before adopting the session, the freshly-generated account key and
            // recovery code are unused secrets — wipe them rather than let them linger.
            if (material != null && !adopted) {
                material.getAccountKey().destroy();
                RecoveryCode rc = material.getRecoveryCode();
                if (rc != null) {
                    rc.destroy();
                }
            }
        }
    }

    /** Online sign-in: authenticate, fetch + unwrap the account key, open the session, cache profile. */
    public synchronized void login(String email, char[] password) {
        requireLocked();
        byte[] pw = Passwords.utf8(password);
        SecretBytes authKey = null;
        SecretBytes unlocked = null;
        boolean adopted = false;
        try {
            LoginParamsResponse params;
            try {
                params = rpc.loginParams(email);
            } catch (ApiException e) {
                throw friendly("Sign-in failed", e);
            } catch (RuntimeException e) {
                throw unreachable(e);
            }

            byte[] salt = B64.INSTANCE.decode(params.getSalt());
            authKey = CryptoCore.INSTANCE.deriveAuthKeyForLogin(pw, salt, params.getKdfParams());
            try {
                rpc.login(new LoginRequest(email, B64.INSTANCE.encode(authKey.bytes())));
            } catch (ApiException e) {
                throw friendly("Sign-in failed", e);
            } catch (RuntimeException e) {
                throw unreachable(e);
            }

            AccountKeyResponse keyResp;
            try {
                keyResp = rpc.getAccountKey();
            } catch (ApiException e) {
                throw friendly("Could not load your account key", e);
            } catch (RuntimeException e) {
                throw unreachable(e);
            }

            Sealed wrapped = Sealed.Companion.fromBlob(keyResp.getWrappedAccountKey());
            try {
                unlocked = CryptoCore.INSTANCE.unlockWithPassword(pw, salt, params.getKdfParams(), wrapped);
            } catch (RuntimeException e) {
                throw new VaultException("Could not unlock your account key.");
            }

            this.email = email;
            this.accountKey = unlocked;
            openSession();
            new AccountProfile(email, config.serverUrl(), salt, params.getKdfParams(),
                    keyResp.getWrappedAccountKey(), keyResp.getSchemeVersion()).save(config.profileFile());
            adopted = true;
        } finally {
            if (authKey != null) {
                authKey.destroy();
            }
            Arrays.fill(pw, (byte) 0);
            if (unlocked != null && !adopted) {
                unlocked.destroy();
            }
        }
    }

    /**
     * Offline unlock from the locally cached (non-secret) profile. Opens the local vault for reading
     * and editing with NO network; {@link #sync()} stays disabled until the next online {@link #login}.
     * A wrong password fails here as the wrapped-key AEAD open rejects it.
     */
    public synchronized void unlockOffline(char[] password) {
        requireLocked();
        if (!hasLocalAccount()) {
            throw new VaultException("No local account to unlock. Sign in online first.");
        }
        AccountProfile profile = AccountProfile.load(config.profileFile());
        byte[] pw = Passwords.utf8(password);
        SecretBytes unlocked = null;
        boolean adopted = false;
        try {
            Sealed wrapped = Sealed.Companion.fromBlob(profile.wrappedAccountKey);
            try {
                unlocked = CryptoCore.INSTANCE.unlockWithPassword(pw, profile.salt, profile.kdfParams, wrapped);
            } catch (RuntimeException e) {
                throw new VaultException("Wrong password.");
            }
            this.email = profile.email;
            this.accountKey = unlocked;
            openSession();
            adopted = true;
        } finally {
            Arrays.fill(pw, (byte) 0);
            if (unlocked != null && !adopted) {
                unlocked.destroy();
            }
        }
    }

    /** Wipe the in-memory account key, close the encrypted store, and drop the bearer token. */
    public synchronized void lock() {
        engine = null;
        if (store != null) {
            store.close(); // wipes the derived cache key
            store = null;
        }
        if (accountKey != null) {
            accountKey.destroy();
            accountKey = null;
        }
        rpc.setToken(null);
        email = null;
    }

    @Override
    public synchronized void close() {
        lock();
        api.close();
    }

    // ---------- notes ----------

    public synchronized List<NoteView> notes() {
        requireUnlocked();
        List<DecryptedNote> raw = engine.list();
        List<NoteView> out = new ArrayList<>(raw.size());
        for (DecryptedNote d : raw) {
            NotePayload p = d.getPayload();
            out.add(new NoteView(d.getId(), p.getTitle(), p.getBody(), p.getTags(), d.getUpdatedAt(), d.getDirty()));
        }
        out.sort(Comparator.comparingLong(NoteView::updatedAt).reversed());
        return out;
    }

    /** Create ({@code id == null}) or update a note locally (encrypted immediately). Returns its id. */
    public synchronized String saveNote(String id, String title, String body, List<String> tags) {
        requireUnlocked();
        NotePayload payload = new NotePayload(title, body, List.copyOf(tags), 1);
        return engine.save(payload, id);
    }

    public synchronized void deleteNote(String id) {
        requireUnlocked();
        engine.delete(id);
    }

    // ---------- sync ----------

    public synchronized SyncSummary sync() {
        requireUnlocked();
        if (rpc.getToken() == null) {
            throw new VaultException("Sign in online to sync.");
        }
        try {
            SyncResult r = BlockingSync.sync(engine);
            return new SyncSummary(r.getPulled(), r.getPushed(), r.getDeletedPushed(), r.getConflicts(), r.getKeptBoth());
        } catch (ApiException e) {
            if (e.isUnauthorized()) {
                rpc.setToken(null);
                throw new VaultException("Your session expired. Sign in again to sync.");
            }
            throw friendly("Sync failed", e);
        } catch (RuntimeException e) {
            throw unreachable(e);
        }
    }

    /** Change the master password: re-wrap the SAME account key; notes are never re-encrypted. */
    public synchronized void changePassword(char[] current, char[] next) {
        requireUnlocked();
        if (rpc.getToken() == null) {
            throw new VaultException("Sign in online to change your password.");
        }
        if (!hasLocalAccount()) {
            throw new VaultException("Missing local account profile.");
        }
        AccountProfile profile = AccountProfile.load(config.profileFile());
        byte[] curPw = Passwords.utf8(current);
        byte[] newPw = Passwords.utf8(next);
        SecretBytes curAuth = null;
        SecretBytes newAuth = null;
        try {
            curAuth = CryptoCore.INSTANCE.deriveAuthKeyForLogin(curPw, profile.salt, profile.kdfParams);
            RewrapResult rewrap = CryptoCore.INSTANCE.rewrapForNewPassword(accountKey, newPw, KdfParams.Companion.getDEFAULT());
            newAuth = rewrap.getAuthKey();
            String newAuthB64 = B64.INSTANCE.encode(newAuth.bytes());
            PasswordChangeRequest req = new PasswordChangeRequest(
                    B64.INSTANCE.encode(curAuth.bytes()),
                    B64.INSTANCE.encode(rewrap.getSalt()),
                    rewrap.getKdfParams(),
                    newAuthB64,
                    rewrap.getWrappedAccountKey().toBlob());
            try {
                rpc.changePassword(req);
            } catch (ApiException e) {
                if (e.isUnauthorized() || "forbidden".equals(e.getCode())) {
                    throw new VaultException("Current password is incorrect.");
                }
                throw friendly("Password change failed", e);
            } catch (RuntimeException e) {
                throw unreachable(e);
            }
            // The server invalidated all sessions; obtain a fresh token with the NEW credential and
            // refresh the cached profile so offline unlock uses the new wrapping.
            try {
                rpc.login(new LoginRequest(email, newAuthB64));
            } catch (RuntimeException e) {
                rpc.setToken(null); // password DID change; user can simply sign in again
            }
            new AccountProfile(email, config.serverUrl(), rewrap.getSalt(), rewrap.getKdfParams(),
                    rewrap.getWrappedAccountKey().toBlob(), 1).save(config.profileFile());
        } finally {
            if (curAuth != null) {
                curAuth.destroy();
            }
            if (newAuth != null) {
                newAuth.destroy();
            }
            Arrays.fill(curPw, (byte) 0);
            Arrays.fill(newPw, (byte) 0);
        }
    }

    // ---------- internals ----------

    private void openSession() {
        this.store = new EncryptedFileNoteStore(config.cacheFile().toFile(), accountKey);
        this.engine = new SyncEngine(api, store, accountKey);
    }

    private void requireUnlocked() {
        if (accountKey == null) {
            throw new VaultException("The vault is locked.");
        }
    }

    private void requireLocked() {
        if (accountKey != null) {
            throw new VaultException("Already unlocked. Lock first.");
        }
    }

    private VaultException unreachable(RuntimeException cause) {
        return new VaultException("Could not reach the server. Check your connection and the server URL.", cause);
    }

    /** Map a server {@link ApiException} to a safe, user-facing message (never echoes secrets). */
    private VaultException friendly(String prefix, ApiException e) {
        String code = e.getCode() == null ? "" : e.getCode();
        String detail = switch (code) {
            case "email_taken" -> "that email is already registered.";
            case "unauthorized" -> "incorrect email or password.";
            case "rate_limited" -> "too many attempts — wait a moment and try again.";
            case "not_found" -> "no account found for that email.";
            case "forbidden" -> "current password is incorrect.";
            case "conflict" -> "a newer version exists on the server.";
            default -> {
                String m = e.getMessage();
                yield (m == null || m.isBlank()) ? ("server error (HTTP " + e.getStatus() + ").") : (m + ".");
            }
        };
        return new VaultException(prefix + ": " + detail);
    }
}
