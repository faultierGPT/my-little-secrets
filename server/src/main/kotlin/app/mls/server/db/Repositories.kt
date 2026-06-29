package app.mls.server.db

import app.mls.core.model.NoteDto
import java.sql.ResultSet
import java.sql.SQLException

/** A stored user row. All blob fields are opaque to the server. */
data class UserRecord(
    val id: String,
    val email: String,
    val salt: String,
    val kdfParamsJson: String,
    val authVerifier: String,
    val wrappedAccountKey: String,
    val wrappedAccountKeyRecovery: String?,
    val schemeVersion: Int,
    val createdAt: Long,
)

class UserRepository(private val db: Db) {

    class EmailTakenException : RuntimeException("email already registered")

    fun insert(rec: UserRecord) = db.tx { c ->
        try {
            c.prepareStatement(
                """INSERT INTO users
                   (id,email,salt,kdf_params,auth_verifier,wrapped_account_key,wrapped_account_key_recovery,scheme_version,created_at)
                   VALUES (?,?,?,?,?,?,?,?,?)""",
            ).use { ps ->
                ps.setString(1, rec.id)
                ps.setString(2, rec.email)
                ps.setString(3, rec.salt)
                ps.setString(4, rec.kdfParamsJson)
                ps.setString(5, rec.authVerifier)
                ps.setString(6, rec.wrappedAccountKey)
                ps.setString(7, rec.wrappedAccountKeyRecovery)
                ps.setInt(8, rec.schemeVersion)
                ps.setLong(9, rec.createdAt)
                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") throw EmailTakenException() else throw e // 23505 = unique violation (PG + H2)
        }
        Unit
    }

    fun findByEmail(email: String): UserRecord? = db.tx { c ->
        c.prepareStatement("SELECT * FROM users WHERE email = ?").use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    fun findById(id: String): UserRecord? = db.tx { c ->
        c.prepareStatement("SELECT * FROM users WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    fun updateCredentials(
        userId: String,
        salt: String,
        kdfParamsJson: String,
        authVerifier: String,
        wrappedAccountKey: String,
    ) = db.tx { c ->
        c.prepareStatement(
            "UPDATE users SET salt=?, kdf_params=?, auth_verifier=?, wrapped_account_key=? WHERE id=?",
        ).use { ps ->
            ps.setString(1, salt)
            ps.setString(2, kdfParamsJson)
            ps.setString(3, authVerifier)
            ps.setString(4, wrappedAccountKey)
            ps.setString(5, userId)
            ps.executeUpdate()
        }
        Unit
    }

    private fun ResultSet.toUser() = UserRecord(
        id = getString("id"),
        email = getString("email"),
        salt = getString("salt"),
        kdfParamsJson = getString("kdf_params"),
        authVerifier = getString("auth_verifier"),
        wrappedAccountKey = getString("wrapped_account_key"),
        wrappedAccountKeyRecovery = getString("wrapped_account_key_recovery"),
        schemeVersion = getInt("scheme_version"),
        createdAt = getLong("created_at"),
    )
}

class NoteRepository(private val db: Db) {

    private data class Existing(val revision: Long, val schemeVersion: Int)

    fun upsert(userId: String, id: String, ciphertext: String, nonce: String, schemeVersion: Int, now: Long): NoteDto =
        db.tx { c ->
            val existing = selectExisting(c, userId, id)
            if (existing == null) {
                c.prepareStatement(
                    """INSERT INTO notes (id,user_id,ciphertext,nonce,scheme_version,updated_at,deleted,revision)
                       VALUES (?,?,?,?,?,?,FALSE,1)""",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, userId); ps.setString(3, ciphertext)
                    ps.setString(4, nonce); ps.setInt(5, schemeVersion); ps.setLong(6, now)
                    ps.executeUpdate()
                }
                NoteDto(id, ciphertext, nonce, schemeVersion, now, deleted = false, revision = 1)
            } else {
                val newRev = existing.revision + 1
                c.prepareStatement(
                    """UPDATE notes SET ciphertext=?, nonce=?, scheme_version=?, updated_at=?, deleted=FALSE, revision=?
                       WHERE user_id=? AND id=?""",
                ).use { ps ->
                    ps.setString(1, ciphertext); ps.setString(2, nonce); ps.setInt(3, schemeVersion)
                    ps.setLong(4, now); ps.setLong(5, newRev); ps.setString(6, userId); ps.setString(7, id)
                    ps.executeUpdate()
                }
                NoteDto(id, ciphertext, nonce, schemeVersion, now, deleted = false, revision = newRev)
            }
        }

    /** Tombstone: mark deleted, clear ciphertext (the server keeps no content of deleted notes). */
    fun softDelete(userId: String, id: String, now: Long): NoteDto? = db.tx { c ->
        val existing = selectExisting(c, userId, id) ?: return@tx null
        val newRev = existing.revision + 1
        c.prepareStatement(
            "UPDATE notes SET ciphertext='', nonce='', deleted=TRUE, updated_at=?, revision=? WHERE user_id=? AND id=?",
        ).use { ps ->
            ps.setLong(1, now); ps.setLong(2, newRev); ps.setString(3, userId); ps.setString(4, id)
            ps.executeUpdate()
        }
        NoteDto(id, "", "", existing.schemeVersion, now, deleted = true, revision = newRev)
    }

    /** All notes (incl. tombstones) changed strictly after [since], oldest first. */
    fun listSince(userId: String, since: Long): List<NoteDto> = db.tx { c ->
        c.prepareStatement(
            """SELECT id,ciphertext,nonce,scheme_version,updated_at,deleted,revision
               FROM notes WHERE user_id=? AND updated_at > ? ORDER BY updated_at ASC, id ASC""",
        ).use { ps ->
            ps.setString(1, userId); ps.setLong(2, since)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            NoteDto(
                                id = rs.getString("id"),
                                ciphertext = rs.getString("ciphertext"),
                                nonce = rs.getString("nonce"),
                                schemeVersion = rs.getInt("scheme_version"),
                                updatedAt = rs.getLong("updated_at"),
                                deleted = rs.getBoolean("deleted"),
                                revision = rs.getLong("revision"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun selectExisting(c: java.sql.Connection, userId: String, id: String): Existing? =
        c.prepareStatement("SELECT revision, scheme_version FROM notes WHERE user_id=? AND id=?").use { ps ->
            ps.setString(1, userId); ps.setString(2, id)
            ps.executeQuery().use { rs -> if (rs.next()) Existing(rs.getLong(1), rs.getInt(2)) else null }
        }
}

class SessionRepository(private val db: Db) {

    fun create(tokenHash: String, userId: String, expiresAt: Long, now: Long) = db.tx { c ->
        c.prepareStatement(
            "INSERT INTO sessions (token_hash,user_id,expires_at,created_at) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, tokenHash); ps.setString(2, userId); ps.setLong(3, expiresAt); ps.setLong(4, now)
            ps.executeUpdate()
        }
        Unit
    }

    fun findValidUserId(tokenHash: String, now: Long): String? = db.tx { c ->
        c.prepareStatement("SELECT user_id FROM sessions WHERE token_hash=? AND expires_at > ?").use { ps ->
            ps.setString(1, tokenHash); ps.setLong(2, now)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun delete(tokenHash: String) = db.tx { c ->
        c.prepareStatement("DELETE FROM sessions WHERE token_hash=?").use { ps ->
            ps.setString(1, tokenHash); ps.executeUpdate()
        }
        Unit
    }

    fun deleteAllForUser(userId: String) = db.tx { c ->
        c.prepareStatement("DELETE FROM sessions WHERE user_id=?").use { ps ->
            ps.setString(1, userId); ps.executeUpdate()
        }
        Unit
    }
}
