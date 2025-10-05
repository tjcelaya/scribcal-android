package com.tjcelaya.scribcal.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity to store Google Photos album configuration
 */
@Entity(tableName = "album_config")
data class AlbumConfig(
    @PrimaryKey
    val id: Int = 1, // Single row table
    val googlePhotosAlbumId: String?,
    val googlePhotosAlbumName: String?,
    val createdAt: Long,
    val lastVerified: Long?
)