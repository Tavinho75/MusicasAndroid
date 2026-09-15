package com.example.downloaderandroid.state

import android.content.Context

/**
 * Camada nativa responsável por manter uma tarefa corrente.
 *
 * O nome do armazenamento pode ser separado para que testes e o download real
 * em segundo plano não sobrescrevam o estado um do outro.
 */
class NativeDownloadTaskRepository(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) {

    private val store = DownloadStateStore(
        context.applicationContext,
        preferencesName,
    )

    fun create(state: DownloadTaskState): DownloadTaskState {
        check(state.status == DownloadTaskStatus.DRAFT) {
            "Uma nova tarefa deve começar em DRAFT."
        }

        store.save(state)
        return state
    }

    fun current(): DownloadTaskState? = store.load()

    fun transition(
        target: DownloadTaskStatus,
        detail: String? = current()?.detail,
        updatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): DownloadTaskState {
        val currentState = requireNotNull(store.load()) {
            "Nenhuma tarefa nativa disponível para transição."
        }

        val nextState = DownloadTaskStateMachine.transition(
            state = currentState,
            target = target,
            detail = detail,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

        store.save(nextState)
        return nextState
    }

    fun clear() {
        store.clear()
    }

    companion object {
        const val DEFAULT_PREFERENCES_NAME = "downloader_native_state"
        const val ACTIVE_DOWNLOAD_PREFERENCES_NAME = "downloader_active_download"
    }
}
