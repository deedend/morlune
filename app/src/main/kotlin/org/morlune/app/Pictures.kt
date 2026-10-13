// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.morlune.engine.PictureNames
import java.io.File

/**
 * The user's own weather pictures. A folder chosen in the settings is scanned once; every image whose folder or file
 * name contains a weather word (rain, clear, cloudy, fog, storm, snow, partly, heavy..., night/day) is shrunk and
 * copied into the app's private storage. The widget then shows one of them that matches the current weather.
 */
object Pictures {
    private const val TAG = "Morlune"
    private const val MAX_FILES = 150
    private const val MAX_EDGE = 800
    private const val MAX_DEPTH = 4

    class ImportResult(val imported: Int, val skipped: Int)

    private fun dir(ctx: Context) = File(ctx.filesDir, "pictures").also { it.mkdirs() }

    private fun files(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()

    fun count(ctx: Context): Int = files(ctx).size

    fun clear(ctx: Context) { files(ctx).forEach { it.delete() } }

    /** e.g. "rain_night" -> a stored picture for rain at night (or the best available), or null to use the built-in one. */
    fun pick(ctx: Context, key: String, seed: Long): Bitmap? {
        val period = if (key.endsWith("_night")) "night" else "day"
        val group = key.removeSuffix("_night").removeSuffix("_day")
        val candidates = files(ctx).mapNotNull { f ->
            val parts = f.nameWithoutExtension.split("__")      // group__period__n
            if (parts.size == 3 && parts[0] == group) parts[1] to f else null
        }
        val file = PictureNames.choose(candidates, period, seed) ?: return null
        return try { BitmapFactory.decodeFile(file.path) } catch (e: Exception) { Log.w(TAG, "picture unreadable: ${e.message}"); null }
    }

    /** Replaces the library with the pictures found under [tree]. Slow: call from a background thread. */
    fun import(ctx: Context, tree: Uri): ImportResult {
        clear(ctx)
        var imported = 0
        var skipped = 0
        val counters = HashMap<String, Int>()
        fun walk(parentDocId: String, path: String, depth: Int) {
            if (depth > MAX_DEPTH || imported >= MAX_FILES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val entries = ArrayList<Triple<String, String, String>>()
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) entries += Triple(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
            }
            for ((docId, name, mime) in entries.sortedBy { it.second }) {
                if (imported >= MAX_FILES) return
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { walk(docId, "$path/$name", depth + 1); continue }
                if (!mime.startsWith("image/")) continue
                val tag = PictureNames.classify("$path/$name")
                if (tag == null) { skipped++; continue }
                val n = counters.merge("${tag.group}__${tag.period}", 1, Int::plus)!!
                val target = File(dir(ctx), "${tag.group}__${tag.period}__$n.jpg")
                if (shrink(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, docId), target)) imported++ else skipped++
            }
        }
        try {
            walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        } catch (e: Exception) {
            Log.w(TAG, "picture import stopped: ${e.message}")
        }
        return ImportResult(imported, skipped)
    }

    /** Decodes with sub-sampling, fixes the camera rotation, scales to at most [MAX_EDGE] and writes a JPEG. */
    private fun shrink(ctx: Context, uri: Uri, target: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        val rotation = ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (decoded == null) false else {
            val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
            val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "skipped a picture: ${e.message}")
        false
    }
}
