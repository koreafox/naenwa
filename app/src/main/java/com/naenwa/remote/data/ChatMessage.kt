package com.naenwa.remote.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val type: MessageType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageType {
    USER_INPUT,      // 사용자가 입력한 메시지
    CLAUDE_OUTPUT,   // Claude 응답
    SYSTEM,          // 시스템 메시지
    TOOL_USE,        // 도구 사용 로그
    BUILD_LOG        // 빌드 로그
}
