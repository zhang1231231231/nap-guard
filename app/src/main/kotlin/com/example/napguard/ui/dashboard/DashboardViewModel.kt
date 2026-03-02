package com.example.napguard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.napguard.data.NapRecord
import com.example.napguard.data.NapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val alarmDurationMin: Int = 30,
    val sleepTriggerSec: Int = 300,
    val latestRecord: NapRecord? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: NapRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.alarmDurationMin,
        repository.sleepTriggerSec,
        repository.getLatestRecord(),
    ) { alarmDuration, sleepTrigger, latestRecord ->
        DashboardUiState(
            alarmDurationMin = alarmDuration,
            sleepTriggerSec = sleepTrigger,
            latestRecord = latestRecord,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun setAlarmDuration(minutes: Int) {
        viewModelScope.launch { repository.setAlarmDuration(minutes) }
    }

    fun setSleepTrigger(seconds: Int) {
        viewModelScope.launch { repository.setSleepTrigger(seconds) }
    }
}
