package com.tjcelaya.calwrite.data.database

/**
 * Represents an ongoing event with optional photo upload progress
 */
data class OngoingEventWithProgress(
    val id: Long,
    val eventTypeId: Long,
    val startTime: Long,
    val notes: String?,
    val uploadProgress: PhotoUploadProgress?
) {
    fun hasPhotoUpload(): Boolean = uploadProgress != null

    fun isUploadInProgress(): Boolean = uploadProgress?.isInProgress() == true

    fun isUploadCompleted(): Boolean = uploadProgress?.isCompleted() == true

    fun isUploadFailed(): Boolean = uploadProgress?.isFailed() == true

    fun getUploadStatusText(): String {
        val progress = uploadProgress ?: return ""

        return when (progress.status) {
            PhotoUploadStatus.PREPARING -> "Preparing upload..."
            PhotoUploadStatus.UPLOADING -> "Uploading ${progress.progressPercent}%"
            PhotoUploadStatus.COMPLETED -> "Upload complete ✓"
            PhotoUploadStatus.FAILED -> "Upload failed ✗"
        }
    }

    fun shouldShowInOngoingList(): Boolean {
        // Show regular ongoing events (without endTime)
        // Show events with upload progress that haven't auto-removed yet
        return uploadProgress?.shouldAutoRemove() != true
    }

    companion object {
        fun fromEvent(event: Event, uploadProgress: PhotoUploadProgress? = null): OngoingEventWithProgress {
            return OngoingEventWithProgress(
                id = event.id,
                eventTypeId = event.eventTypeId,
                startTime = event.startTime,
                notes = event.notes,
                uploadProgress = uploadProgress
            )
        }

        fun fromOngoingEvent(ongoingEvent: OngoingEvent, uploadProgress: PhotoUploadProgress? = null): OngoingEventWithProgress {
            return OngoingEventWithProgress(
                id = ongoingEvent.id,
                eventTypeId = ongoingEvent.eventTypeId,
                startTime = ongoingEvent.startTime,
                notes = ongoingEvent.notes,
                uploadProgress = uploadProgress
            )
        }
    }
}