package cn.xiao.xyaax.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY id")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY id")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CourseEntity>)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM courses")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(entities: List<CourseEntity>) {
        clearAll()
        upsertAll(entities)
    }
}

@Dao
interface TimeProfileDao {
    @Query("SELECT * FROM time_profiles ORDER BY isDefault DESC, name")
    fun observeAll(): Flow<List<TimeProfileEntity>>

    @Query("SELECT * FROM time_profiles ORDER BY isDefault DESC, name")
    suspend fun getAll(): List<TimeProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TimeProfileEntity>)

    @Query("DELETE FROM time_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE time_profiles SET isDefault = 0")
    suspend fun clearDefaultFlag()

    @Query("UPDATE time_profiles SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: String)
}
