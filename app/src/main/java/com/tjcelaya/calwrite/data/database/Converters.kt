package com.tjcelaya.calwrite.data.database

import androidx.room.TypeConverter

/**
 * Room type converters for CalWrite entities.
 */
class Converters {

    @TypeConverter
    fun fromCadence(cadence: Cadence): String = cadence.name

    @TypeConverter
    fun toCadence(value: String?): Cadence = Cadence.fromName(value)
}
