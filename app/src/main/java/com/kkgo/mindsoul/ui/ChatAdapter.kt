/*
 * ============================================================
 * ChatAdapter - 聊天消息列表适配器（升级版）
 * ============================================================
 *
 * 支持的消息类型展示：
 * 1. TEXT    - 纯文本气泡
 * 2. IMAGE   - 缩略图 + 点击查看大图
 * 3. FILE    - 文件卡片（图标 + 文件名 + 大小）
 * 4. VOICE   - 语音波形 + 播放按钮 + 时长
 * 5. VIDEO   - 视频缩略图 + 播放叠加
 * 6. LINK    - URL 文本
 * 7. SYSTEM  - 居中灰色提示文字
 *
 * 每种消息类型区分 AI 侧（左）和 USER 侧（右）布局
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.kkgo.mindsoul.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 聊天消息列表适配器
 *
 * @param onImageClick 点击图片时的回调（用于查看大图）
 * @param onFileClick  点击文件时的回调（用于打开文件）
 */
class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf(),
    private var onImageClick: ((ChatMessage) -> Unit)? = null,
    private var onFileClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    /** 长按消息回调（用于TTS朗读） */
    private var onMessageLongClick: ((ChatMessage) -> Unit)? = null

    /** 复制消息回调 */
    var onCopyClick: ((ChatMessage) -> Unit)? = null
    /** 编辑消息回调（仅用户消息可编辑） */
    var onEditClick: ((ChatMessage) -> Unit)? = null
    /** 删除消息回调 */
    var onDeleteClick: ((ChatMessage, Int) -> Unit)? = null

    /** 时间格式化器 */
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ============ 数据操作 ============

    /**
     * 添加一条消息
     */
    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    /**
     * 批量设置消息列表（从数据库恢复时使用）
     */
    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    /**
     * 获取所有消息
     */
    fun getMessages(): List<ChatMessage> = messages.toList()

    /**
     * 清空所有消息
     */
    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    /**
     * 删除指定位置的消息
     */
    fun removeMessage(position: Int) {
        if (position in messages.indices) {
            messages.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    /**
     * 设置图片点击回调
     */
    fun setOnImageClickListener(listener: (ChatMessage) -> Unit) {
        onImageClick = listener
    }

    /**
     * 设置文件点击回调
     */
    fun setOnFileClickListener(listener: (ChatMessage) -> Unit) {
        onFileClick = listener
    }

    /**
     * 设置消息长按回调（用于TTS朗读）
     */
    fun setOnMessageLongClick(listener: (ChatMessage) -> Unit) {
        onMessageLongClick = listener
    }

    // ============ RecyclerView 核心方法 ============

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            MessageRole.USER -> 0
            MessageRole.AI -> 1
            MessageRole.SYSTEM -> 2
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        holder.bind(msg)
    }

    override fun getItemCount(): Int = messages.size

    // ============ ViewHolder ============

    /**
     * 消息项视图持有者
     * 负责将 ChatMessage 数据绑定到对应的视图元素
     */
    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // 系统消息
        private val tvSystem: TextView = itemView.findViewById(R.id.tvSystem)

        // AI 侧（左侧）
        private val layoutAi: LinearLayout = itemView.findViewById(R.id.layoutAi)
        private val tvAiText: TextView = itemView.findViewById(R.id.tvAiText)
        private val tvAiTime: TextView = itemView.findViewById(R.id.tvAiTime)
        private val ivAiImage: ImageView = itemView.findViewById(R.id.ivAiImage)
        private val layoutAiFile: LinearLayout = itemView.findViewById(R.id.layoutAiFile)
        private val tvAiFileIcon: TextView = itemView.findViewById(R.id.tvAiFileIcon)
        private val tvAiFileName: TextView = itemView.findViewById(R.id.tvAiFileName)
        private val tvAiFileSize: TextView = itemView.findViewById(R.id.tvAiFileSize)
        private val layoutAiVoice: LinearLayout = itemView.findViewById(R.id.layoutAiVoice)
        private val tvAiVoiceDuration: TextView = itemView.findViewById(R.id.tvAiVoiceDuration)
        private val layoutAiVideo: FrameLayout = itemView.findViewById(R.id.layoutAiVideo)

        // 用户侧（右侧）
        private val layoutUser: LinearLayout = itemView.findViewById(R.id.layoutUser)
        private val tvUserText: TextView = itemView.findViewById(R.id.tvUserText)
        private val tvUserTime: TextView = itemView.findViewById(R.id.tvUserTime)
        private val ivUserImage: ImageView = itemView.findViewById(R.id.ivUserImage)
        private val layoutUserFile: LinearLayout = itemView.findViewById(R.id.layoutUserFile)
        private val tvUserFileIcon: TextView = itemView.findViewById(R.id.tvUserFileIcon)
        private val tvUserFileName: TextView = itemView.findViewById(R.id.tvUserFileName)
        private val tvUserFileSize: TextView = itemView.findViewById(R.id.tvUserFileSize)
        private val layoutUserVoice: LinearLayout = itemView.findViewById(R.id.layoutUserVoice)
        private val tvUserVoiceDuration: TextView = itemView.findViewById(R.id.tvUserVoiceDuration)
        private val layoutUserVideo: FrameLayout = itemView.findViewById(R.id.layoutUserVideo)

        // 消息操作按钮（⋮ 按钮）
        private val btnAiOptions: TextView = itemView.findViewById(R.id.btnAiOptions)
        private val btnUserOptions: TextView = itemView.findViewById(R.id.btnUserOptions)

        /**
         * 绑定消息数据到视图
         */
        fun bind(msg: ChatMessage) {
            // 先隐藏所有布局
            resetViews()

            when (msg.role) {
                // ============ 系统消息 ============
                MessageRole.SYSTEM -> {
                    tvSystem.visibility = View.VISIBLE
                    tvSystem.text = msg.text
                }

                // ============ 用户消息（右侧） ============
                MessageRole.USER -> {
                    layoutUser.visibility = View.VISIBLE
                    bindUserMessage(msg)
                    tvUserTime.text = timeFormat.format(Date(msg.timestamp))
                    // 显示操作按钮
                    btnUserOptions.visibility = View.VISIBLE
                    btnUserOptions.setOnClickListener { anchor ->
                        showPopupMenu(anchor, msg, isUser = true)
                    }
                    // 长按用户消息触发TTS朗读
                    layoutUser.setOnLongClickListener {
                        onMessageLongClick?.invoke(msg)
                        true
                    }
                }

                // ============ AI 消息（左侧） ============
                MessageRole.AI -> {
                    layoutAi.visibility = View.VISIBLE
                    bindAiMessage(msg)
                    tvAiTime.text = timeFormat.format(Date(msg.timestamp))
                    // 显示操作按钮
                    btnAiOptions.visibility = View.VISIBLE
                    btnAiOptions.setOnClickListener { anchor ->
                        showPopupMenu(anchor, msg, isUser = false)
                    }
                    // 长按AI消息触发TTS朗读
                    layoutAi.setOnLongClickListener {
                        onMessageLongClick?.invoke(msg)
                        true
                    }
                }
            }
        }

        /**
         * 重置所有视图为隐藏状态
         */
        private fun resetViews() {
            tvSystem.visibility = View.GONE
            layoutAi.visibility = View.GONE
            layoutUser.visibility = View.GONE
            // AI 侧子视图
            tvAiText.visibility = View.GONE
            ivAiImage.visibility = View.GONE
            layoutAiFile.visibility = View.GONE
            layoutAiVoice.visibility = View.GONE
            layoutAiVideo.visibility = View.GONE
            // 用户侧子视图
            tvUserText.visibility = View.GONE
            ivUserImage.visibility = View.GONE
            layoutUserFile.visibility = View.GONE
            layoutUserVoice.visibility = View.GONE
            layoutUserVideo.visibility = View.GONE
            // 操作按钮（默认隐藏）
            btnAiOptions.visibility = View.GONE
            btnUserOptions.visibility = View.GONE
        }

        /**
         * 绑定用户侧消息内容
         */
        private fun bindUserMessage(msg: ChatMessage) {
            when (msg.type) {
                MessageType.TEXT -> {
                    tvUserText.visibility = View.VISIBLE
                    tvUserText.text = msg.text
                }
                MessageType.IMAGE -> {
                    tvUserText.visibility = View.VISIBLE
                    tvUserText.text = "🖼️ ${msg.text}"
                    // 如果有图片路径，加载缩略图
                    if (!msg.filePath.isNullOrEmpty()) {
                        ivUserImage.visibility = View.VISIBLE
                        tvUserText.visibility = View.GONE
                        loadImageFromUri(itemView.context, msg.filePath, ivUserImage)
                        ivUserImage.setOnClickListener {
                            onImageClick?.invoke(msg)
                        }
                    }
                }
                MessageType.FILE -> {
                    layoutUserFile.visibility = View.VISIBLE
                    val icon = getFileIcon(msg.fileName ?: msg.text)
                    tvUserFileIcon.text = icon
                    tvUserFileName.text = msg.fileName ?: msg.text
                    tvUserFileSize.text = formatFileSize(msg.fileSize)
                    layoutUserFile.setOnClickListener {
                        onFileClick?.invoke(msg)
                    }
                }
                MessageType.VOICE -> {
                    layoutUserVoice.visibility = View.VISIBLE
                    tvUserVoiceDuration.text = formatDuration(msg.duration)
                }
                MessageType.VIDEO -> {
                    layoutUserVideo.visibility = View.VISIBLE
                    if (!msg.filePath.isNullOrEmpty()) {
                        // 加载视频缩略图
                        val ivThumb: ImageView = itemView.findViewById(R.id.ivUserVideoThumb)
                        loadImageFromUri(itemView.context, msg.filePath, ivThumb)
                    }
                }
                MessageType.LINK -> {
                    tvUserText.visibility = View.VISIBLE
                    tvUserText.text = "🔗 ${msg.text}"
                }
                MessageType.SYSTEM -> {
                    tvUserText.visibility = View.VISIBLE
                    tvUserText.text = msg.text
                }
            }
        }

        /**
         * 绑定 AI 侧消息内容
         */
        private fun bindAiMessage(msg: ChatMessage) {
            when (msg.type) {
                MessageType.TEXT -> {
                    tvAiText.visibility = View.VISIBLE
                    tvAiText.text = msg.text
                }
                MessageType.IMAGE -> {
                    tvAiText.visibility = View.VISIBLE
                    tvAiText.text = "🖼️ ${msg.text}"
                    if (!msg.filePath.isNullOrEmpty()) {
                        ivAiImage.visibility = View.VISIBLE
                        tvAiText.visibility = View.GONE
                        loadImageFromUri(itemView.context, msg.filePath, ivAiImage)
                        ivAiImage.setOnClickListener {
                            onImageClick?.invoke(msg)
                        }
                    }
                }
                MessageType.FILE -> {
                    layoutAiFile.visibility = View.VISIBLE
                    val icon = getFileIcon(msg.fileName ?: msg.text)
                    tvAiFileIcon.text = icon
                    tvAiFileName.text = msg.fileName ?: msg.text
                    tvAiFileSize.text = formatFileSize(msg.fileSize)
                    layoutAiFile.setOnClickListener {
                        onFileClick?.invoke(msg)
                    }
                }
                MessageType.VOICE -> {
                    layoutAiVoice.visibility = View.VISIBLE
                    tvAiVoiceDuration.text = formatDuration(msg.duration)
                }
                MessageType.VIDEO -> {
                    layoutAiVideo.visibility = View.VISIBLE
                    if (!msg.filePath.isNullOrEmpty()) {
                        val ivThumb: ImageView = itemView.findViewById(R.id.ivAiVideoThumb)
                        loadImageFromUri(itemView.context, msg.filePath, ivThumb)
                    }
                }
                MessageType.LINK -> {
                    tvAiText.visibility = View.VISIBLE
                    tvAiText.text = "🔗 ${msg.text}"
                }
                MessageType.SYSTEM -> {
                    tvAiText.visibility = View.VISIBLE
                    tvAiText.text = msg.text
                }
            }
        }

        /**
         * 显示消息操作弹出菜单
         *
         * @param anchor 锚点视图（⋮ 按钮）
         * @param msg 当前消息
         * @param isUser 是否为用户消息
         */
        private fun showPopupMenu(anchor: View, msg: ChatMessage, isUser: Boolean) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, "📋 复制")
            if (isUser && msg.type == MessageType.TEXT) {
                popup.menu.add(0, 2, 1, "✏️ 编辑")
            }
            if (msg.role != MessageRole.SYSTEM) {
                popup.menu.add(0, 3, 2, "🗑️ 删除")
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> onCopyClick?.invoke(msg)
                    2 -> onEditClick?.invoke(msg)
                    3 -> {
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            onDeleteClick?.invoke(msg, pos)
                        }
                    }
                }
                true
            }
            popup.show()
        }
    }

    // ============ 工具方法 ============

    /**
     * 根据文件扩展名返回对应的 emoji 图标
     */
    private fun getFileIcon(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "📕"
            "doc", "docx" -> "📘"
            "xls", "xlsx", "csv" -> "📗"
            "ppt", "pptx" -> "📙"
            "zip", "rar", "7z" -> "🗜️"
            "mp3", "wav", "ogg" -> "🎵"
            "mp4", "avi", "mkv" -> "🎬"
            "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
            "txt", "md" -> "📝"
            "html", "xml", "json" -> "🌐"
            "apk" -> "📦"
            "dex" -> "⚙️"
            else -> "📄"
        }
    }

    /**
     * 格式化文件大小为可读字符串
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "未知大小"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * 格式化语音时长（毫秒 -> mm:ss）
     */
    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "0:00"
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", minutes, secs)
    }

    /**
     * 从 URI 字符串加载图片到 ImageView
     * 简化版本，实际项目中应使用 Glide/Coil
     */
    private fun loadImageFromUri(context: Context, uriStr: String, imageView: ImageView) {
        try {
            val uri = Uri.parse(uriStr)
            imageView.setImageURI(uri)
        } catch (e: Exception) {
            // URI 无效时显示占位符
            imageView.setBackgroundColor(context.getColor(R.color.soul_surface_light))
        }
    }
}
