package com.example.isip.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_logs")
data class AgentLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "user_query")
    val userQuery: String? = null,
    @ColumnInfo(name = "retrieved_photo_ids")
    val retrievedPhotoIds: String? = null,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_args_json")
    val toolArgsJson: String? = null,
    @ColumnInfo(name = "model_output_json")
    val modelOutputJson: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
