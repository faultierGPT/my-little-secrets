package app.mls.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Desktop runtime configuration. Server URL and data directory come from the environment so the
 * same build can point at localhost or a self-hosted instance; sensible defaults target local dev.
 *
 * @param serverUrl base URL of the sync server, no trailing slash (e.g. {@code http://localhost:8080})
 * @param dataDir   where the encrypted local cache + (non-secret) account profile live
 * @param autoLock  idle timeout after which the vault wipes the in-memory account key and re-locks
 */
public record DesktopConfig(String serverUrl, Path dataDir, Duration autoLock) {

    public static DesktopConfig defaults() {
        return new DesktopConfig(
                stripTrailingSlash(env("MLS_SERVER_URL", "http://localhost:8080")),
                defaultDataDir(),
                Duration.ofMinutes(parseLongEnv("MLS_AUTOLOCK_MINUTES", 5)));
    }

    /** Returns a copy with a different server URL (used by the sign-in screen). */
    public DesktopConfig withServerUrl(String url) {
        return new DesktopConfig(stripTrailingSlash(url), dataDir, autoLock);
    }

    public Path cacheFile() {
        return dataDir.resolve("cache.bin");
    }

    public Path profileFile() {
        return dataDir.resolve("account.properties");
    }

    private static Path defaultDataDir() {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Paths.get(xdg)
                : Paths.get(System.getProperty("user.home"), ".local", "share");
        return base.resolve("my-little-secrets");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static long parseLongEnv(String key, long fallback) {
        try {
            String v = System.getenv(key);
            return (v != null && !v.isBlank()) ? Long.parseLong(v.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stripTrailingSlash(String url) {
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
