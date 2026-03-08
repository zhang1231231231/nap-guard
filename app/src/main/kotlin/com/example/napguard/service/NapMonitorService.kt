package com.example.napguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.napguard.MainActivity
import com.example.napguard.R
import com.example.napguard.audio.SnoreDetector
import com.example.napguard.data.NapRecord
import com.example.napguard.data.NapRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 午休监控 Foreground Service
 *
 * 状态机：
 * LISTENING  → 正在检测鼾声
 * SNORE_DETECTED → 检测到鼾声，计算连续时长
 * SLEEPING   → 已判定入睡，开始闹钟倒计时
 * ALARMING   → 倒计时结束，触发闹钟
 */
@AndroidEntryPoint
class NapMonitorService : Service() {

    companion object {
        private const val TAG = "NapMonitorService"

        const val CHANNEL_ID = "nap_monitor_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.napguard.ACTION_START"
        const val ACTION_STOP = "com.example.napguard.ACTION_STOP"

        const val EXTRA_ALARM_DURATION_MIN = "alarm_duration_min"
        const val EXTRA_SLEEP_TRIGGER_SEC = "sleep_trigger_sec"

        // 状态广播
        const val BROADCAST_STATE = "com.example.napguard.STATE_UPDATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_SNORE_DURATION_MS = "snore_duration_ms"
        const val EXTRA_COUNTDOWN_MS = "countdown_ms"
        const val EXTRA_IS_SNORING = "is_snoring"

        const val STATE_LISTENING = "LISTENING"
        const val STATE_SNORE_DETECTED = "SNORE_DETECTED"
        const val STATE_SLEEPING = "SLEEPING"
        const val STATE_ALARMING = "ALARMING"
        const val STATE_STOPPED = "STOPPED"
    }

    @Inject
    lateinit var repository: NapRepository

    @Inject
    lateinit var snoreDetector: SnoreDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    private var startTimeMs = 0L
    private var alarmDurationMs = 30 * 60 * 1000L
    private var sleepTriggerSec = 300

    // 入睡判定：使用滑动窗口鼾声占比（方案A+C）
    private var lastFrameTimeMs = 0L
    private var sleepDetectedTimeMs = 0L
    private var currentState = STATE_LISTENING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                alarmDurationMs = (intent.getIntExtra(EXTRA_ALARM_DURATION_MIN, 30) * 60_000).toLong()
                sleepTriggerSec = intent.getIntExtra(EXTRA_SLEEP_TRIGGER_SEC, 300).coerceAtLeast(10)
                startMonitoring()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        startTimeMs = System.currentTimeMillis()
        currentState = STATE_LISTENING
        lastFrameTimeMs = startTimeMs
        snoreDetector.updateWindowSize(sleepTriggerSec)

        startForeground(NOTIFICATION_ID, buildNotification("正在监控睡眠..."))

        monitorJob = serviceScope.launch {
            // 状态广播协程（每秒广播一次）
            launch {
                while (true) {
                    broadcastState()
                    delay(1000)
                }
            }

            // 音频检测协程——直接用 collect 而非 launchIn，确保每帧都被处理
            launch {
                Log.d(TAG, "开始收集音频帧流...")
                snoreDetector.audioFrameFlow().collect { frame ->
                    val now = System.currentTimeMillis()
                    lastFrameTimeMs = now

                    when (currentState) {
                        STATE_LISTENING, STATE_SNORE_DETECTED ->
                        {
                            currentState = if (frame.isSnoreDetected) STATE_SNORE_DETECTED else STATE_LISTENING

                            val ratio = frame.snoreRatio
                            if (ratio > 0.0) {
                                Log.d(TAG, "帧处理: state=$currentState, ratio=${String.format("%.3f", ratio)}, threshold=${SnoreDetector.SNORE_RATIO_THRESHOLD}")
                            }
                            if (ratio >= SnoreDetector.SNORE_RATIO_THRESHOLD) {
                                Log.d(TAG, ">>> 判定入睡! ratio=$ratio, 进入倒计时")
                                sleepDetectedTimeMs = now
                                currentState = STATE_SLEEPING
                                broadcastState() // 立刻广播新状态
                                startAlarmCountdown()
                            }

                            if (frame.isSnoreDetected) {
                                broadcastState(isSnoring = true)
                            }
                        }
                        else -> { /* 睡眠/响铃阶段无需处理 */ }
                    }
                }
            }
        }
    }

    private fun startAlarmCountdown() {
        serviceScope.launch {
            updateNotification("已入睡，${alarmDurationMs / 60_000}分钟后叫醒你")
            delay(alarmDurationMs)
            triggerAlarm()
        }
    }

    private fun triggerAlarm() {
        currentState = STATE_ALARMING
        val endTimeMs = System.currentTimeMillis()

        // 保存记录
        serviceScope.launch {
            repository.saveRecord(
                NapRecord(
                    startTimeMs = startTimeMs,
                    sleepDetectedTimeMs = sleepDetectedTimeMs,
                    endTimeMs = endTimeMs,
                    totalDurationMs = endTimeMs - startTimeMs,
                    sleepDurationMs = endTimeMs - sleepDetectedTimeMs,
                )
            )
        }

        // 跳转到闹钟 Activity
        val alarmIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("navigate_to", "alarm")
        }
        startActivity(alarmIntent)

        // 广播一次停止状态，然后停止服务
        broadcastState()
        stopSelf()
    }

    private fun broadcastState(isSnoring: Boolean = false) {
        val now = System.currentTimeMillis()
        val ratio = (snoreDetector.currentSnoreRatio() * 100).toLong()
        val countdownMs = if (currentState == STATE_SLEEPING) {
            alarmDurationMs - (now - sleepDetectedTimeMs)
        } else 0L

        // 更新全局 StateFlow（给 ViewModel 用）
        NapStateHolder.update {
            copy(
                serviceState = currentState,
                elapsedMs = now - startTimeMs,
                snoreRatio = ratio.toInt(),
                countdownMs = countdownMs,
                isSnoring = isSnoring,
            )
        }

        // 同时保留 BroadcastReceiver（兼容）
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, currentState)
            putExtra(EXTRA_ELAPSED_MS, now - startTimeMs)
            putExtra(EXTRA_SNORE_DURATION_MS, ratio)
            putExtra(EXTRA_COUNTDOWN_MS, countdownMs)
            putExtra(EXTRA_IS_SNORING, isSnoring)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "午休监控",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "午休卫士正在后台监控睡眠状态"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("午休卫士")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
