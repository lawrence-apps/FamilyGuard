package com.familyguard.child.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Gallery access (parent dashboard ke liye) — MediaStore par photos+videos ki
 * list + on-demand thumb/full upload. DESIGN:
 *  - list() MEKANICAL metadata hai (naam/album/date/size) — koi pixel transfer
 *    nahi, isliye gallery kholne par turant grid ban jata hai.
 *  - thumbs/full SIRF parent tap kare tab upload hote hain (ek JPEG ~15-200 KB)
 *    — poora gallery kabhi bulk me download nahi hota (privacy + bandwidth).
 * Videos v1 me thumbnail-frame dikhate hain (server /media sirf jpg/png serve
 * karta hai; poora video streaming baad me).
 */
object GalleryFetcher {

    private const val PAGE = 18

    // ------------------------------------------------------------ permissions

    fun hasImagesPerm(ctx: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun hasVideosPerm(ctx: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun hasPerm(ctx: Context): Boolean = hasImagesPerm(ctx) || hasVideosPerm(ctx)

    // ------------------------------------------------------------------- list

    /** Latest `limit` media items (photos+videos), date DESC. No pixel data. */
    fun list(ctx: Context, limit: Int = 200): JsonArray {
        val all = JsonArray()
        if (hasImagesPerm(ctx)) all.addAll(query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo"))
        if (hasVideosPerm(ctx)) all.addAll(query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video"))
        // date DESC merge — dono queries ka result ek saath sort karo
        val sorted = all.toList().sortedByDescending { it.asJsonObject.get("date")?.asLong ?: 0L }
        val out = JsonArray()
        for (el in sorted.take(limit)) out.add(el)
        return out
    }

    private fun query(ctx: Context, collection: Uri, type: String): List<JsonObject> = runCatching {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        )
        val out = ArrayList<JsonObject>()
        ctx.contentResolver.query(collection, proj, null, null,
            MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val o = JsonObject()
                o.addProperty("id", type.first() + c.getLong(iId).toString())
                o.addProperty("type", type)
                o.addProperty("name", c.getString(iName) ?: "")
                o.addProperty("album", if (iBucket >= 0) c.getString(iBucket) ?: "" else "")
                o.addProperty("date", c.getLong(iDate) * 1000L) // DATE_MODIFIED seconds hota hai
                o.addProperty("size", c.getLong(iSize))
                out.add(o)
            }
        }
        out
    }.getOrDefault(emptyList())

    fun uriFor(id: String): Uri? {
        if (id.length < 2) return null
        val num = id.substring(1).toLongOrNull() ?: return null
        return when (id[0]) {
            'p' -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, num)
            'v' -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, num)
            else -> null
        }
    }

    fun isVideo(id: String): Boolean = id.startsWith("v")

    // ------------------------------------------------------------------ media

    /** Grid tile (~320px) JPEG file — upload ke liye. Video: 1s frame. */
    suspend fun thumb(ctx: Context, id: String): File? =
        mediaFile(ctx, id, 320, 80, "gthumb")

    /** Full view (~1080px) JPEG file — photo ka pura frame, video ka 1s frame. */
    suspend fun full(ctx: Context, id: String): File? =
        mediaFile(ctx, id, 1080, 85, "gfull")

    private suspend fun mediaFile(ctx: Context, id: String, maxSide: Int, quality: Int, prefix: String): File? =
        withContext(Dispatchers.IO) {
            val uri = uriFor(id) ?: return@withContext null
            val bmp = if (isVideo(id)) videoFrame(ctx, uri, maxSide) else decodeScaled(ctx, uri, maxSide)
                ?: return@withContext null
            runCatching {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bmp.recycle()
                val f = File(ctx.cacheDir, "${prefix}_${System.currentTimeMillis()}_${id.substring(1)}.jpg")
                f.outputStream().use { it.write(bos.toByteArray()) }
                f
            }.getOrNull()
        }

    /** Photo ko maxSide tak downscale karke decode (OOM-safe inSampleSize) +
     *  EXIF rotation fix (portrait photos sideways na dikhein). */
    private fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        var sample = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (maxOf(w, h) / 2 >= maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = open(ctx, uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: return@runCatching null
        val rotated = exifRotate(ctx, uri, bmp)
        capSide(rotated ?: bmp, maxSide)
    }.getOrNull()

    private fun open(ctx: Context, uri: Uri): InputStream? = runCatching {
        ctx.contentResolver.openInputStream(uri)
    }.getOrNull()

    private fun exifRotate(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap? = runCatching {
        val exif = open(ctx, uri)?.use { android.media.ExifInterface(it) } ?: return@runCatching null
        val rotation = when (
            exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return@runCatching null
        val m = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }.getOrNull()

    /** Side jo maxSide se bada ho to scale-down (aspect ratio same). */
    private fun capSide(bmp: Bitmap, maxSide: Int): Bitmap {
        val big = maxOf(bmp.width, bmp.height)
        if (big <= maxSide) return bmp
        val scale = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true)
    }

    /** Video ka ~1s par frame (thumbnail + full dono isi se). */
    private fun videoFrame(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            capSide(frame, maxSide)
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /** Wizard/self-heal ke liye — kitne items visible hain (permission state check). */
    fun countForDiag(ctx: Context): Int = if (hasPerm(ctx)) runCatching { list(ctx, 1).size() }.getOrDefault(0) else 0
}
