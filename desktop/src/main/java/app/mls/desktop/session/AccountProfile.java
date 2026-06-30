package app.mls.desktop.session;

import app.mls.core.crypto.KdfParams;
import app.mls.core.model.EncryptedBlob;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

/**
 * The non-secret material needed to unlock the account OFFLINE: the public Argon2id salt + cost
 * params and the account key WRAPPED under the password-derived KEK. None of this is sensitive on
 * its own — the wrapped key is useless without the password — so it is stored as a plain properties
 * file next to the (encrypted) cache. It is what lets the desktop app open the local vault with no
 * network, exactly like the server-backed login but using a locally cached copy of the same blob.
 */
public final class AccountProfile {

    public final String email;
    public final String serverUrl;
    public final byte[] salt;
    public final KdfParams kdfParams;
    public final EncryptedBlob wrappedAccountKey;
    public final int schemeVersion;

    public AccountProfile(String email, String serverUrl, byte[] salt, KdfParams kdfParams,
                          EncryptedBlob wrappedAccountKey, int schemeVersion) {
        this.email = email;
        this.serverUrl = serverUrl;
        this.salt = salt;
        this.kdfParams = kdfParams;
        this.wrappedAccountKey = wrappedAccountKey;
        this.schemeVersion = schemeVersion;
    }

    public void save(Path file) {
        Properties p = new Properties();
        p.setProperty("email", email);
        p.setProperty("serverUrl", serverUrl);
        p.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        p.setProperty("kdf.algorithm", kdfParams.getAlgorithm());
        p.setProperty("kdf.memLimitBytes", Long.toString(kdfParams.getMemLimitBytes()));
        p.setProperty("kdf.opsLimit", Long.toString(kdfParams.getOpsLimit()));
        p.setProperty("kdf.parallelism", Integer.toString(kdfParams.getParallelism()));
        p.setProperty("wrap.ciphertext", wrappedAccountKey.getCiphertext());
        p.setProperty("wrap.nonce", wrappedAccountKey.getNonce());
        p.setProperty("wrap.schemeVersion", Integer.toString(wrappedAccountKey.getSchemeVersion()));
        p.setProperty("schemeVersion", Integer.toString(schemeVersion));
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "my-little-secrets account profile (non-secret; wrapped key only)");
            }
        } catch (IOException e) {
            throw new VaultException("Could not save the local account profile.", e);
        }
    }

    public static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    public static AccountProfile load(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new VaultException("Could not read the local account profile.", e);
        }
        KdfParams kdf = new KdfParams(
                p.getProperty("kdf.algorithm", "argon2id"),
                Long.parseLong(p.getProperty("kdf.memLimitBytes")),
                Long.parseLong(p.getProperty("kdf.opsLimit")),
                Integer.parseInt(p.getProperty("kdf.parallelism", "1")));
        EncryptedBlob wrapped = new EncryptedBlob(
                p.getProperty("wrap.ciphertext"),
                p.getProperty("wrap.nonce"),
                Integer.parseInt(p.getProperty("wrap.schemeVersion", "1")));
        return new AccountProfile(
                p.getProperty("email"),
                p.getProperty("serverUrl"),
                Base64.getDecoder().decode(p.getProperty("salt")),
                kdf,
                wrapped,
                Integer.parseInt(p.getProperty("schemeVersion", "1")));
    }
}
