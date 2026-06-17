package com.piontech.bugfilter.demo.data.local.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.piontech.bugfilter.core.model.BugScrip

/** Room TypeConverter: List<BugScrip> ↔ JSON (lưu nguyên scrip vào 1 cột). */
class ScripConverter {

    private val gson = Gson()
    private val listType = object : TypeToken<List<BugScrip>>() {}.type

    @TypeConverter
    fun fromScrip(scrip: List<BugScrip>): String = gson.toJson(scrip, listType)

    @TypeConverter
    fun toScrip(json: String): List<BugScrip> =
        runCatching { gson.fromJson<List<BugScrip>>(json, listType) }.getOrNull() ?: emptyList()
}
