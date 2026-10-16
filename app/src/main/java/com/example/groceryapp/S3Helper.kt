package com.example.groceryapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import java.util.concurrent.Executors

/**
 * All AWS S3 access for the app (uploads AND downloads) goes through this object, using the team
 * credentials from aws.properties (exposed as BuildConfig fields).
 *
 * Network calls run on a background thread; results are delivered on the main thread.
 */
object S3Helper {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: AmazonS3Client by lazy {
        AmazonS3Client(
            BasicAWSCredentials(BuildConfig.AWS_ACCESS_KEY, BuildConfig.AWS_SECRET_KEY),
            Region.getRegion(Regions.fromName(BuildConfig.AWS_REGION))
        )
    }

    /** False until aws.properties has been filled in. */
    fun isConfigured(): Boolean =
        BuildConfig.AWS_ACCESS_KEY.isNotBlank() && BuildConfig.AWS_SECRET_KEY.isNotBlank() &&
                BuildConfig.AWS_BUCKET.isNotBlank() && BuildConfig.AWS_REGION.isNotBlank()

    /** The public-style S3 URL stored in Firebase for an object key. */
    fun urlFor(key: String) = "https://${BuildConfig.AWS_BUCKET}.s3.${BuildConfig.AWS_REGION}.amazonaws.com/$key"

    /** Recovers the object key from a URL produced by [urlFor]. */
    fun keyFromUrl(url: String): String? = url.substringAfter(".amazonaws.com/", "").takeIf { it.isNotEmpty() }

    /**
     * Compresses the picked photo (< 0.5 MB) and uploads it as profile_photos/{uid}/{timestamp}.jpg.
     * A new key per upload means the bucket keeps both the first and the updated photo.
     * Delivers the S3 URL on success.
     */
    fun uploadProfilePhoto(context: Context, uri: Uri, uid: String, onResult: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                val file = ImageUtils.compressForUpload(appContext, uri)
                try {
                    val key = "profile_photos/$uid/${System.currentTimeMillis()}.jpg"
                    val request = PutObjectRequest(BuildConfig.AWS_BUCKET, key, file).apply {
                        metadata = ObjectMetadata().apply { contentType = "image/jpeg" }
                    }
                    client.putObject(request)
                    urlFor(key)
                } finally {
                    file.delete()
                }
            }
            mainHandler.post { onResult(result) }
        }
    }

    /**
     * Downloads the image behind an S3 URL (via the S3 credentials, so private buckets work too).
     * Pass [maxSide] > 0 to decode a smaller bitmap (longest side about [maxSide] px) for thumbnails.
     */
    fun downloadImage(url: String, maxSide: Int = 0, onResult: (Result<Bitmap>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val key = keyFromUrl(url) ?: throw IllegalArgumentException("Not an S3 URL")
                val bytes = client.getObject(BuildConfig.AWS_BUCKET, key).objectContent.use { it.readBytes() }

                val options = BitmapFactory.Options()
                if (maxSide > 0) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    options.inSampleSize = sample
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: throw IllegalStateException("Couldn't decode image")
            }
            mainHandler.post { onResult(result) }
        }
    }
}