package app.mls.desktop.session;

/**
 * A user-facing failure from a {@link Vault} operation. The message is safe to show in the UI; it
 * deliberately never contains secrets, ciphertext, tokens, or raw server payloads.
 */
public class VaultException extends RuntimeException {
    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
