package com.example.groceryapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Helpers to decode, rotate and shrink photos so every upload stays well under the 0.5 MB limit. */
object ImageUtils {

    /** Hard cap for uploads. The course limit is 0.5 MB, so we stay safely below it. */
    const val MAX_UPLOAD_BYTES = 400 * 1024

    /** Decodes [uri] scaled down so its longest side is at most [maxSide] px, with EXIF rotation applied. */
    fun decodeBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        return if (rotation == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }

    /**
     * Shrinks the picked image to a JPEG of at most [MAX_UPLOAD_BYTES] and writes it to the cache folder.
     * Lowers the JPEG quality first, then the resolution, until it fits.
     */
    fun compressForUpload(context: Context, uri: Uri): File {
        var maxSide = 1024
        while (true) {
            val bitmap = decodeBitmap(context, uri, maxSide) ?: throw IllegalArgumentException("Couldn't read the selected image")
            var quality = 90
            while (quality >= 40) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_UPLOAD_BYTES) {
                    return File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg").also { it.writeBytes(out.toByteArray()) }
                }
                quality -= 10
            }
            if (maxSide <= 256) throw IllegalStateException("Image is too large to compress")
            maxSide = (maxSide * 0.75).toInt()
        }
    }
}
