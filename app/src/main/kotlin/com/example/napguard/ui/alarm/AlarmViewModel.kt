package com.example.napguard.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.napguard.data.NapRecord
import com.example.napguard.data.NapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AlarmViewModel @Inject constructor(
    repository: NapRepository,
) : ViewModel() {

    val latestRecord: StateFlow<NapRecord?> = repository.getLatestRecord()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
}
