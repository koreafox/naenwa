package com.naenwa.remote.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naenwa.remote.data.MessageType
import com.naenwa.remote.databinding.ItemChatMessageBinding

data class ChatDisplayMessage(
    val id: Long = System.currentTimeMillis(),
    val type: MessageType,
    val content: String
)

class ChatMessageAdapter : ListAdapter<ChatDisplayMessage, ChatMessageAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatDisplayMessage) {
            // 모든 레이아웃 숨기기
            binding.layoutUserMessage.visibility = View.GONE
            binding.layoutClaudeMessage.visibility = View.GONE
            binding.layoutSystemMessage.visibility = View.GONE

            when (message.type) {
                MessageType.USER_INPUT -> {
                    binding.layoutUserMessage.visibility = View.VISIBLE
                    binding.tvUserMessage.text = message.content
                }
                MessageType.CLAUDE_OUTPUT -> {
                    binding.layoutClaudeMessage.visibility = View.VISIBLE
                    binding.tvClaudeMessage.text = message.content
                }
                MessageType.SYSTEM, MessageType.TOOL_USE, MessageType.BUILD_LOG -> {
                    binding.layoutSystemMessage.visibility = View.VISIBLE
                    binding.tvSystemMessage.text = message.content
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatDisplayMessage>() {
        override fun areItemsTheSame(oldItem: ChatDisplayMessage, newItem: ChatDisplayMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatDisplayMessage, newItem: ChatDisplayMessage): Boolean {
            return oldItem == newItem
        }
    }
}
