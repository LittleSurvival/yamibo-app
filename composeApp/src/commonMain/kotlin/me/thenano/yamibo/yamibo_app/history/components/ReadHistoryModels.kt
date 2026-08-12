package me.thenano.yamibo.yamibo_app.history.components

import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository

internal sealed interface HistoryState {
    data object Loading : HistoryState
    data class Success(
        val items: List<ReadHistoryRepository.AnyReadingHistory>,
        val totalCount: Long,
        val currentPage: Int,
        val requestKey: String = "",
        val loadGeneration: Long = 0L,
        val supplementaryLoading: Boolean = false,
    ) : HistoryState

    data object Empty : HistoryState
    data class Error(val message: String) : HistoryState
}

internal enum class PageMode { Normal, Search, Select }
