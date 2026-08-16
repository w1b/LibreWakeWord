package org.librewakeword.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Read-only provider for individually granted, expiring wake-word clips. */
class WakewordAudioProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "audio/wav"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Wake-word audio is read-only")
        val name = uri.lastPathSegment.orEmpty()
        if (!SAFE_NAME.matches(name)) throw FileNotFoundException("Invalid audio asset")
        val file = File(requireNotNull(context).cacheDir, "$DIRECTORY/$name")
        if (!file.isFile) throw FileNotFoundException("Audio asset expired")
        if (file.lastModified() <= System.currentTimeMillis()) {
            file.delete()
            requireNotNull(context).revokeUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            throw FileNotFoundException("Audio asset expired")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    companion object {
        const val AUTHORITY = "org.librewakeword.audio"
        const val DIRECTORY = "wakeword-audio-v1"
        private val SAFE_NAME = Regex("[a-f0-9-]+\\.wav")
    }
}
