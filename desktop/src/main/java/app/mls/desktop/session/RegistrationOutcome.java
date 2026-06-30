package app.mls.desktop.session;

/**
 * Result of a successful registration. The {@link #recoveryCode} is the ONE-TIME, dash-grouped
 * recovery string the UI must show the user immediately and never persist; it is {@code null} if
 * recovery was not enabled. Hold it only while it is on screen.
 */
public record RegistrationOutcome(String recoveryCode) {
    public boolean hasRecoveryCode() {
        return recoveryCode != null && !recoveryCode.isBlank();
    }
}
