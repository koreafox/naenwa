package com.naenwa.remote.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(value = ["updatedAt"], name = "index_sessions_updated"),  // 최근 세션 정렬용
        Index(value = ["createdAt"], name = "index_sessions_created")   // 생성 시간 정렬용
    ]
)
data class ChatSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val serverUrl: String,
    val claudeSessionId: String? = null,  // Claude CLI 세션 ID (대화 재개용)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
