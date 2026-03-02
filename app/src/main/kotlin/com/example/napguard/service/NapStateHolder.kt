package com.example.napguard.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局单例状态持有者，用于在 NapMonitorService 和 MonitoringViewModel 之间共享状态。
 * 彻底绕过 BroadcastReceiver，直接用 StateFlow 传递状态。
 */
object NapStateHolder {
    data class NapState(
        val serviceState: String = NapMonitorService.STATE_LISTENING,
        val elapsedMs: Long = 0L,
        val snoreRatio: Int = 0,
        val countdownMs: Long = 0L,
        val isSnoring: Boolean = false,
    )

    private val _state = MutableStateFlow(NapState())
    val state: StateFlow<NapState> = _state.asStateFlow()

    fun update(block: NapState.() -> NapState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = NapState()
    }
}
