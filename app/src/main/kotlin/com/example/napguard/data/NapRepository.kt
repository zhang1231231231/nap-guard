package com.example.napguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nap_settings")

@Singleton
class NapRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val napDao: NapDao,
) {
    companion object {
        val KEY_ALARM_DURATION_MIN = intPreferencesKey("alarm_duration_min")
        val KEY_SLEEP_TRIGGER_SEC = intPreferencesKey("sleep_trigger_sec")

        const val DEFAULT_ALARM_DURATION_MIN = 30
        const val DEFAULT_SLEEP_TRIGGER_SEC = 300
    }

    // ---- 用户设置 ----

    val alarmDurationMin: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALARM_DURATION_MIN] ?: DEFAULT_ALARM_DURATION_MIN
    }

    val sleepTriggerSec: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SLEEP_TRIGGER_SEC] ?: DEFAULT_SLEEP_TRIGGER_SEC
    }

    suspend fun setAlarmDuration(minutes: Int) {
        context.dataStore.edit { it[KEY_ALARM_DURATION_MIN] = minutes }
    }

    suspend fun setSleepTrigger(seconds: Int) {
        context.dataStore.edit { it[KEY_SLEEP_TRIGGER_SEC] = seconds }
    }

    // ---- 午休记录 ----

    fun getAllRecords(): Flow<List<NapRecord>> = napDao.getAllRecords()

    fun getLatestRecord(): Flow<NapRecord?> = napDao.getLatestRecord()

    suspend fun saveRecord(record: NapRecord): Long = napDao.insert(record)
}
