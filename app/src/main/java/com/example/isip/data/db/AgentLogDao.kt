package com.example.isip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentLog(agentLog: AgentLogEntity): Long

    @Query("SELECT * FROM agent_logs ORDER BY created_at DESC")
    suspend fun getAllAgentLogs(): List<AgentLogEntity>

    @Query("DELETE FROM agent_logs WHERE id = :id")
    suspend fun deleteAgentLog(id: Long)
}
