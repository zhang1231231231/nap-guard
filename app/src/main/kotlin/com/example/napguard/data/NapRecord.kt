package com.example.napguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 午休记录实体
 */
@Entity(tableName = "nap_records")
data class NapRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 开始时间（Unix 毫秒时间戳） */
    val startTimeMs: Long,
    /** 检测到入睡的时间（Unix 毫秒时间戳） */
    val sleepDetectedTimeMs: Long,
    /** 结束时间（Unix 毫秒时间戳） */
    val endTimeMs: Long,
    /** 总时长（毫秒），从开启到闹钟响 */
    val totalDurationMs: Long,
    /** 稳睡时长（毫秒），从判定入睡到闹钟响 */
    val sleepDurationMs: Long,
)
