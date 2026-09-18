package com.example.downloaderandroid.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent FIFO queue for URLs waiting to be downloaded. */
class DownloadQueueStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<DownloadQueueItem> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isNotBlank()) {
                        add(
                            DownloadQueueItem(
                                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                                url = url,
                                addedAtEpochMillis = item.optLong("addedAtEpochMillis", 0L),
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(url: String): DownloadQueueItem? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val current = list()
        if (current.any { it.url == normalized }) return null

        val item = DownloadQueueItem(
            id = "queue-" + UUID.randomUUID(),
            url = normalized,
            addedAtEpochMillis = System.currentTimeMillis(),
        )
        save((current + item).take(MAX_ITEMS))
        return item
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() = preferences.edit().remove(KEY_ITEMS).apply()

    private fun save(items: List<DownloadQueueItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("addedAtEpochMillis", item.addedAtEpochMillis)
                }
            )
        }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "downloader_download_queue"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 50
    }
}

data class DownloadQueueItem(
    val id: String,
    val url: String,
    val addedAtEpochMillis: Long,
)
