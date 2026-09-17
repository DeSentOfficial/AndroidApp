package xyz.desent.data.local.database.converter

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromStringListList(value: List<List<String>>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringListList(value: String): List<List<String>> {
        return Json.decodeFromString(value)
    }
}