package com.jc.mvl

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val Context.historyDataStore by preferencesDataStore(name = "mvl_history")

data class BreakEntry(
    val stepOut: LocalDateTime,
    val stepIn: LocalDateTime
)

data class WorkDayEntry(
    val id: String,                          // unique ID: timestamp of sign-in
    val date: String,                        // display date e.g. "May 7, 2026"
    val signIn: LocalDateTime,
    val signOut: LocalDateTime,
    val breaks: List<BreakEntry>,
    val totalBreakMinutes: Long,
    val totalWorkMinutes: Long,
    val officeMode: Boolean
) {
    val formatter: DateTimeFormatter get() = DateTimeFormatter.ofPattern("hh:mm a")
    val dateFormatter: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    fun overtimeMinutes(): Long {
        val required = 9 * 60L
        return maxOf(0L, totalWorkMinutes - required)
    }

    fun underMinutes(): Long {
        val required = 9 * 60L
        return maxOf(0L, required - totalWorkMinutes)
    }

    fun formatDuration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

class HistoryRepository(private val context: Context) {

    private val keyHistory = stringPreferencesKey("history_entries")

    suspend fun saveEntry(entry: WorkDayEntry) {
        val existing = loadEntries().toMutableList()
        existing.add(0, entry) // newest first
        val json = entriesToJson(existing)
        context.historyDataStore.edit { prefs ->
            prefs[keyHistory] = json
        }
    }

    suspend fun loadEntries(): List<WorkDayEntry> {
        val prefs = context.historyDataStore.data.first()
        val json = prefs[keyHistory]?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return jsonToEntries(json)
    }

    suspend fun deleteEntry(id: String) {
        val existing = loadEntries().toMutableList()
        existing.removeAll { it.id == id }
        context.historyDataStore.edit { prefs ->
            prefs[keyHistory] = entriesToJson(existing)
        }
    }

    suspend fun clearAll() {
        context.historyDataStore.edit { it.clear() }
    }

    private fun entriesToJson(entries: List<WorkDayEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("date", e.date)
            obj.put("signIn", e.signIn.toString())
            obj.put("signOut", e.signOut.toString())
            obj.put("totalBreakMinutes", e.totalBreakMinutes)
            obj.put("totalWorkMinutes", e.totalWorkMinutes)
            obj.put("officeMode", e.officeMode)

            val breaksArr = JSONArray()
            e.breaks.forEach { b ->
                val bObj = JSONObject()
                bObj.put("stepOut", b.stepOut.toString())
                bObj.put("stepIn", b.stepIn.toString())
                breaksArr.put(bObj)
            }
            obj.put("breaks", breaksArr)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun jsonToEntries(json: String): List<WorkDayEntry> {
        val result = mutableListOf<WorkDayEntry>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val breaksArr = obj.getJSONArray("breaks")
            val breaks = mutableListOf<BreakEntry>()
            for (j in 0 until breaksArr.length()) {
                val b = breaksArr.getJSONObject(j)
                breaks.add(
                    BreakEntry(
                        stepOut = LocalDateTime.parse(b.getString("stepOut")),
                        stepIn = LocalDateTime.parse(b.getString("stepIn"))
                    )
                )
            }
            result.add(
                WorkDayEntry(
                    id = obj.getString("id"),
                    date = obj.getString("date"),
                    signIn = LocalDateTime.parse(obj.getString("signIn")),
                    signOut = LocalDateTime.parse(obj.getString("signOut")),
                    breaks = breaks,
                    totalBreakMinutes = obj.getLong("totalBreakMinutes"),
                    totalWorkMinutes = obj.getLong("totalWorkMinutes"),
                    officeMode = obj.getBoolean("officeMode")
                )
            )
        }
        return result
    }
}