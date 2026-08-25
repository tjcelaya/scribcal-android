package com.tjcelaya.calwrite.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * DAO for managing album configuration
 */
@Dao
interface AlbumConfigDao {

    @Query("SELECT * FROM album_config WHERE id = 1")
    suspend fun getAlbumConfig(): AlbumConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbumConfig(config: AlbumConfig)

    @Update
    suspend fun updateAlbumConfig(config: AlbumConfig)

    @Query("UPDATE album_config SET lastVerified = :timestamp WHERE id = 1")
    suspend fun updateLastVerified(timestamp: Long)

    @Query("DELETE FROM album_config")
    suspend fun clearAlbumConfig()
}