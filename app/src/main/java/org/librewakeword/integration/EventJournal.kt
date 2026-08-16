package org.librewakeword.integration

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.net.toUri
import org.librewakeword.client.WakewordAudio
import org.librewakeword.client.WakewordEvent

/** Small bounded journal used for diagnostics, deduplication, and delivery accounting. */
class EventJournal(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                event_id TEXT PRIMARY KEY,
                detection_id TEXT NOT NULL,
                stage INTEGER NOT NULL,
                elapsed_nanos INTEGER NOT NULL,
                epoch_millis INTEGER NOT NULL,
                phrase TEXT,
                audio_uri TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX events_time ON events(epoch_millis DESC)")
        createDeliveries(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS deliveries")
            createDeliveries(db)
        } else if (oldVersion < 5) {
            db.execSQL(
                "ALTER TABLE deliveries ADD COLUMN audio_start_elapsed_nanos INTEGER",
            )
        }
    }

    fun record(event: WakewordEvent) {
        writableDatabase.inTransaction {
            insertWithOnConflict(
                "events",
                null,
                ContentValues().apply {
                    put("event_id", event.eventId)
                    put("detection_id", event.detectionId)
                    put("stage", event.stage)
                    put("elapsed_nanos", event.occurredAtElapsedRealtimeNanos)
                    put("epoch_millis", event.occurredAtEpochMillis)
                    put("phrase", event.phrase)
                    put("audio_uri", event.audio?.uri?.toString())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            execSQL(
                "DELETE FROM events WHERE event_id NOT IN " +
                    "(SELECT event_id FROM events ORDER BY epoch_millis DESC LIMIT $MAX_EVENTS)",
            )
        }
    }

    @Synchronized
    fun queueDelivery(component: String, event: WakewordEvent) {
        writableDatabase.inTransaction {
            insertWithOnConflict(
                "deliveries",
                null,
                eventValues(event).apply { put("component", component) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            execSQL(
                "DELETE FROM deliveries WHERE rowid NOT IN " +
                    "(SELECT rowid FROM deliveries ORDER BY epoch_millis DESC LIMIT $MAX_DELIVERIES)",
            )
        }
    }

    @Synchronized
    fun acknowledgeDelivery(component: String, eventId: String) {
        writableDatabase.delete(
            "deliveries",
            "component = ? AND event_id = ?",
            arrayOf(component, eventId),
        )
    }

    @Synchronized
    fun pendingDeliveries(): List<Pair<String, WakewordEvent>> {
        val result = mutableListOf<Pair<String, WakewordEvent>>()
        readableDatabase.query("deliveries", null, null, null, null, null, "epoch_millis ASC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val component = cursor.string("component")
                    val audioUri = cursor.nullableString("audio_uri")
                    val audio = audioUri?.let {
                        WakewordAudio(
                            uri = it.toUri(),
                            mimeType = cursor.string("audio_mime"),
                            sampleRateHz = cursor.int("sample_rate"),
                            channelCount = cursor.int("channel_count"),
                            encoding = cursor.int("encoding"),
                            frameCount = cursor.long("frame_count"),
                            expiresAtEpochMillis = cursor.long("expires_at"),
                            startTimeElapsedRealtimeNanos =
                                cursor.nullableLong("audio_start_elapsed_nanos"),
                        )
                    }
                    result += component to WakewordEvent(
                        eventId = cursor.string("event_id"),
                        detectionId = cursor.string("detection_id"),
                        stage = cursor.int("stage"),
                        occurredAtElapsedRealtimeNanos = cursor.long("elapsed_nanos"),
                        occurredAtEpochMillis = cursor.long("epoch_millis"),
                        phrase = cursor.nullableString("phrase"),
                        localeTag = cursor.nullableString("locale"),
                        audio = audio,
                    )
                }
            }
        return result
    }

    private fun eventValues(event: WakewordEvent) = ContentValues().apply {
        put("event_id", event.eventId)
        put("detection_id", event.detectionId)
        put("stage", event.stage)
        put("elapsed_nanos", event.occurredAtElapsedRealtimeNanos)
        put("epoch_millis", event.occurredAtEpochMillis)
        put("phrase", event.phrase)
        put("locale", event.localeTag)
        put("audio_uri", event.audio?.uri?.toString())
        put("audio_mime", event.audio?.mimeType)
        put("sample_rate", event.audio?.sampleRateHz)
        put("channel_count", event.audio?.channelCount)
        put("encoding", event.audio?.encoding)
        put("frame_count", event.audio?.frameCount)
        put("expires_at", event.audio?.expiresAtEpochMillis)
        put("audio_start_elapsed_nanos", event.audio?.startTimeElapsedRealtimeNanos)
    }

    private fun createDeliveries(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS deliveries (
                component TEXT NOT NULL,
                event_id TEXT NOT NULL,
                detection_id TEXT NOT NULL,
                stage INTEGER NOT NULL,
                elapsed_nanos INTEGER NOT NULL,
                epoch_millis INTEGER NOT NULL,
                phrase TEXT,
                locale TEXT,
                audio_uri TEXT,
                audio_mime TEXT,
                sample_rate INTEGER,
                channel_count INTEGER,
                encoding INTEGER,
                frame_count INTEGER,
                expires_at INTEGER,
                audio_start_elapsed_nanos INTEGER,
                PRIMARY KEY (component, event_id)
            )""".trimIndent(),
        )
    }

    private fun android.database.Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun android.database.Cursor.string(name: String) = getString(index(name))
    private fun android.database.Cursor.nullableString(name: String) =
        if (isNull(index(name))) null else getString(index(name))
    private fun android.database.Cursor.int(name: String) = getInt(index(name))
    private fun android.database.Cursor.long(name: String) = getLong(index(name))
    private fun android.database.Cursor.nullableLong(name: String) =
        if (isNull(index(name))) null else getLong(index(name))
    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val NAME = "wakeword-events-v1.db"
        const val VERSION = 5
        const val MAX_EVENTS = 256
        const val MAX_DELIVERIES = 256
    }
}
