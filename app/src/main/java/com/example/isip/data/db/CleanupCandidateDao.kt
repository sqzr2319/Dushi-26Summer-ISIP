package com.example.isip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CleanupCandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidate(candidate: CleanupCandidateEntity): Long

    @Query("SELECT * FROM cleanup_candidates ORDER BY created_at DESC")
    suspend fun getAllCandidates(): List<CleanupCandidateEntity>

    @Query("UPDATE cleanup_candidates SET status = :status WHERE id = :id")
    suspend fun updateCandidateStatus(id: Long, status: String)

    @Query("DELETE FROM cleanup_candidates WHERE id = :id")
    suspend fun deleteCandidate(id: Long)
}
