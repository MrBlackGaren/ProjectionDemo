package com.projection.screen.server

import android.app.Application
import android.os.Looper
import android.util.Log

class MyApp : Application() {
    private val TAG = "MyApp"
    private val START = ">>>>> Dispatching"
    private val END = "<<<<< Finished"
    
    // 性能监控阈值，超过此值的消息处理将被记录，单位：毫秒
    private val PERFORMANCE_THRESHOLD = 16L // 60fps标准下的单帧时间
    
    // 用于存储消息开始时间的映射表
    private val messageStartTimes = HashMap<String, Long>() // 由于所有操作都在主线程，使用普通HashMap即可

    override fun onCreate() {
        super.onCreate()
        
        // 设置主线程消息日志监听，用于性能监控
        /*Looper.getMainLooper().setMessageLogging { logMessage ->
            if (logMessage.startsWith(START)) {
                // 记录消息处理开始时间
                val content = logMessage.substring(START.length).trim()
                val messageId = generateMessageId(content)
                messageStartTimes[messageId] = System.currentTimeMillis()
//                Log.d(TAG, "$START Original: '$logMessage'" )
//                Log.d(TAG, "$START Content: '$content', Message ID: $messageId")
            } else if (logMessage.startsWith(END)) {
                // 计算消息处理耗时
                val messageId = extractMessageId(logMessage)
                val startTime = messageStartTimes.remove(messageId)
                
//                Log.d(TAG, "$END Original: '$logMessage'")
                val endContent = logMessage.substring(END.length).trim()
//                Log.d(TAG, "$END Content: '$endContent', Message ID: $messageId")
                
                if (startTime != null) {
                    val processingTime = System.currentTimeMillis() - startTime
                    val logContent = logMessage.substring(END.length)
                    
                    // 记录消息处理完成信息
//                    Log.d(TAG, "$END Message ID: $messageId, Time: $processingTime ms")
                    
                    // 检测性能问题：如果处理时间超过阈值，则打印警告日志
                    if (processingTime > PERFORMANCE_THRESHOLD) {
                        Log.w(TAG, "PERFORMANCE WARNING: Message processing took too long! " +
                                "Message ID: $messageId, Time: $processingTime ms (Threshold: $PERFORMANCE_THRESHOLD ms)" +
                                "\nContent: $logContent")
                    }
                } else {
                    Log.w(TAG, "not found dispatch message")
                }
            }
        }*/
        
        Log.i(TAG, "MyApp performance monitoring initialized with threshold: $PERFORMANCE_THRESHOLD ms")
    }
    
    /**
     * 生成消息ID，基于消息内容的哈希值
     * 确保消息开始和结束时使用相同的ID，忽略末尾的数字后缀
     */
    private fun generateMessageId(messageContent: String): String {
        // 移除消息内容末尾的数字后缀（如": 0"），确保开始和结束消息ID一致
        val normalizedContent = messageContent.replace(Regex(":\\s*\\d+$"), "").trim()
        return "MSG_${normalizedContent.hashCode()}"
    }
    
    /**
     * 从结束日志中提取消息ID
     * 与generateMessageId使用相同的逻辑，确保ID一致
     */
    private fun extractMessageId(endLog: String): String {
        val content = endLog.substring(END.length).trim()
        return generateMessageId(content) // 使用相同的ID生成逻辑
    }
}