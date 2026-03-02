package com.example.napguard.ui.monitoring

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.napguard.service.NapMonitorService
import com.example.napguard.service.NapStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MonitoringUiState(
    val serviceState: String = NapMonitorService.STATE_LISTENING,
    val elapsedMs: Long = 0L,
    val snoreRatio: Int = 0,
    val countdownMs: Long = 0L,
    val isAlarming: Boolean = false,
    val isSnoring: Boolean = false,
)

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    // 直接从全局 StateFlow 转换，绕过 BroadcastReceiver
    val uiState: StateFlow<MonitoringUiState> = NapStateHolder.state.map { napState ->
        android.util.Log.d("MonitorVM", "状态更新: serviceState=${napState.serviceState}, countdown=${napState.countdownMs}")
        MonitoringUiState(
            serviceState = napState.serviceState,
            elapsedMs = napState.elapsedMs,
            snoreRatio = napState.snoreRatio,
            countdownMs = napState.countdownMs,
            isAlarming = napState.serviceState == NapMonitorService.STATE_ALARMING,
            isSnoring = napState.isSnoring,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MonitoringUiState(),
    )

    fun stopService() {
        val intent = Intent(getApplication(), NapMonitorService::class.java).apply {
            action = NapMonitorService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        NapStateHolder.reset()
    }
}
