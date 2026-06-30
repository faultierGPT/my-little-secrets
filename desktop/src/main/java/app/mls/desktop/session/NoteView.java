package app.mls.desktop.session;

import java.util.List;

/**
 * A note decrypted for display in the UI. This is the ONLY shape note plaintext takes in the
 * desktop layer; it exists purely in memory while the vault is unlocked and is never persisted in
 * the clear (the on-disk cache is encrypted; see {@code EncryptedFileNoteStore}).
 *
 * @param id        stable note id (UUID); {@code null} for a brand-new unsaved note
 * @param title     note title (may be blank)
 * @param body      note body
 * @param tags      free-form tags
 * @param updatedAt server (or local) epoch-millis of the last change, for ordering
 * @param dirty     true if there are local edits not yet pushed to the server
 */
public record NoteView(String id, String title, String body, List<String> tags, long updatedAt, boolean dirty) {

    /** A short single-line preview for the note list. */
    public String snippet() {
        String basis = !body.isBlank() ? body : (tags.isEmpty() ? "" : String.join(" ", tags));
        String oneLine = basis.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 79) + "…";
    }

    /** The title to show, falling back to a placeholder when empty. */
    public String displayTitle() {
        return title.isBlank() ? "Untitled" : title;
    }
}
