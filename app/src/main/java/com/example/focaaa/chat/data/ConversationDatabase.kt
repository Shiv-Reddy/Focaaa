package com.example.focaaa.chat.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.pairing.PairingVerificationSnapshot
import com.example.focaaa.chat.normalizeContactName
import com.example.focaaa.chat.MessageSender
import com.example.focaaa.chat.backup.HistoryBackupCodec
import com.example.focaaa.chat.backup.HistoryConversation
import com.example.focaaa.chat.backup.HistoryMessage
import com.example.focaaa.chat.backup.HistorySnapshot
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(defaultValue = "0") val isRestored: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId"), Index(value = ["conversationId", "createdAtMillis"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val text: String,
    val sender: String,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "1") val deliveryLevel: Int = 1,
    @ColumnInfo(defaultValue = "0") val readReceiptSent: Boolean = false
)

@Entity(
    tableName = "live_draft_edits",
    primaryKeys = ["conversationId", "sessionId", "direction", "sequence"],
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["conversationId", "atMillis"])]
)
data class LiveDraftEditEntity(
    val conversationId: String,
    val sessionId: String,
    val direction: String,
    val sequence: Long,
    val epoch: Long,
    val atMillis: Long,
    val start: Int,
    val removed: String,
    val inserted: String,
    val ciphertext: ByteArray,
    val acknowledged: Boolean = false
)

@Entity(
    tableName = "pairing_qr_consumed_steps",
    primaryKeys = ["inviteId", "kind"],
    indices = [Index("consumedAtMillis")]
)
data class PairingQrConsumedStepEntity(
    val inviteId: String,
    val kind: String,
    val expiresAtMillis: Long,
    val consumedAtMillis: Long
)

@Entity(tableName = "pairing_verification")
data class PairingVerificationEntity(
    @PrimaryKey val groupHandle: String,
    val expectedPeerIdentity: ByteArray,
    val expectedCode: String,
    val localConfirmed: Boolean,
    val peerConfirmed: Boolean,
    val revoked: Boolean
)

@Entity(tableName = "crypto_state")
data class CryptoStateEntity(
    @PrimaryKey val stateId: String,
    val state: ByteArray
)

@Entity(
    tableName = "relay_envelopes",
    indices = [Index(value = ["conversationId", "direction", "state", "createdAtMillis"])]
)
data class RelayEnvelopeEntity(
    @PrimaryKey val envelopeId: String,
    val conversationId: String,
    val direction: String,
    val state: String,
    val payload: ByteArray,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
)

@Entity(tableName = "pairing_session")
data class PairingSessionEntity(
    @PrimaryKey val sessionId: String = CURRENT_SESSION,
    val role: String,
    val stage: String,
    val identityHandle: String,
    val inviteToken: String?,
    val expiresAtMillis: Long,
    val conversationId: String?,
    val keyPackage: ByteArray?,
    val welcome: ByteArray?,
    val ratchetTree: ByteArray?,
    val verificationCode: String?,
    val localControlQueued: Boolean,
    val relayActive: Boolean,
    @ColumnInfo(defaultValue = "0") val liveDraftConsent: Boolean = false
) {
    companion object {
        const val CURRENT_SESSION = "current"
    }
}

data class ConversationContactRow(
    val conversationId: String,
    val title: String,
    val isRestored: Boolean
)

data class BackupMessageStats(val messageCount: Long, val textBytes: Long)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT title FROM conversations WHERE id = :conversationId")
    fun observeConversationTitle(conversationId: String): Flow<String?>

    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun updateConversationTitleRow(conversationId: String, title: String): Int

    @Transaction
    suspend fun renameConversationLocally(conversationId: String, title: String) {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        val clean = requireNotNull(normalizeContactName(title)) {
            "Enter a name from 1 to 40 characters."
        }
        insertConversation(ConversationEntity(conversationId, "Private contact"))
        check(updateConversationTitleRow(conversationId, clean) == 1) {
            "The local conversation name could not be saved."
        }
    }

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis, id")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis, id")
    suspend fun getBackupMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) AS messageCount, COALESCE(SUM(LENGTH(CAST(text AS BLOB))), 0) AS textBytes " +
        "FROM messages WHERE conversationId = :conversationId")
    suspend fun getBackupMessageStats(conversationId: String): BackupMessageStats

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND sender = 'LOCAL_USER' " +
        "AND deliveryLevel IN (1, 2) AND createdAtMillis >= :minimumCreatedAtMillis " +
        "ORDER BY createdAtMillis ASC LIMIT 100")
    suspend fun getOutgoingStatusCandidateIds(conversationId: String, minimumCreatedAtMillis: Long): List<String>

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND sender = 'REMOTE_PARTNER' " +
        "AND readReceiptSent = 0 AND createdAtMillis >= :minimumCreatedAtMillis " +
        "ORDER BY createdAtMillis DESC LIMIT 100")
    suspend fun getUnreportedInboundIds(conversationId: String, minimumCreatedAtMillis: Long): List<String>

    @Query("UPDATE messages SET deliveryLevel = MAX(deliveryLevel, :level) " +
        "WHERE conversationId = :conversationId AND id = :messageId AND sender = 'LOCAL_USER'")
    suspend fun advanceOutgoingDeliveryLevel(conversationId: String, messageId: String, level: Int): Int

    @Query("UPDATE messages SET readReceiptSent = 1 WHERE conversationId = :conversationId " +
        "AND id = :messageId AND sender = 'REMOTE_PARTNER'")
    suspend fun markInboundReadReported(conversationId: String, messageId: String): Int

    @Query("DELETE FROM messages WHERE id = :messageId AND conversationId = :conversationId")
    suspend fun deleteMessage(messageId: String, conversationId: String)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLiveDraftEdit(edit: LiveDraftEditEntity): Long

    @Query("SELECT * FROM live_draft_edits WHERE conversationId = :conversationId AND direction = :direction " +
        "AND atMillis >= :sinceMillis ORDER BY atMillis, sessionId, sequence")
    fun observeLiveDraftHistory(conversationId: String, direction: String, sinceMillis: Long): Flow<List<LiveDraftEditEntity>>

    @Query("SELECT * FROM live_draft_edits WHERE conversationId = :conversationId AND direction = 'LOCAL' " +
        "AND sessionId = :sessionId AND acknowledged = 0 ORDER BY sequence LIMIT 1000")
    suspend fun pendingLiveDraftEdits(conversationId: String, sessionId: String): List<LiveDraftEditEntity>

    @Query("SELECT * FROM live_draft_edits WHERE conversationId = :conversationId AND direction = 'LOCAL' " +
        "AND sessionId = :sessionId ORDER BY sequence LIMIT 1000")
    suspend fun localLiveDraftSessionEdits(conversationId: String, sessionId: String): List<LiveDraftEditEntity>

    @Query("UPDATE live_draft_edits SET acknowledged = 1 WHERE conversationId = :conversationId " +
        "AND direction = 'LOCAL' AND sessionId = :sessionId AND sequence <= :sequence")
    suspend fun acknowledgeLiveDraftEdits(conversationId: String, sessionId: String, sequence: Long)

    @Query("DELETE FROM live_draft_edits WHERE atMillis < :beforeMillis")
    suspend fun purgeExpiredLiveDraftEdits(beforeMillis: Long)

    @Query("DELETE FROM live_draft_edits WHERE conversationId = :conversationId AND direction = 'REMOTE'")
    suspend fun deleteRemoteLiveDraftHistory(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPairingQrConsumedStep(step: PairingQrConsumedStepEntity): Long

    @Query("SELECT * FROM pairing_verification WHERE groupHandle = :groupHandle")
    suspend fun getPairingVerification(groupHandle: String): PairingVerificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairingVerification(verification: PairingVerificationEntity)

    @Query("DELETE FROM pairing_verification WHERE groupHandle = :groupHandle")
    suspend fun deletePairingVerification(groupHandle: String)

    @Query("SELECT * FROM crypto_state WHERE stateId = :stateId")
    suspend fun getCryptoState(stateId: String): CryptoStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCryptoState(state: CryptoStateEntity)

    @Query("DELETE FROM crypto_state WHERE stateId = :stateId")
    suspend fun deleteCryptoState(stateId: String)

    @Query("SELECT * FROM pairing_session WHERE sessionId = :sessionId")
    suspend fun getPairingSession(sessionId: String): PairingSessionEntity?

    @Query("SELECT * FROM pairing_session WHERE stage = 'ACTIVE' AND relayActive = 1 ORDER BY sessionId")
    fun observeActivePairingSessions(): Flow<List<PairingSessionEntity>>

    @Query("SELECT * FROM pairing_session WHERE stage = 'ACTIVE' AND relayActive = 1 ORDER BY sessionId")
    suspend fun getActivePairingSessions(): List<PairingSessionEntity>

    @Query("SELECT conversations.id AS conversationId, conversations.title AS title, conversations.isRestored AS isRestored FROM conversations " +
        "INNER JOIN pairing_session ON pairing_session.sessionId = conversations.id " +
        "WHERE pairing_session.stage = 'ACTIVE' AND pairing_session.relayActive = 1 " +
        "ORDER BY conversations.title COLLATE NOCASE, conversations.id")
    fun observeActiveContacts(): Flow<List<ConversationContactRow>>

    @Query("SELECT DISTINCT conversations.id AS conversationId, conversations.title AS title, conversations.isRestored AS isRestored " +
        "FROM conversations LEFT JOIN pairing_session ON pairing_session.sessionId = conversations.id " +
        "WHERE conversations.isRestored = 1 OR (pairing_session.stage = 'ACTIVE' AND pairing_session.relayActive = 1) " +
        "ORDER BY conversations.title COLLATE NOCASE, conversations.id")
    fun observePrivateContacts(): Flow<List<ConversationContactRow>>

    @Query("SELECT DISTINCT conversations.id AS conversationId, conversations.title AS title, conversations.isRestored AS isRestored " +
        "FROM conversations LEFT JOIN pairing_session ON pairing_session.sessionId = conversations.id " +
        "WHERE conversations.isRestored = 1 OR (pairing_session.stage = 'ACTIVE' AND pairing_session.relayActive = 1) " +
        "ORDER BY conversations.title COLLATE NOCASE, conversations.id LIMIT 101")
    suspend fun getBackupContacts(): List<ConversationContactRow>

    @Transaction
    suspend fun exportHistoryRecords(): List<HistoryConversation> {
        var totalMessages = 0L
        var totalTextBytes = 0L
        val contacts = getBackupContacts().filter { it.conversationId != "synthetic-demo" }
        require(contacts.size <= 100) { "There are too many conversations to back up." }
        return contacts.map { contact ->
            require(contact.title.length in 1..40 && contact.title.isNotBlank()) { "Rename this contact before backing it up." }
            val stats = getBackupMessageStats(contact.conversationId)
            totalMessages += stats.messageCount
            totalTextBytes += stats.textBytes
            require(totalMessages <= 20_000 && totalTextBytes <= HistoryBackupCodec.MAX_FILE_BYTES) {
                "There are too many messages to back up."
            }
            val messages = getBackupMessages(contact.conversationId)
            HistoryConversation(contact.conversationId, contact.title, messages.map { row ->
                val sender = MessageSender.valueOf(row.sender)
                require(sender != MessageSender.DEMO_PARTNER) { "Demo messages cannot be backed up." }
                HistoryMessage(row.id, sender, row.createdAtMillis, row.text)
            })
        }
    }

    @Transaction
    suspend fun importSavedHistory(snapshot: HistorySnapshot) {
        HistoryBackupCodec.validate(snapshot)
        snapshot.conversations.forEach { source ->
            val archiveId = archiveId(snapshot.backupId, source.sourceId)
            val expected = ConversationEntity(archiveId, source.title, isRestored = true)
            val existing = getConversation(archiveId)
            require(existing == null || existing.isRestored) { "A saved contact ID has conflicting contents." }
            if (existing == null) insertConversation(expected)
            source.messages.forEach { message ->
                val entity = MessageEntity(
                    id = archiveId(snapshot.backupId, source.sourceId, message.sourceId),
                    conversationId = archiveId,
                    text = message.text,
                    sender = message.sender.name,
                    createdAtMillis = message.createdAtMillis
                )
                if (insertMessageIfAbsent(entity) == -1L) {
                    require(getMessage(entity.id) == entity) { "A saved message ID has conflicting contents." }
                }
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairingSession(session: PairingSessionEntity)

    @Transaction
    suspend fun savePairingSessionForConversation(session: PairingSessionEntity) {
        require(session.sessionId != PairingSessionEntity.CURRENT_SESSION)
        require(session.sessionId == session.conversationId)
        val current = getPairingSession(PairingSessionEntity.CURRENT_SESSION)
        insertConversation(ConversationEntity(session.sessionId, "Private contact"))
        upsertPairingSession(session)
        if (current?.conversationId == session.conversationId) {
            deletePairingSession(PairingSessionEntity.CURRENT_SESSION)
        }
    }

    @Query("DELETE FROM pairing_session WHERE sessionId = :sessionId")
    suspend fun deletePairingSession(sessionId: String)

    @Query("SELECT * FROM relay_envelopes WHERE envelopeId = :envelopeId")
    suspend fun getRelayEnvelope(envelopeId: String): RelayEnvelopeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRelayEnvelope(envelope: RelayEnvelopeEntity): Long

    @Query("SELECT * FROM relay_envelopes WHERE conversationId = :conversationId AND direction = 'INBOUND' AND state IN ('STAGED', 'COMMITTED') ORDER BY createdAtMillis, envelopeId LIMIT 100")
    suspend fun getPendingInbound(conversationId: String): List<RelayEnvelopeEntity>

    @Query("SELECT * FROM relay_envelopes WHERE direction = 'OUTBOUND' AND state = 'QUEUED' ORDER BY createdAtMillis, envelopeId LIMIT 100")
    suspend fun getPendingOutbound(): List<RelayEnvelopeEntity>

    @Query("UPDATE relay_envelopes SET state = 'COMMITTED' WHERE envelopeId = :envelopeId AND direction = 'INBOUND' AND state = 'STAGED'")
    suspend fun markInboundCommitted(envelopeId: String): Int

    @Query("DELETE FROM relay_envelopes WHERE envelopeId = :envelopeId AND state = :expectedState")
    suspend fun removeRelayEnvelope(envelopeId: String, expectedState: String): Int

    @Transaction
    suspend fun markOutboundAcceptedAndRemove(envelopeId: String) {
        val envelope = getRelayEnvelope(envelopeId)
            ?: error("The queued outbound envelope is missing.")
        check(envelope.direction == "OUTBOUND" && envelope.state == "QUEUED")
        advanceOutgoingDeliveryLevel(envelope.conversationId, envelopeId, 1)
        check(removeRelayEnvelope(envelopeId, "QUEUED") == 1)
    }

    @Query("DELETE FROM relay_envelopes WHERE conversationId = :conversationId")
    suspend fun deleteRelayEnvelopes(conversationId: String)

    @Transaction
    suspend fun stageRelayEnvelope(envelope: RelayEnvelopeEntity): Boolean {
        val existing = getRelayEnvelope(envelope.envelopeId)
        if (existing != null) {
            require(
                existing.conversationId == envelope.conversationId &&
                    existing.direction == envelope.direction &&
                    existing.payload.contentEquals(envelope.payload)
            ) { "A relay envelope ID was reused with different content." }
            return true
        }
        return insertRelayEnvelope(envelope) != -1L
    }

    /** MLS snapshot and outbound retry record become visible together or neither does. */
    @Transaction
    suspend fun commitMlsStateAndOutbound(
        cryptoState: CryptoStateEntity,
        envelope: RelayEnvelopeEntity,
        localMessage: MessageEntity?,
        conversationTitle: String?
    ) {
        require(envelope.direction == "OUTBOUND" && envelope.state == "QUEUED")
        val existing = getRelayEnvelope(envelope.envelopeId)
        if (existing != null) {
            require(
                existing.conversationId == envelope.conversationId &&
                    existing.direction == envelope.direction &&
                    existing.state == envelope.state &&
                    existing.payload.contentEquals(envelope.payload) &&
                    existing.createdAtMillis == envelope.createdAtMillis &&
                    existing.expiresAtMillis == envelope.expiresAtMillis
            ) { "An outbound MLS envelope ID was reused with different content or state." }
        } else {
            check(insertRelayEnvelope(envelope) != -1L) { "Could not commit the outbound MLS envelope." }
        }
        if (localMessage != null) {
            require(
                localMessage.id == envelope.envelopeId &&
                    localMessage.conversationId == envelope.conversationId &&
                    localMessage.sender == "LOCAL_USER" &&
                    localMessage.createdAtMillis == envelope.createdAtMillis &&
                    !localMessage.text.isBlank()
            ) { "The local message does not match its outbound MLS envelope." }
            val title = requireNotNull(conversationTitle)
            require(title.isNotBlank() && title.length <= 120)
            insertConversation(ConversationEntity(localMessage.conversationId, title))
            if (insertMessageIfAbsent(localMessage) == -1L) {
                val existing = getMessage(localMessage.id)
                    ?: error("The local MLS message disappeared during idempotent commit.")
                require(existing == localMessage) { "A local MLS message ID was reused with different content." }
            }
        } else {
            require(conversationTitle == null) { "A conversation title requires a local message." }
        }
        upsertCryptoState(cryptoState)
    }

    /** MLS snapshot, inbox message, and relay acknowledgement-ready state commit atomically. */
    @Transaction
    suspend fun commitMlsStateAndInbound(
        cryptoState: CryptoStateEntity,
        envelopeId: String,
        message: MessageEntity,
        conversationTitle: String
    ) {
        val envelope = getRelayEnvelope(envelopeId)
            ?: error("The inbound MLS envelope is missing from the durable queue.")
        require(
            envelope.direction == "INBOUND" &&
                envelope.conversationId == message.conversationId &&
                envelope.state in setOf("STAGED", "COMMITTED")
        ) { "The inbound MLS envelope is not eligible for an atomic commit." }
        require(conversationTitle.isNotBlank() && conversationTitle.length <= 120)
        insertConversation(ConversationEntity(message.conversationId, conversationTitle))

        val inserted = insertMessageIfAbsent(message)
        if (inserted == -1L) {
            val existing = getMessage(message.id)
                ?: error("An inbound MLS message disappeared during idempotent commit.")
            require(existing == message) { "An inbound MLS message ID was reused with different content." }
        }
        if (envelope.state == "STAGED") {
            check(markInboundCommitted(envelopeId) == 1) {
                "The inbound MLS envelope could not be marked committed."
            }
        }
        upsertCryptoState(cryptoState)
    }

    /** Group-state-only MLS messages still commit their state before becoming ack-eligible. */
    @Transaction
    suspend fun commitMlsStateAndInboundState(
        cryptoState: CryptoStateEntity,
        envelopeId: String,
        conversationId: String,
        verification: PairingVerificationEntity?
    ) {
        val envelope = getRelayEnvelope(envelopeId)
            ?: error("The inbound MLS envelope is missing from the durable queue.")
        require(
            envelope.direction == "INBOUND" &&
                envelope.conversationId == conversationId &&
                envelope.state in setOf("STAGED", "COMMITTED")
        ) { "The inbound MLS envelope is not eligible for an atomic commit." }
        if (envelope.state == "STAGED") {
            check(markInboundCommitted(envelopeId) == 1) {
                "The inbound MLS state envelope could not be marked committed."
            }
        }
        upsertCryptoState(cryptoState)
        if (verification != null) upsertPairingVerification(verification)
    }

    @Transaction
    suspend fun deleteAllConversationData(conversationId: String) {
        deleteRelayEnvelopes(conversationId)
        deleteConversation(conversationId)
    }

    /** Keep the local pairing and its chat removal atomic after the Keystore key is retired. */
    @Transaction
    suspend fun deletePairingAndConversationData(conversationId: String) {
        deletePairingVerification(conversationId)
        deletePairingSession(conversationId)
        deleteRelayEnvelopes(conversationId)
        deleteConversation(conversationId)
    }

    @Transaction
    suspend fun consumePairingQrStepOnce(step: PairingQrConsumedStepEntity, nowMillis: Long): Boolean {
        if (step.expiresAtMillis <= nowMillis) return false
        return insertPairingQrConsumedStep(step) != -1L
    }
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        LiveDraftEditEntity::class,
        PairingQrConsumedStepEntity::class,
        PairingVerificationEntity::class,
        CryptoStateEntity::class,
        RelayEnvelopeEntity::class,
        PairingSessionEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
}

private fun archiveId(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        digest.update(part.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return "archive_" + digest.digest().joinToString("") { "%02x".format(it) }
}
