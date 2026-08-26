package com.tjcelaya.calwrite.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoUploadProgressDao {

    @Query("SELECT * FROM photo_upload_progress ORDER BY startTime DESC")
    fun getAllUploadProgress(): Flow<List<PhotoUploadProgress>>

    @Query("SELECT * FROM photo_upload_progress WHERE eventId = :eventId")
    suspend fun getUploadProgressForEvent(eventId: Long): PhotoUploadProgress?

    @Query("SELECT * FROM photo_upload_progress WHERE status IN (:statuses)")
    fun getUploadProgressByStatus(vararg statuses: PhotoUploadStatus): Flow<List<PhotoUploadProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUploadProgress(progress: PhotoUploadProgress)

    @Update
    suspend fun updateUploadProgress(progress: PhotoUploadProgress)

    @Query("DELETE FROM photo_upload_progress WHERE eventId = :eventId")
    suspend fun deleteUploadProgress(eventId: Long)

    @Query("DELETE FROM photo_upload_progress WHERE completedTime IS NOT NULL AND completedTime < :cutoffTime")
    suspend fun deleteExpiredCompletedUploads(cutoffTime: Long)

    @Query("UPDATE photo_upload_progress SET progressPercent = :percent WHERE eventId = :eventId")
    suspend fun updateUploadPercentage(eventId: Long, percent: Int)

    @Query("UPDATE photo_upload_progress SET status = :status, completedTime = :completedTime, photoUrl = :photoUrl WHERE eventId = :eventId")
    suspend fun markUploadCompleted(eventId: Long, status: PhotoUploadStatus, completedTime: Long, photoUrl: String?)

    @Query("UPDATE photo_upload_progress SET status = :status, completedTime = :completedTime, errorMessage = :errorMessage WHERE eventId = :eventId")
    suspend fun markUploadFailed(eventId: Long, status: PhotoUploadStatus, completedTime: Long, errorMessage: String?)
}