package cn.xiao.xyaax.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CourseEntity::class, TimeProfileEntity::class],
    version = 1,
    // exportSchema=false：避免 KSP 侧 kotlinx-serialization 版本与 Room schema bundle
    // 生成代码接口不匹配（AbstractMethodError）。schema 目录已留存历史基线，
    // 需要迁移能力时可重新开启并生成 v1+ 版本。
    exportSchema = false,
)
abstract class XClassDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun timeProfileDao(): TimeProfileDao

    companion object {
        const val NAME = "xclass.db"

        @Volatile
        private var instance: XClassDatabase? = null

        fun get(context: Context): XClassDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    XClassDatabase::class.java,
                    NAME,
                )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
