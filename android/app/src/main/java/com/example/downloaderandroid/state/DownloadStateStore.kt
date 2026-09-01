package com.example.downloaderandroid.state

import android.content.Context
import org.json.JSONObject

/**
 * Persistência mínima da camada Android.
 *
 * Nesta etapa não existe fila nem agendador de downloads. O store persiste
 * apenas o estado da operação corrente, para validar a arquitetura onde o
 * estado permanece nativamente e não na camada HTML/JavaScript.
 */
class DownloadStateStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(state: DownloadTaskState) {
        preferences.edit()
            .putString(KEY_CURRENT_STATE, state.toJson().toString())
            .apply()
    }

    fun load(): DownloadTaskState? {
        val raw = preferences.getString(KEY_CURRENT_STATE, null) ?: return null

        return try {
            raw.toDownloadTaskState()
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        preferences.edit().remove(KEY_CURRENT_STATE).apply()
    }

    private fun DownloadTaskState.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("url", url)
            .put("status", status.name)
            .put("title", title)
            .put("detail", detail)
            .put("updatedAtEpochMillis", updatedAtEpochMillis)

    private fun String.toDownloadTaskState(): DownloadTaskState {
        val json = JSONObject(this)

        return DownloadTaskState(
            id = json.getString("id"),
            url = json.getString("url"),
            status = DownloadTaskStatus.valueOf(json.getString("status")),
            title = json.optString("title").takeIf { it.isNotBlank() },
            detail = json.optString("detail").takeIf { it.isNotBlank() },
            updatedAtEpochMillis = json.getLong("updatedAtEpochMillis")
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "downloader_native_state"
        const val KEY_CURRENT_STATE = "current_state"
    }
}
