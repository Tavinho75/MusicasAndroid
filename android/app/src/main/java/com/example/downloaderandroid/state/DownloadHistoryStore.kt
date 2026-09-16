package com.example.downloaderandroid.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent history of finished downloads, newest item first. */
class DownloadHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun list(): List<DownloadHistoryItem> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        DownloadHistoryItem(
                            id = item.optString("id"),
                            title = item.optString("title").takeIf { it.isNotBlank() },
                            url = item.optString("url"),
                            status = item.optString("status"),
                            detail = item.optString("detail").takeIf { it.isNotBlank() },
                            completedAtEpochMillis = item.optLong("completedAtEpochMillis", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(state: DownloadTaskState) {
        val item = DownloadHistoryItem(
            id = state.id,
            title = state.title,
            url = state.url,
            status = state.status.name,
            detail = state.detail,
            completedAtEpochMillis = System.currentTimeMillis(),
        )
        val items = list().filterNot { it.id == item.id }.toMutableList()
        items.add(0, item)
        save(items.take(MAX_ITEMS))
    }

    fun clear() = preferences.edit().remove(KEY_ITEMS).apply()

    private fun save(items: List<DownloadHistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title ?: "")
                    put("url", item.url)
                    put("status", item.status)
                    put("detail", item.detail ?: "")
                    put("completedAtEpochMillis", item.completedAtEpochMillis)
                }
            )
        }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "downloader_download_history"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 30
    }
}

data class DownloadHistoryItem(
    val id: String,
    val title: String?,
    val url: String,
    val status: String,
    val detail: String?,
    val completedAtEpochMillis: Long,
)
