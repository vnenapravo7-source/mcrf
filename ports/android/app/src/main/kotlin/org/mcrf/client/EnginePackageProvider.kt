package org.mcrf.client

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** The installer gets one read-only APK URI, never arbitrary paths or profiles. */
class EnginePackageProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        require(uri.authority == "${requireNotNull(context).packageName}.engine-package" && uri.query == null && uri.fragment == null && uri.pathSegments.size == 1)
        val name = uri.lastPathSegment.orEmpty(); require(name.matches(Regex("mcrf-engine-[0-9a-f-]{36}\\.apk")))
        val base = requireNotNull(context).cacheDir.canonicalFile
        return File(base, name).also { require(it.canonicalFile.parentFile == base && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile) }
    }
    override fun getType(uri: Uri): String { file(uri); return "application/vnd.android.package-archive" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor { require(mode == "r"); return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri); val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        require(columns.all { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) })
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any> { if (it == OpenableColumns.DISPLAY_NAME) "MCRF-engines.apk" else file.length() }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
