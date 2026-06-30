package app.mls.desktop;

import app.mls.desktop.session.NoteView;
import app.mls.desktop.session.RegistrationOutcome;
import app.mls.desktop.session.SyncSummary;
import app.mls.desktop.session.Vault;
import app.mls.desktop.session.VaultException;
import app.mls.server.Embedded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the pure-Java desktop {@link Vault} controller against the REAL server (Netty + embedded
 * H2) over a loopback socket — the whole stack, no mocks of our own code. Proves: register opens a
 * session and yields a recovery code; a note encrypts locally and syncs; a SECOND device logs in,
 * pulls and decrypts it; the encrypted cache supports offline unlock; a wrong password is rejected.
 *
 * (That the server only ever stored ciphertext is proven separately in the server module's
 * ClientServerEndToEndTest / ServerCannotReadTest, so it is not re-litigated here.)
 */
class VaultIntegrationTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void fullDesktopLifecycleAgainstRealServer(@TempDir Path tmp) throws Exception {
        String dbName = "desktop_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Embedded.Handle server = Embedded.start(Embedded.h2Config(dbName))) {
            String url = "http://127.0.0.1:" + server.port;

            // ---- Device 1: register, create a note, sync it up ----
            DesktopConfig cfg1 = new DesktopConfig(url, tmp.resolve("dev1"), Duration.ofMinutes(5));
            String noteId;
            try (Vault dev1 = new Vault(cfg1)) {
                assertFalse(dev1.hasLocalAccount(), "no profile before first run");

                RegistrationOutcome reg = dev1.register(EMAIL, PASSWORD.toCharArray());
                assertTrue(reg.hasRecoveryCode(), "registration must surface a one-time recovery code");
                assertTrue(dev1.isUnlocked());
                assertTrue(dev1.isOnline());
                assertTrue(dev1.hasLocalAccount(), "profile cached after register");
                assertEquals(EMAIL, dev1.rememberedEmail());

                noteId = dev1.saveNote(null, "Grocery list", "milk, eggs, and a secret", List.of("home"));
                assertEquals(1, dev1.notes().size());

                SyncSummary up = dev1.sync();
                assertEquals(1, up.pushed(), "the new note is pushed");
                assertEquals(0, up.conflicts());
                assertFalse(dev1.notes().get(0).dirty(), "pushed note is no longer dirty");
            }

            // ---- Device 2: independent data dir, logs in, pulls + decrypts the note ----
            DesktopConfig cfg2 = new DesktopConfig(url, tmp.resolve("dev2"), Duration.ofMinutes(5));
            try (Vault dev2 = new Vault(cfg2)) {
                dev2.login(EMAIL, PASSWORD.toCharArray());
                assertTrue(dev2.isOnline());

                SyncSummary down = dev2.sync();
                assertEquals(1, down.pulled(), "the note arrives from the server");

                List<NoteView> notes = dev2.notes();
                assertEquals(1, notes.size());
                NoteView note = notes.get(0);
                assertEquals(noteId, note.id());
                assertEquals("Grocery list", note.title());
                assertEquals("milk, eggs, and a secret", note.body(), "decrypted on a second device");
                assertEquals(List.of("home"), note.tags());

                // ---- Offline unlock from the encrypted local cache ----
                dev2.lock();
                assertFalse(dev2.isUnlocked());

                // wrong password is rejected by the wrapped-key AEAD open
                assertThrows(VaultException.class, () -> dev2.unlockOffline("not the password".toCharArray()));
                assertFalse(dev2.isUnlocked(), "a failed unlock leaves the vault locked");

                dev2.unlockOffline(PASSWORD.toCharArray());
                assertTrue(dev2.isUnlocked());
                assertFalse(dev2.isOnline(), "offline unlock holds no bearer token");
                assertEquals("milk, eggs, and a secret", dev2.notes().get(0).body(),
                        "note is readable from the encrypted cache with no network");

                // ---- Sync is gated until an online sign-in restores a token ----
                assertThrows(VaultException.class, dev2::sync);
            }
        }
    }
}
