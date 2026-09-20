package com.localai.runtime.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.localai.runtime.core.download.DownloadStore
import kotlinx.coroutines.flow.Flow

/** CRUD for [ModelEntity]. */
@Dao
interface ModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: ModelEntity)

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: String): ModelEntity?

    @Query("SELECT * FROM models")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    fun observe(id: String): Flow<ModelEntity?>

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM models")
    suspend fun all(): List<ModelEntity>
}

/** CRUD for [RuntimeConfigEntity]. */
@Dao
interface RuntimeConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: RuntimeConfigEntity)

    @Query("SELECT * FROM runtime_configs WHERE modelId = :modelId")
    fun observe(modelId: String): Flow<RuntimeConfigEntity?>

    @Query("SELECT * FROM runtime_configs WHERE modelId = :modelId")
    suspend fun get(modelId: String): RuntimeConfigEntity?

    @Query("DELETE FROM runtime_configs WHERE modelId = :modelId")
    suspend fun delete(modelId: String)
}

/**
 * Room implementation of the framework-free [DownloadStore] seam owned by the download package.
 * Room annotations live on these overrides because the shared interface carries none.
 */
@Dao
interface DownloadDao : DownloadStore {

    @Insert
    override suspend fun insert(e: DownloadEntity): Long

    @Update
    override suspend fun update(e: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    override suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, id DESC")
    override suspend fun all(): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE state IN ('QUEUED', 'CONNECTING', 'DOWNLOADING', 'PAUSED', 'VERIFYING') " +
            "ORDER BY createdAt ASC, id ASC",
    )
    override suspend fun active(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id")
    override suspend fun delete(id: Long)

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC, id DESC")
    override fun observeAll(): Flow<List<DownloadEntity>>
}

/** Append-only API request log with newest-first reads. */
@Dao
interface ApiLogDao {

    @Insert
    suspend fun insert(e: ApiLogEntity): Long

    @Query("SELECT * FROM api_logs ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ApiLogEntity>>

    @Query("SELECT * FROM api_logs ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ApiLogEntity>

    @Query("DELETE FROM api_logs")
    suspend fun clear()

    @Query("DELETE FROM api_logs WHERE ts < :keep")
    suspend fun prune(keep: Long)
}

/** Paired device registry. */
@Dao
interface PairedDeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: PairedDeviceEntity)

    @Query("SELECT * FROM paired_devices ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE tokenHash = :hash LIMIT 1")
    suspend fun byTokenHash(hash: String): PairedDeviceEntity?

    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE paired_devices SET lastSeenAt = :ts WHERE id = :id")
    suspend fun updateLastSeen(id: String, ts: Long)
}
