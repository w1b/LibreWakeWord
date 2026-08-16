package org.librewakeword.sample

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.librewakeword.client.WakewordAvailability
import java.io.File
import java.util.UUID

data class SampleEntry(
    val id: String,
    val occurredAtEpochMillis: Long,
    val title: String,
    val detail: String,
    val recordingName: String? = null,
    val active: Boolean = false,
)

data class SampleAvailability(
    val state: Int = WakewordAvailability.STATE_INACTIVE,
    val phrase: String? = null,
)

data class SampleAccessSelections(
    val observers: Set<String> = emptySet(),
    val handler: String? = null,
) {
    fun enabledComponents(): Set<String> = observers + listOfNotNull(handler)
}

/** Small persistent history used to make every sample callback visible in the app itself. */
object SampleHistory {
    const val ACTION_CHANGED = "org.librewakeword.sample.HISTORY_CHANGED"

    private const val PREFERENCES = "sample_history"
    private const val ENTRIES = "entries"
    private const val AVAILABILITY_STATE = "availability_state"
    private const val AVAILABILITY_PHRASE = "availability_phrase"
    private const val ENABLED_OBSERVERS = "enabled_observers"
    private const val ENABLED_HANDLER = "enabled_handler"
    private const val MAX_ENTRIES = 64
    private const val RECORDINGS_DIRECTORY = "recordings"
    private val lock = Any()

    fun load(context: Context): List<SampleEntry> = synchronized(lock) {
        readEntries(context)
    }

    fun contains(context: Context, id: String): Boolean = synchronized(lock) {
        readEntries(context).any { it.id == id }
    }

    fun put(context: Context, entry: SampleEntry) {
        synchronized(lock) {
            val entries = readEntries(context).toMutableList()
            val existingIndex = entries.indexOfFirst { it.id == entry.id }
            if (existingIndex >= 0) {
                val previous = entries[existingIndex]
                if (previous.recordingName != entry.recordingName) {
                    deleteRecording(context, previous.recordingName)
                }
                entries[existingIndex] = entry
            } else {
                entries.add(0, entry)
            }
            while (entries.size > MAX_ENTRIES) {
                deleteRecording(context, entries.removeAt(entries.lastIndex).recordingName)
            }
            writeEntries(context, entries)
        }
        notifyChanged(context)
    }

    fun update(context: Context, id: String, transform: (SampleEntry) -> SampleEntry) {
        var changed = false
        synchronized(lock) {
            val entries = readEntries(context).toMutableList()
            val index = entries.indexOfFirst { it.id == id }
            if (index >= 0) {
                val previous = entries[index]
                val replacement = transform(previous)
                if (replacement != previous) {
                    if (previous.recordingName != replacement.recordingName) {
                        deleteRecording(context, previous.recordingName)
                    }
                    entries[index] = replacement
                    writeEntries(context, entries)
                    changed = true
                }
            }
        }
        if (changed) notifyChanged(context)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
                remove(ENTRIES)
            }
            recordingsDirectory(context).listFiles()?.forEach(File::delete)
        }
        notifyChanged(context)
    }

    fun availability(context: Context): SampleAvailability {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return SampleAvailability(
            state = preferences.getInt(
                AVAILABILITY_STATE,
                WakewordAvailability.STATE_INACTIVE,
            ),
            phrase = preferences.getString(AVAILABILITY_PHRASE, null),
        )
    }

    fun setAvailability(context: Context, availability: WakewordAvailability) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putInt(AVAILABILITY_STATE, availability.state)
            if (availability.phrase == null) remove(AVAILABILITY_PHRASE)
            else putString(AVAILABILITY_PHRASE, availability.phrase)
        }
        notifyChanged(context)
    }

    fun accessSelections(context: Context): SampleAccessSelections {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return SampleAccessSelections(
            observers = preferences.getStringSet(ENABLED_OBSERVERS, emptySet()).orEmpty().toSet(),
            handler = preferences.getString(ENABLED_HANDLER, null),
        )
    }

    fun markEnabled(context: Context, componentName: String, handler: Boolean) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit {
            if (handler) {
                putString(ENABLED_HANDLER, componentName)
            } else {
                putStringSet(
                    ENABLED_OBSERVERS,
                    preferences.getStringSet(ENABLED_OBSERVERS, emptySet()).orEmpty() + componentName,
                )
            }
        }
        notifyChanged(context)
    }

    fun newRecording(context: Context, prefix: String): File {
        val safePrefix = prefix.filter { it.isLetterOrDigit() || it == '-' }.take(32)
            .ifEmpty { "recording" }
        return recordingsDirectory(context).resolve("$safePrefix-${UUID.randomUUID()}.wav")
    }

    fun recording(context: Context, name: String): File? {
        if (name != File(name).name) return null
        val file = recordingsDirectory(context).resolve(name)
        return file.takeIf(File::isFile)
    }

    private fun recordingsDirectory(context: Context): File =
        context.filesDir.resolve(RECORDINGS_DIRECTORY).also(File::mkdirs)

    private fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    private fun deleteRecording(context: Context, name: String?) {
        name?.let { recording(context, it)?.delete() }
    }

    private fun readEntries(context: Context): List<SampleEntry> {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(
                        SampleEntry(
                            id = item.getString("id"),
                            occurredAtEpochMillis = item.getLong("time"),
                            title = item.getString("title"),
                            detail = item.getString("detail"),
                            recordingName = item.optString("recording").ifEmpty { null },
                            active = item.optBoolean("active", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(context: Context, entries: List<SampleEntry>) {
        val encoded = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("time", entry.occurredAtEpochMillis)
                        .put("title", entry.title)
                        .put("detail", entry.detail)
                        .put("active", entry.active)
                        .apply { entry.recordingName?.let { put("recording", it) } },
                )
            }
        }.toString()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(ENTRIES, encoded)
        }
    }
}
