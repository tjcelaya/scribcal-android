package com.tjcelaya.calwrite.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks photo upload progress for events
 */
@Entity(tableName = "photo_upload_progress")
data class PhotoUploadProgress(
    @PrimaryKey
    val eventId: Long,
    val fileName: String,
    val status: PhotoUploadStatus,
    val progressPercent: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    val completedTime: Long? = null,
    val errorMessage: String? = null,
    val photoUrl: String? = null
) {
    fun isCompleted(): Boolean = status == PhotoUploadStatus.COMPLETED
    fun isFailed(): Boolean = status == PhotoUploadStatus.FAILED
    fun isInProgress(): Boolean = status == PhotoUploadStatus.UPLOADING
    fun shouldAutoRemove(): Boolean {
        val completedTime = this.completedTime ?: return false
        val autoRemoveTime = completedTime + AUTO_REMOVE_DELAY_MS
        return System.currentTimeMillis() > autoRemoveTime
    }

    companion object {
        const val AUTO_REMOVE_DELAY_MS = 60_000L // 1 minute
    }
}

enum class PhotoUploadStatus {
    PREPARING,      // Getting ready to upload
    UPLOADING,      // Upload in progress
    COMPLETED,      // Upload successful
    FAILED          // Upload failed
}