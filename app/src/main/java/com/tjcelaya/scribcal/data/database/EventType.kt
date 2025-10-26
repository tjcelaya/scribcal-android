package com.tjcelaya.scribcal.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

import androidx.room.Index

@Entity(
    tableName = "event_types",
    indices = [Index(value = ["name"], unique = true)]
)
data class EventType(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val color: Int? = null, // Optional color for UI
    val createdAt: Long = System.currentTimeMillis()
)
