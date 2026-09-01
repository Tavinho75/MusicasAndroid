package com.example.downloaderandroid.state

/**
 * Regras de transição do ciclo de vida de uma única tarefa.
 *
 * Esta classe não agenda tarefas, não cria fila e não executa downloads.
 * Ela apenas mantém as mudanças de estado na camada Android de forma
 * explícita e validável.
 */
object DownloadTaskStateMachine {

    private val allowedTransitions = mapOf(
        DownloadTaskStatus.DRAFT to setOf(
            DownloadTaskStatus.ANALYZING,
            DownloadTaskStatus.CANCELLED
        ),
        DownloadTaskStatus.ANALYZING to setOf(
            DownloadTaskStatus.READY,
            DownloadTaskStatus.FAILED,
            DownloadTaskStatus.CANCELLED
        ),
        DownloadTaskStatus.READY to setOf(
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.CANCELLED
        ),
        DownloadTaskStatus.DOWNLOADING to setOf(
            DownloadTaskStatus.PROCESSING,
            DownloadTaskStatus.FAILED,
            DownloadTaskStatus.CANCELLED
        ),
        DownloadTaskStatus.PROCESSING to setOf(
            DownloadTaskStatus.COMPLETED,
            DownloadTaskStatus.FAILED,
            DownloadTaskStatus.CANCELLED
        ),
        DownloadTaskStatus.COMPLETED to emptySet(),
        DownloadTaskStatus.FAILED to emptySet(),
        DownloadTaskStatus.CANCELLED to emptySet()
    )

    fun canTransition(
        from: DownloadTaskStatus,
        to: DownloadTaskStatus
    ): Boolean = allowedTransitions[from]?.contains(to) == true

    fun transition(
        state: DownloadTaskState,
        target: DownloadTaskStatus,
        detail: String? = state.detail,
        updatedAtEpochMillis: Long = System.currentTimeMillis()
    ): DownloadTaskState {
        check(canTransition(state.status, target)) {
            "Transição inválida: ${state.status} -> $target"
        }

        return state.copy(
            status = target,
            detail = detail,
            updatedAtEpochMillis = updatedAtEpochMillis
        )
    }
}
