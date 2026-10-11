package cn.xiao.xyaax.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CourseEntity::class, TimeProfileEntity::class],
    version = 1,
    exportSchema = true,
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
