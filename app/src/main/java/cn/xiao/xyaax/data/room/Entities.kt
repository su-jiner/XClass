package cn.xiao.xyaax.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 课程实体（Room 化数据层）。与现有 JSON 课程模型一一对应，字段含序列化友好类型。 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val teacher: String,
    val rawWeekText: String,
    /** meetings 的 JSON 编码（kotlinx-serialization），保持迁移简单 */
    val meetingsJson: String,
    /** 颜色索引（与主题色板对齐） */
    val colorIndex: Int,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 时间表（校区）实体。 */
@Entity(tableName = "time_profiles")
data class TimeProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean = false,
    /** blocks 的 JSON 编码 */
    val blocksJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
