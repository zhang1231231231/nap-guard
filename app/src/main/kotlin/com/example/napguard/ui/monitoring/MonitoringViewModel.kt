package com.example.napguard.ui.monitoring

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.napguard.service.NapMonitorService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class MonitoringUiState(
    val serviceState: String = NapMonitorService.STATE_LISTENING,
    val elapsedMs: Long = 0L,
    val snoreDurationMs: Long = 0L,
    val countdownMs: Long = 0L,
    val isAlarming: Boolean = false,
)

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NapMonitorService.BROADCAST_STATE) {
                val state = intent.getStringExtra(NapMonitorService.EXTRA_STATE) ?: return
                _uiState.update {
                    it.copy(
                        serviceState = state,
                        elapsedMs = intent.getLongExtra(NapMonitorService.EXTRA_ELAPSED_MS, 0L),
                        snoreDurationMs = intent.getLongExtra(NapMonitorService.EXTRA_SNORE_DURATION_MS, 0L),
                        countdownMs = intent.getLongExtra(NapMonitorService.EXTRA_COUNTDOWN_MS, 0L),
                        isAlarming = state == NapMonitorService.STATE_ALARMING,
                    )
                }
            }
        }
    }

    init {
        val filter = IntentFilter(NapMonitorService.BROADCAST_STATE)
        ContextCompat.registerReceiver(
            application,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stopService() {
        val intent = Intent(getApplication(), NapMonitorService::class.java).apply {
            action = NapMonitorService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(broadcastReceiver)
        super.onCleared()
    }
}
