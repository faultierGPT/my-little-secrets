package app.mls.desktop.session;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Password handling that avoids creating extra immutable {@code String} copies of the secret. */
public final class Passwords {

    private Passwords() {
    }

    /**
     * UTF-8 encode a password held as a {@code char[]} into a {@code byte[]}, without round-tripping
     * through a {@code String}. The caller owns and must wipe BOTH the input {@code chars} and the
     * returned array. (JavaFX's PasswordField only exposes a String, an unavoidable upstream copy
     * we document honestly in SECURITY.md — this keeps every step we DO control off the String heap.)
     */
    public static byte[] utf8(char[] chars) {
        CharBuffer cb = CharBuffer.wrap(chars);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        // Scrub the encoder's backing buffer, which held a copy of the plaintext bytes.
        if (bb.hasArray()) {
            Arrays.fill(bb.array(), (byte) 0);
        }
        return out;
    }
}
