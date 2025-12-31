package com.naenwa.remote.adapter

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naenwa.remote.R
import com.naenwa.remote.data.ChatSession
import com.naenwa.remote.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SessionAdapter(
    private val onSessionClick: (ChatSession) -> Unit,
    private val onDeleteClick: (ChatSession) -> Unit
) : ListAdapter<ChatSession, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    private var selectedSessionId: Long = -1

    fun setSelectedSession(sessionId: Long) {
        val oldSelectedId = selectedSessionId
        selectedSessionId = sessionId

        // 이전 선택 아이템 갱신
        currentList.indexOfFirst { it.id == oldSelectedId }.takeIf { it >= 0 }?.let {
            notifyItemChanged(it)
        }
        // 새 선택 아이템 갱신
        currentList.indexOfFirst { it.id == sessionId }.takeIf { it >= 0 }?.let {
            notifyItemChanged(it)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position), selectedSessionId)
    }

    inner class SessionViewHolder(
        private val binding: ItemSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("MM.dd", Locale.getDefault())
        private val fullDateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())

        fun bind(session: ChatSession, selectedId: Long) {
            val context = binding.root.context
            val isSelected = session.id == selectedId

            // 제목 설정
            binding.tvSessionTitle.text = session.title

            // 상대적 날짜 표시
            binding.tvSessionDate.text = getRelativeTimeString(session.updatedAt)

            // 선택 상태에 따른 스타일 적용
            if (isSelected) {
                // 선택된 상태
                binding.accentLine.visibility = View.VISIBLE
                binding.cardSession.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.surface_variant)
                )
                binding.ivSessionIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.primary)
                )
                binding.iconContainer.background.setTint(
                    ContextCompat.getColor(context, R.color.primary_dark).let { color ->
                        android.graphics.Color.argb(40,
                            android.graphics.Color.red(color),
                            android.graphics.Color.green(color),
                            android.graphics.Color.blue(color))
                    }
                )
                binding.tvSessionTitle.setTextColor(
                    ContextCompat.getColor(context, R.color.text_primary)
                )
            } else {
                // 선택되지 않은 상태
                binding.accentLine.visibility = View.INVISIBLE
                binding.cardSession.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.card_background)
                )
                binding.ivSessionIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.text_secondary)
                )
                binding.iconContainer.background.setTint(
                    ContextCompat.getColor(context, R.color.surface_variant)
                )
                binding.tvSessionTitle.setTextColor(
                    ContextCompat.getColor(context, R.color.text_primary)
                )
            }

            // 클릭 이벤트
            binding.root.setOnClickListener {
                onSessionClick(session)
            }

            binding.btnDeleteSession.setOnClickListener {
                onDeleteClick(session)
            }
        }

        private fun getRelativeTimeString(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
            val date = Date(timestamp)

            return when {
                timestamp >= todayStart -> "오늘 ${timeFormat.format(date)}"
                timestamp >= yesterdayStart -> "어제 ${timeFormat.format(date)}"
                diff < TimeUnit.DAYS.toMillis(7) -> {
                    val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()
                    "${days}일 전"
                }
                else -> fullDateFormat.format(date)
            }
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(oldItem: ChatSession, newItem: ChatSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatSession, newItem: ChatSession): Boolean {
            return oldItem == newItem
        }
    }
}
