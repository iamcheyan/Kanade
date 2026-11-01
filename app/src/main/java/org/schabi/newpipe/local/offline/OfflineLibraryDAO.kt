package org.schabi.newpipe.local.offline

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface OfflineLibraryDAO {
    @Query("SELECT * FROM offline_library ORDER BY downloaded_at DESC, title COLLATE NOCASE")
    fun getAll(): List<OfflineLibraryEntity>

    @Query("SELECT * FROM offline_library WHERE account_id = :accountId AND video_id = :videoId LIMIT 1")
    fun get(accountId: String, videoId: String): OfflineLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entries: List<OfflineLibraryEntity>)

    @Query("DELETE FROM offline_library WHERE account_id = :accountId AND video_id = :videoId")
    fun delete(accountId: String, videoId: String)

    @Query("DELETE FROM offline_library WHERE remote_missing_since <> ''")
    fun deleteWatched()

    @Query("DELETE FROM offline_library")
    fun deleteAll()
}
