package com.example.focaaa.chat.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.example.focaaa.chat.Conversation
import com.example.focaaa.chat.ConversationMessage
import com.example.focaaa.chat.ConversationContact
import com.example.focaaa.chat.ConversationRepository
import com.example.focaaa.chat.MessageSender
import com.example.focaaa.chat.MessageDeliveryStatus
import com.example.focaaa.chat.contactDisplayName
import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.crypto.AtomicMlsStateStore
import com.example.focaaa.chat.crypto.KeystoreRotatingCryptoStateStore
import com.example.focaaa.chat.crypto.MlsInboundHandoff
import com.example.focaaa.chat.crypto.MlsOutboundHandoff
import com.example.focaaa.chat.crypto.OpenMlsSnapshotStoreProbe
import com.example.focaaa.chat.pairing.PairingInviteId
import com.example.focaaa.chat.pairing.PairingQrKind
import com.example.focaaa.chat.pairing.PairingQrReplayStore
import com.example.focaaa.chat.pairing.PairingVerificationCode
import com.example.focaaa.chat.pairing.PairingVerificationSnapshot
import com.example.focaaa.chat.pairing.PairingVerificationStore
import com.example.focaaa.chat.pairing.PairingSession
import com.example.focaaa.chat.pairing.PairingSessionStore
import com.example.focaaa.chat.pairing.PairingRole
import com.example.focaaa.chat.pairing.PairingStage
import com.example.focaaa.chat.pairing.CURRENT_PAIRING_SESSION_ID
import com.example.focaaa.chat.pairing.pairingSessionStorageKey
import com.example.focaaa.chat.relay.WorkerRelayGateway
import com.example.focaaa.chat.relay.RelayQueueStore
import com.example.focaaa.chat.relay.RelayQueueState
import com.example.focaaa.chat.relay.RelayGateway
import com.example.focaaa.chat.relay.StoredRelayEnvelope
import com.example.focaaa.chat.backup.HistoryBackupCodec
import com.example.focaaa.chat.backup.HistorySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomConversationRepository private constructor(
    private val database: ConversationDatabase,
    private val appContext: Context
) : ConversationRepository {
    private val dao = database.conversationDao()

    suspend fun saveLiveDraftEdit(edit: LiveDraftEditEntity): Boolean = dao.insertLiveDraftEdit(edit) != -1L

    fun liveDraftHistory(conversationId: String, direction: String): Flow<List<LiveDraftEditEntity>> =
        dao.observeLiveDraftHistory(conversationId, direction, System.currentTimeMillis() - LIVE_DRAFT_RETENTION_MS)

    suspend fun pendingLiveDraftEdits(conversationId: String, sessionId: String): List<LiveDraftEditEntity> =
        dao.pendingLiveDraftEdits(conversationId, sessionId)

    suspend fun localLiveDraftSessionEdits(conversationId: String, sessionId: String): List<LiveDraftEditEntity> =
        dao.localLiveDraftSessionEdits(conversationId, sessionId)

    suspend fun acknowledgeLiveDraftEdits(conversationId: String, sessionId: String, sequence: Long) =
        dao.acknowledgeLiveDraftEdits(conversationId, sessionId, sequence)

    suspend fun purgeExpiredLiveDraftEdits() =
        dao.purgeExpiredLiveDraftEdits(System.currentTimeMillis() - LIVE_DRAFT_RETENTION_MS)
    val pairingQrReplayStore: PairingQrReplayStore = RoomPairingQrReplayStore(dao)
    val pairingVerificationStore: PairingVerificationStore = RoomPairingVerificationStore(dao)
    val pairingSessionStore: PairingSessionStore = RoomPairingSessionStore(dao)
    private val roomCryptoStateStore = RoomEncryptedCryptoStateStore(dao)
    val encryptedCryptoStateStore = KeystoreRotatingCryptoStateStore(appContext, roomCryptoStateStore)
    val openMlsSnapshotStoreProbe = OpenMlsSnapshotStoreProbe(encryptedCryptoStateStore)
    val relayQueueStore: RelayQueueStore = RoomRelayQueueStore(dao)
    override val conversation = Conversation(DEMO_CONVERSATION_ID, "Demo partner")
    override val messages: Flow<List<ConversationMessage>> = dao.observeMessages(conversation.id).map { rows ->
        rows.map { row ->
            ConversationMessage(
                id = row.id,
                conversationId = row.conversationId,
                text = row.text,
                sender = MessageSender.valueOf(row.sender),
                createdAtMillis = row.createdAtMillis,
                deliveryStatus = MessageDeliveryStatus.fromLevel(row.deliveryLevel)
            )
        }
    }

    fun messagesFor(conversationId: String): Flow<List<ConversationMessage>> {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        return dao.observeMessages(conversationId).map { rows ->
            rows.map { row ->
                ConversationMessage(
                    id = row.id,
                    conversationId = row.conversationId,
                    text = row.text,
                    sender = MessageSender.valueOf(row.sender),
                    createdAtMillis = row.createdAtMillis,
                    deliveryStatus = MessageDeliveryStatus.fromLevel(row.deliveryLevel)
                )
            }
        }
    }

    fun observeConversationTitle(conversationId: String): Flow<String> {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        return dao.observeConversationTitle(conversationId).map(::contactDisplayName)
    }

    fun observeContacts(): Flow<List<ConversationContact>> =
        dao.observePrivateContacts().map { rows ->
            rows.map { row -> ConversationContact(row.conversationId, com.example.focaaa.chat.contactDisplayName(row.title), row.isRestored) }
        }

    fun observeActiveContacts(): Flow<List<ConversationContact>> =
        dao.observeActiveContacts().map { rows ->
            rows.map { row -> ConversationContact(row.conversationId, contactDisplayName(row.title)) }
        }

    suspend fun exportHistorySnapshot(): HistorySnapshot {
        val snapshot = HistorySnapshot(
            backupId = UUID.randomUUID().toString().replace("-", ""),
            createdAtMillis = System.currentTimeMillis(),
            conversations = dao.exportHistoryRecords()
        )
        HistoryBackupCodec.validate(snapshot)
        return snapshot
    }

    suspend fun importHistorySnapshot(snapshot: HistorySnapshot) = dao.importSavedHistory(snapshot)

    suspend fun renameConversation(conversationId: String, title: String) {
        dao.renameConversationLocally(conversationId, title)
    }

    suspend fun deleteLocalMessage(conversationId: String, messageId: String) {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        dao.deleteMessage(messageId, conversationId)
    }

    suspend fun outgoingStatusCandidateIds(conversationId: String): List<String> =
        dao.getOutgoingStatusCandidateIds(conversationId, System.currentTimeMillis() - RECEIPT_QUERY_WINDOW_MS)

    suspend fun unreportedInboundIds(conversationId: String): List<String> =
        dao.getUnreportedInboundIds(conversationId, System.currentTimeMillis() - RECEIPT_QUERY_WINDOW_MS)

    suspend fun advanceOutgoingStatus(conversationId: String, messageId: String, level: Int) {
        require(level in 1..3)
        dao.advanceOutgoingDeliveryLevel(conversationId, messageId, level)
    }

    suspend fun markInboundReadReported(conversationId: String, messageId: String) {
        dao.markInboundReadReported(conversationId, messageId)
    }

    suspend fun deletePrivateConversation(conversationId: String) {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        dao.deletePairingAndConversationData(conversationId)
    }

    override suspend fun sendDemoMessage(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        dao.insertConversation(ConversationEntity(conversation.id, conversation.title))
        dao.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                text = cleanText,
                sender = MessageSender.LOCAL_USER.name,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteMessage(messageId: String) = dao.deleteMessage(messageId, conversation.id)

    override suspend fun deleteAllLocalData() = dao.deleteAllConversationData(conversation.id)

    companion object {
        private const val DEMO_CONVERSATION_ID = "synthetic-demo"
        private const val RECEIPT_QUERY_WINDOW_MS = 6L * 24 * 60 * 60 * 1000
        private const val LIVE_DRAFT_RETENTION_MS = 24L * 60 * 60 * 1000

        fun open(context: Context): RoomConversationRepository {
            synchronized(this) {
                instance?.let { return it }
                System.loadLibrary("sqlcipher")
                val key = DatabaseKeyManager(context).getOrCreatePassphrase()
                val factory = SupportOpenHelperFactory(key)
                val database = Room.databaseBuilder(
                    context.applicationContext,
                    ConversationDatabase::class.java,
                    DatabaseKeyManager.DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .addCallback(seedDemoMessages())
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                database.openHelper.writableDatabase
                return RoomConversationRepository(database, context.applicationContext).also { instance = it }
            }
        }

        @Volatile private var instance: RoomConversationRepository? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val timestamp = System.currentTimeMillis()
                insertMigrationDemoMessage(
                    db,
                    "demo-partner-message-2",
                    "I saved us a quiet table by the window.",
                    timestamp - 60_000L
                )
                insertMigrationDemoMessage(
                    db,
                    "demo-partner-message-3",
                    "Looking forward to seeing you!",
                    timestamp
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pairing_qr_consumed_steps` (
                        `inviteId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `expiresAtMillis` INTEGER NOT NULL,
                        `consumedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`inviteId`, `kind`)
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pairing_qr_consumed_steps_consumedAtMillis` " +
                        "ON `pairing_qr_consumed_steps` (`consumedAtMillis`)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pairing_verification` (
                        `groupHandle` TEXT NOT NULL,
                        `expectedPeerIdentity` BLOB NOT NULL,
                        `expectedCode` TEXT NOT NULL,
                        `localConfirmed` INTEGER NOT NULL,
                        `peerConfirmed` INTEGER NOT NULL,
                        `revoked` INTEGER NOT NULL,
                        PRIMARY KEY(`groupHandle`)
                    )""".trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `crypto_state` (
                        `stateId` TEXT NOT NULL,
                        `state` BLOB NOT NULL,
                        PRIMARY KEY(`stateId`)
                    )""".trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `relay_envelopes` (
                        `envelopeId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `payload` BLOB NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `expiresAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`envelopeId`)
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_relay_envelopes_conversationId_direction_state_createdAtMillis` " +
                        "ON `relay_envelopes` (`conversationId`, `direction`, `state`, `createdAtMillis`)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pairing_session` (
                        `sessionId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `stage` TEXT NOT NULL,
                        `identityHandle` TEXT NOT NULL,
                        `inviteToken` TEXT,
                        `expiresAtMillis` INTEGER NOT NULL,
                        `conversationId` TEXT,
                        `keyPackage` BLOB,
                        `welcome` BLOB,
                        `ratchetTree` BLOB,
                        `verificationCode` TEXT,
                        `localControlQueued` INTEGER NOT NULL,
                        `relayActive` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )""".trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE pairing_session SET sessionId = conversationId " +
                        "WHERE sessionId = 'current' AND stage = 'ACTIVE' AND relayActive = 1 " +
                        "AND conversationId IS NOT NULL AND length(trim(conversationId)) > 0"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN isRestored INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveryLevel INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE messages ADD COLUMN readReceiptSent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET deliveryLevel = 0 WHERE sender = 'LOCAL_USER' " +
                    "AND id IN (SELECT envelopeId FROM relay_envelopes WHERE direction = 'OUTBOUND' AND state = 'QUEUED')")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `live_draft_edits` (
                    `conversationId` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `direction` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `epoch` INTEGER NOT NULL,
                    `atMillis` INTEGER NOT NULL,
                    `start` INTEGER NOT NULL,
                    `removed` TEXT NOT NULL,
                    `inserted` TEXT NOT NULL,
                    `ciphertext` BLOB NOT NULL,
                    `acknowledged` INTEGER NOT NULL,
                    PRIMARY KEY(`conversationId`, `sessionId`, `direction`, `sequence`),
                    FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_draft_edits_conversationId_atMillis` " +
                    "ON `live_draft_edits` (`conversationId`, `atMillis`)")
            }
        }

        private fun insertMigrationDemoMessage(
            db: SupportSQLiteDatabase,
            messageId: String,
            text: String,
            createdAtMillis: Long
        ) {
            db.execSQL(
                """INSERT OR IGNORE INTO messages (id, conversationId, text, sender, createdAtMillis)
                    SELECT ?, ?, ?, ?, ? WHERE EXISTS (
                        SELECT 1 FROM conversations WHERE id = ?
                    ) AND EXISTS (
                        SELECT 1 FROM messages WHERE conversationId = ?
                    )""".trimIndent(),
                arrayOf<Any?>(
                    messageId,
                    DEMO_CONVERSATION_ID,
                    text,
                    MessageSender.DEMO_PARTNER.name,
                    createdAtMillis,
                    DEMO_CONVERSATION_ID,
                    DEMO_CONVERSATION_ID
                )
            )
        }

        private fun seedDemoMessages() = object : RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val timestamp = System.currentTimeMillis()
                db.execSQL("INSERT INTO conversations (id, title) VALUES (?, ?)", arrayOf(DEMO_CONVERSATION_ID, "Demo partner"))
                db.execSQL(
                    "INSERT INTO messages (id, conversationId, text, sender, createdAtMillis) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>("demo-partner-message", DEMO_CONVERSATION_ID, "Hey, are we still on for tonight?", MessageSender.DEMO_PARTNER.name, timestamp - 180_000L)
                )
                db.execSQL(
                    "INSERT INTO messages (id, conversationId, text, sender, createdAtMillis) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>("demo-local-message", DEMO_CONVERSATION_ID, "Yes. I'll message you when I'm leaving.", MessageSender.LOCAL_USER.name, timestamp - 120_000L)
                )
                insertMigrationDemoMessage(
                    db,
                    "demo-partner-message-2",
                    "I saved us a quiet table by the window.",
                    timestamp - 60_000L
                )
                insertMigrationDemoMessage(
                    db,
                    "demo-partner-message-3",
                    "Looking forward to seeing you!",
                    timestamp
                )
            }
        }
    }
}

private class RoomPairingQrReplayStore(
    private val dao: ConversationDao
) : PairingQrReplayStore {
    override suspend fun consumeOnce(
        inviteId: PairingInviteId,
        kind: PairingQrKind,
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean = dao.consumePairingQrStepOnce(
        step = PairingQrConsumedStepEntity(
            inviteId = inviteId.value,
            kind = kind.name,
            expiresAtMillis = expiresAtMillis,
            consumedAtMillis = nowMillis
        ),
        nowMillis = nowMillis
    )
}

private class RoomPairingVerificationStore(
    private val dao: ConversationDao
) : PairingVerificationStore {
    override suspend fun read(group: CryptoGroupHandle): PairingVerificationSnapshot? =
        dao.getPairingVerification(group.value)?.let { entity ->
            PairingVerificationSnapshot(
                expectedPeerIdentity = entity.expectedPeerIdentity.copyOf(),
                expectedCode = PairingVerificationCode(entity.expectedCode),
                localConfirmed = entity.localConfirmed,
                peerConfirmed = entity.peerConfirmed,
                revoked = entity.revoked
            )
        }

    override suspend fun write(group: CryptoGroupHandle, snapshot: PairingVerificationSnapshot) {
        dao.upsertPairingVerification(
            PairingVerificationEntity(
                groupHandle = group.value,
                expectedPeerIdentity = snapshot.expectedPeerIdentity.copyOf(),
                expectedCode = snapshot.expectedCode.value,
                localConfirmed = snapshot.localConfirmed,
                peerConfirmed = snapshot.peerConfirmed,
                revoked = snapshot.revoked
            )
        )
    }

    override suspend fun delete(group: CryptoGroupHandle) {
        dao.deletePairingVerification(group.value)
    }
}

private class RoomPairingSessionStore(
    private val dao: ConversationDao
) : PairingSessionStore {
    override suspend fun read(sessionId: String): PairingSession? =
        dao.getPairingSession(sessionId)?.toPairingSession()

    override fun observeActive(): Flow<List<PairingSession>> =
        dao.observeActivePairingSessions().map { rows -> rows.map(PairingSessionEntity::toPairingSession) }

    override suspend fun write(sessionId: String, session: PairingSession) {
        require(sessionId == pairingSessionStorageKey(session)) {
            "Pairing session must be stored under its pending or active conversation key."
        }
        require(session.identityHandle.length in 1..128)
        require(session.inviteToken == null || session.inviteToken.length <= 128)
        require(session.conversationId == null || session.conversationId.length <= 128)
        require(session.keyPackage == null || session.keyPackage.size <= 16 * 1024)
        require(session.welcome == null || session.welcome.size <= 64 * 1024)
        require(session.ratchetTree == null || session.ratchetTree.size <= 64 * 1024)
        val entity = PairingSessionEntity(
            sessionId = sessionId,
            role = session.role.name,
            stage = session.stage.name,
            identityHandle = session.identityHandle,
            inviteToken = session.inviteToken,
            expiresAtMillis = session.expiresAtMillis,
            conversationId = session.conversationId,
            keyPackage = session.keyPackage?.copyOf(),
            welcome = session.welcome?.copyOf(),
            ratchetTree = session.ratchetTree?.copyOf(),
            verificationCode = session.verificationCode,
            localControlQueued = session.localControlQueued,
            relayActive = session.relayActive
        )
        if (sessionId != CURRENT_PAIRING_SESSION_ID) {
            dao.savePairingSessionForConversation(entity)
        } else {
            dao.upsertPairingSession(entity)
        }
    }

    override suspend fun clear(sessionId: String) = dao.deletePairingSession(sessionId)
}

private fun PairingSessionEntity.toPairingSession(): PairingSession = PairingSession(
    role = PairingRole.valueOf(role),
    stage = PairingStage.valueOf(stage),
    identityHandle = identityHandle,
    inviteToken = inviteToken,
    expiresAtMillis = expiresAtMillis,
    conversationId = conversationId,
    keyPackage = keyPackage?.copyOf(),
    welcome = welcome?.copyOf(),
    ratchetTree = ratchetTree?.copyOf(),
    verificationCode = verificationCode,
    localControlQueued = localControlQueued,
    relayActive = relayActive
)

private class RoomEncryptedCryptoStateStore(
    private val dao: ConversationDao
) : AtomicMlsStateStore {
    override suspend fun read(stateId: String): ByteArray? {
        validateStateId(stateId)
        return dao.getCryptoState(stateId)?.state?.copyOf()
    }

    override suspend fun write(stateId: String, state: ByteArray) {
        validateStateId(stateId)
        require(state.isNotEmpty()) { "Serialized MLS state must not be empty." }
        dao.upsertCryptoState(CryptoStateEntity(stateId = stateId, state = state.copyOf()))
    }

    override suspend fun writeAndEnqueueOutbound(
        stateId: String,
        state: ByteArray,
        envelope: StoredRelayEnvelope,
        localMessage: MlsOutboundHandoff?
    ) {
        validateStateId(stateId)
        validateState(state)
        validateRelayEnvelope(envelope)
        if (localMessage != null) {
            require(localMessage.envelopeId == envelope.envelopeId)
            require(localMessage.conversationId == envelope.conversationId)
            require(localMessage.createdAtMillis == envelope.createdAtMillis)
            require(localMessage.conversationTitle.isNotBlank() && localMessage.conversationTitle.length <= 120)
            require(localMessage.plaintext.isNotBlank() && localMessage.plaintext.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES)
        }
        dao.commitMlsStateAndOutbound(
            cryptoState = CryptoStateEntity(stateId, state.copyOf()),
            envelope = RelayEnvelopeEntity(
                envelopeId = envelope.envelopeId,
                conversationId = envelope.conversationId,
                direction = OUTBOUND,
                state = QUEUED,
                payload = envelope.payloadCopy(),
                createdAtMillis = envelope.createdAtMillis,
                expiresAtMillis = envelope.expiresAtMillis
            ),
            localMessage = localMessage?.let {
                MessageEntity(
                    id = it.envelopeId,
                    conversationId = it.conversationId,
                    text = it.plaintext,
                    sender = MessageSender.LOCAL_USER.name,
                    createdAtMillis = it.createdAtMillis,
                    deliveryLevel = 0
                )
            },
            conversationTitle = localMessage?.conversationTitle
        )
    }

    override suspend fun writeAndCommitInbound(
        stateId: String,
        state: ByteArray,
        handoff: MlsInboundHandoff
    ) {
        validateStateId(stateId)
        validateState(state)
        require(handoff.envelopeId.isNotBlank() && handoff.envelopeId.length <= 128)
        require(handoff.conversationId.isNotBlank() && handoff.conversationId.length <= 128)
        require(handoff.messageId.isNotBlank() && handoff.messageId.length <= 128)
        require(handoff.sender == MessageSender.REMOTE_PARTNER) {
            "An inbound MLS handoff must be attributed to the authenticated remote partner."
        }
        require(handoff.plaintext.isNotEmpty() && handoff.plaintext.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES)
        require(handoff.createdAtMillis >= 0)
        dao.commitMlsStateAndInbound(
            cryptoState = CryptoStateEntity(stateId, state.copyOf()),
            envelopeId = handoff.envelopeId,
            message = MessageEntity(
                id = handoff.messageId,
                conversationId = handoff.conversationId,
                text = handoff.plaintext,
                sender = handoff.sender.name,
                createdAtMillis = handoff.createdAtMillis
            ),
            conversationTitle = handoff.conversationTitle
        )
    }

    override suspend fun writeAndCommitInboundState(
        stateId: String,
        state: ByteArray,
        envelopeId: String,
        conversationId: String,
        verificationSnapshot: PairingVerificationSnapshot?
    ) {
        validateStateId(stateId)
        validateState(state)
        require(envelopeId.isNotBlank() && envelopeId.length <= 128)
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        dao.commitMlsStateAndInboundState(
            cryptoState = CryptoStateEntity(stateId, state.copyOf()),
            envelopeId = envelopeId,
            conversationId = conversationId,
            verification = verificationSnapshot?.let { snapshot ->
                PairingVerificationEntity(
                    groupHandle = conversationId,
                    expectedPeerIdentity = snapshot.expectedPeerIdentity.copyOf(),
                    expectedCode = snapshot.expectedCode.value,
                    localConfirmed = snapshot.localConfirmed,
                    peerConfirmed = snapshot.peerConfirmed,
                    revoked = snapshot.revoked
                )
            }
        )
    }

    override suspend fun delete(stateId: String) {
        validateStateId(stateId)
        dao.deleteCryptoState(stateId)
    }

    private fun validateStateId(stateId: String) {
        require(stateId.isNotBlank() && stateId.length <= 256) {
            "MLS state ID must contain between 1 and 256 characters."
        }
    }

    private fun validateState(state: ByteArray) {
        require(state.isNotEmpty() && state.size <= MAX_STATE_BYTES) { "Serialized MLS state is invalid." }
    }

    private fun validateRelayEnvelope(envelope: StoredRelayEnvelope) {
        require(envelope.envelopeId.isNotBlank() && envelope.envelopeId.length <= 128)
        require(envelope.conversationId.isNotBlank() && envelope.conversationId.length <= 128)
        require(envelope.payload.isNotEmpty() && envelope.payload.size <= MAX_ENVELOPE_BYTES)
        require(envelope.expiresAtMillis > envelope.createdAtMillis)
    }

    private companion object {
        const val OUTBOUND = "OUTBOUND"
        const val QUEUED = "QUEUED"
        const val MAX_STATE_BYTES = 16 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = 48 * 1024
        const val MAX_MESSAGE_BYTES = MAX_ENVELOPE_BYTES
    }
}

private class RoomRelayQueueStore(
    private val dao: ConversationDao
) : RelayQueueStore {
    override suspend fun stageInbound(envelope: StoredRelayEnvelope) {
        validate(envelope)
        check(
            dao.stageRelayEnvelope(
                RelayEnvelopeEntity(
                    envelopeId = envelope.envelopeId,
                    conversationId = envelope.conversationId,
                    direction = INBOUND,
                    state = STAGED,
                    payload = envelope.payloadCopy(),
                    createdAtMillis = envelope.createdAtMillis,
                    expiresAtMillis = envelope.expiresAtMillis
                )
            )
        ) { "Could not stage the relay envelope in encrypted local storage." }
    }

    override suspend fun pendingInbound(conversationId: String): List<StoredRelayEnvelope> =
        dao.getPendingInbound(conversationId).map(::toStored)

    override suspend fun removeInboundAfterAcknowledgement(envelopeId: String) {
        dao.removeRelayEnvelope(envelopeId, COMMITTED)
    }

    override suspend fun enqueueOutbound(envelope: StoredRelayEnvelope) {
        validate(envelope)
        check(
            dao.stageRelayEnvelope(
                RelayEnvelopeEntity(
                    envelopeId = envelope.envelopeId,
                    conversationId = envelope.conversationId,
                    direction = OUTBOUND,
                    state = QUEUED,
                    payload = envelope.payloadCopy(),
                    createdAtMillis = envelope.createdAtMillis,
                    expiresAtMillis = envelope.expiresAtMillis
                )
            )
        ) { "Could not queue the encrypted outgoing envelope." }
    }

    override suspend fun pendingOutbound(): List<StoredRelayEnvelope> =
        dao.getPendingOutbound().map(::toStored)

    override suspend fun removeOutboundAfterAcceptance(envelopeId: String) {
        dao.markOutboundAcceptedAndRemove(envelopeId)
    }

    private fun toStored(row: RelayEnvelopeEntity) = StoredRelayEnvelope(
        envelopeId = row.envelopeId,
        conversationId = row.conversationId,
        payload = row.payload.copyOf(),
        createdAtMillis = row.createdAtMillis,
        expiresAtMillis = row.expiresAtMillis,
        state = RelayQueueState.valueOf(row.state)
    )

    private fun validate(envelope: StoredRelayEnvelope) {
        require(envelope.envelopeId.isNotBlank() && envelope.envelopeId.length <= 128)
        require(envelope.conversationId.isNotBlank() && envelope.conversationId.length <= 128)
        require(envelope.payload.isNotEmpty() && envelope.payload.size <= WorkerRelayGateway.MAX_ENVELOPE_BYTES)
        require(envelope.expiresAtMillis > envelope.createdAtMillis)
    }

    private companion object {
        const val INBOUND = "INBOUND"
        const val OUTBOUND = "OUTBOUND"
        const val STAGED = "STAGED"
        const val COMMITTED = "COMMITTED"
        const val QUEUED = "QUEUED"
    }
}
