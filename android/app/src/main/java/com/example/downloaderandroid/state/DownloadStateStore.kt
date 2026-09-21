package com.example.downloaderandroid.state

import android.content.Context
import org.json.JSONObject

/**
 * Persistência mínima da camada Android.
 *
 * A preferência usada pelo store pode ser nomeada para manter o estado de
 * testes isolado do estado real de um download em segundo plano.
 */
class DownloadStateStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {

    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

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
            .put("progressPercent", progressPercent)
            .put("etaSeconds", etaSeconds)
            .put("updatedAtEpochMillis", updatedAtEpochMillis)
            .put("startedAtEpochMillis", startedAtEpochMillis)
            .put("fileSizeBytes", fileSizeBytes)
            .put("folder", folder)

    private fun String.toDownloadTaskState(): DownloadTaskState {
        val json = JSONObject(this)

        return DownloadTaskState(
            id = json.getString("id"),
            url = json.getString("url"),
            status = DownloadTaskStatus.valueOf(json.getString("status")),
            title = json.optString("title").takeIf { it.isNotBlank() },
            detail = json.optString("detail").takeIf { it.isNotBlank() },
            progressPercent = if (json.isNull("progressPercent")) null else json.optDouble("progressPercent").toFloat(),
            etaSeconds = if (json.isNull("etaSeconds")) null else json.optLong("etaSeconds"),
            updatedAtEpochMillis = json.getLong("updatedAtEpochMillis"),
            startedAtEpochMillis = json.optLong("startedAtEpochMillis", System.currentTimeMillis()),
            fileSizeBytes = json.optLong("fileSizeBytes", 0L),
            folder = json.optString("folder").takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "downloader_native_state"
        const val KEY_CURRENT_STATE = "current_state"
    }
}
