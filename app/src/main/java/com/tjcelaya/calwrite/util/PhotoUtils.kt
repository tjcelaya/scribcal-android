package com.tjcelaya.calwrite.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

object PhotoUtils {
    private const val TAG = "PhotoUtils"
    private const val PHOTOS_DIR = "photos"
    private const val THUMBNAIL_SIZE = 300

    /**
     * Saves a shared photo from URI to app's internal storage
     * @param context Application context
     * @param uri URI of the shared photo
     * @return Path to saved photo file, or null if failed
     */
    fun saveSharedPhoto(context: Context, uri: Uri): String? {
        try {
            val photosDir = File(context.filesDir, PHOTOS_DIR)
            if (!photosDir.exists()) {
                photosDir.mkdirs()
            }

            // Generate unique filename
            val filename = "${UUID.randomUUID()}.jpg"
            val photoFile = File(photosDir, filename)

            // Copy photo from URI to internal storage
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                FileOutputStream(photoFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Photo saved to: ${photoFile.absolutePath}")
            return photoFile.absolutePath

        } catch (e: IOException) {
            Log.e(TAG, "Failed to save shared photo", e)
            return null
        }
    }

    /**
     * Creates a thumbnail bitmap from a photo file
     * @param photoPath Path to the photo file
     * @param size Desired thumbnail size (width/height)
     * @return Thumbnail bitmap or null if failed
     */
    fun createThumbnail(photoPath: String, size: Int = THUMBNAIL_SIZE): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(photoPath, options)

            // Calculate sample size for thumbnail
            val sampleSize = calculateInSampleSize(options, size, size)

            val thumbnailOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            BitmapFactory.decodeFile(photoPath, thumbnailOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create thumbnail for $photoPath", e)
            null
        }
    }

    /**
     * Loads a full-size bitmap from a photo file
     * @param photoPath Path to the photo file
     * @return Full-size bitmap or null if failed
     */
    fun loadPhoto(photoPath: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(photoPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load photo from $photoPath", e)
            null
        }
    }

    /**
     * Deletes a photo file from internal storage
     * @param photoPath Path to the photo file
     * @return True if deleted successfully
     */
    fun deletePhoto(photoPath: String): Boolean {
        return try {
            val file = File(photoPath)
            val deleted = file.delete()
            Log.d(TAG, "Photo deleted: $photoPath - success: $deleted")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete photo: $photoPath", e)
            false
        }
    }

    /**
     * Checks if a photo file exists
     * @param photoPath Path to the photo file
     * @return True if file exists and is readable
     */
    fun photoExists(photoPath: String?): Boolean {
        if (photoPath.isNullOrBlank()) return false
        val file = File(photoPath)
        return file.exists() && file.canRead()
    }

    /**
     * Gets the size of a photo file in bytes
     * @param photoPath Path to the photo file
     * @return File size in bytes, or 0 if file doesn't exist
     */
    fun getPhotoSize(photoPath: String): Long {
        return try {
            File(photoPath).length()
        } catch (e: Exception) {
            0L
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}