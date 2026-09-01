package com.example.downloaderandroid.state

import android.content.Context

/**
 * Camada nativa responsável por manter uma única tarefa corrente.
 *
 * Não é uma fila e não executa downloads. Centraliza apenas persistência e
 * transições válidas para que as futuras camadas de execução não manipulem
 * SharedPreferences ou estados arbitrariamente.
 */
class NativeDownloadTaskRepository(context: Context) {

    private val store = DownloadStateStore(context.applicationContext)

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
        updatedAtEpochMillis: Long = System.currentTimeMillis()
    ): DownloadTaskState {
        val currentState = requireNotNull(store.load()) {
            "Nenhuma tarefa nativa disponível para transição."
        }

        val nextState = DownloadTaskStateMachine.transition(
            state = currentState,
            target = target,
            detail = detail,
            updatedAtEpochMillis = updatedAtEpochMillis
        )

        store.save(nextState)
        return nextState
    }

    fun clear() {
        store.clear()
    }
}
