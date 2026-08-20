package com.tjcelaya.scribcal.data.database

import androidx.room.TypeConverter

/**
 * Room type converters for ScribCal entities.
 */
class Converters {

    @TypeConverter
    fun fromCadence(cadence: Cadence): String = cadence.name

    @TypeConverter
    fun toCadence(value: String?): Cadence = Cadence.fromName(value)
}
