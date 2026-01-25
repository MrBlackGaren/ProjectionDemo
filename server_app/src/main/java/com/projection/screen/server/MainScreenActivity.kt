package com.projection.screen.server

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainScreenActivity - 在主屏幕上显示内容的演示Activity
 * 
 * 这个Activity的内容和DemoPresentation类似，但运行在主屏幕上。
 * 用于演示投屏主屏幕内容到客户端的功能。
 */
class MainScreenActivity : Activity() {

    companion object {
        private const val TAG = "MainScreenActivity"
        // 编码分辨率（与服务端一致）
        private const val ENCODED_WIDTH = 1280
        private const val ENCODED_HEIGHT = 720
    }

    /**
     * 触摸点列表
     * 存储用户在主屏幕上触摸的位置，用于绘制轨迹
     */
    private val touchPoints = mutableListOf<TouchPoint>()

    /**
     * 触摸点数据类
     * @param x X坐标
     * @param y Y坐标
     * @param action 触摸动作类型（DOWN/MOVE/UP/CANCEL）
     */
    private data class TouchPoint(val x: Float, val y: Float, val action: Int)

    private var touchInfoTextView: TextView? = null
    private var demoButton: Button? = null
    private var countTextView: TextView? = null
    private var timeTextView: TextView? = null
    private var count = 0
    private val handler = Handler(Looper.getMainLooper())
    private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    
    // 主屏幕实际尺寸（在onCreate中获取）
    private var screenWidth = 0
    private var screenHeight = 0
    
    private val countRunnable = object : Runnable {
        override fun run() {
            count++
            countTextView?.text = "计数: $count"
            Log.i(TAG, "计数: $count")
            handler.postDelayed(this, 1000)
        }
    }
    
    private val timeRunnable = object : Runnable {
        override fun run() {
            val currentTime = simpleDateFormat.format(Date())
            timeTextView?.text = "当前时间: $currentTime"
            Log.i(TAG, "当前时间: $currentTime")
            handler.postDelayed(this, 1000)
        }
    }
    
    /**
     * 触摸事件广播接收器
     */
    private val touchEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.projection.screen.server.TOUCH_EVENT") {
                val action = intent.getIntExtra("action", -1)
                val encodedX = intent.getFloatExtra("x", 0f)
                val encodedY = intent.getFloatExtra("y", 0f)
                val pressure = intent.getFloatExtra("pressure", 1f)
                val size = intent.getFloatExtra("size", 1f)
                
                // 将坐标从编码分辨率(1280x720)转换到主屏幕实际尺寸
                val scaleX = screenWidth.toFloat() / ENCODED_WIDTH
                val scaleY = screenHeight.toFloat() / ENCODED_HEIGHT
                val x = encodedX * scaleX
                val y = encodedY * scaleY
                
                Log.d(TAG, "坐标转换: 编码坐标=($encodedX, $encodedY) -> 主屏幕坐标=($x, $y)")
                
                // 创建MotionEvent
                val event = MotionEvent.obtain(
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    action,
                    x,
                    y,
                    pressure,
                    size,
                    0,
                    1f,
                    1f,
                    0,
                    0
                )
                
                // 处理触摸事件
                handleTouchEvent(event)
                event.recycle()
            } else if (intent?.action == Intent.ACTION_SCREEN_OFF || 
                       intent?.getBooleanExtra("finish", false) == true) {
                // 收到关闭信号，结束Activity
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_demo)
        
        // 获取主屏幕实际尺寸
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        Log.i(TAG, "主屏幕尺寸: ${screenWidth}x${screenHeight}")

        val contentTextView = findViewById<TextView>(R.id.demo_content)
        val displayInfo = "这是在主屏幕上运行的演示内容\n\n触摸屏幕可绘制轨迹\n屏幕尺寸: ${screenWidth}x${screenHeight}"
        contentTextView.text = displayInfo

        touchInfoTextView = findViewById(R.id.touch_info)
        demoButton = findViewById(R.id.demo_button)
        countTextView = findViewById(R.id.count_text)
        timeTextView = findViewById(R.id.time_text)
        
        // 设置初始计数文本
        countTextView?.text = "计数: 0"
        
        demoButton?.setOnClickListener {
            showButtonClicked()
        }
        
        // 设置触摸监听器
        val rootView = findViewById<View>(android.R.id.content)
        rootView?.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true
        }
        
        // 开始计数和时间更新
        handler.postDelayed(countRunnable, 1000)
        handler.postDelayed(timeRunnable, 1000)
        
        // 注册触摸事件广播接收器
        val filter = IntentFilter("com.projection.screen.server.TOUCH_EVENT")
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要指定RECEIVER_NOT_EXPORTED（因为这是应用内部广播）
            registerReceiver(touchEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(touchEventReceiver, filter)
        }
        
        Log.i(TAG, "MainScreenActivity initialized")
    }

    /**
     * 处理触摸事件
     * 当用户在客户端触摸时，此方法会被调用
     * @param event 触摸事件对象，包含坐标和动作类型
     */
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        Log.i(TAG, "Received touch event: action=${event.action}, x=${event.x}, y=${event.y}")

        val actionString = when (event.action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER"
        }

        touchPoints.add(TouchPoint(event.x, event.y, event.action))

        if (touchPoints.size > 100) {
            touchPoints.removeAt(0)
        }

        val touchInfo = "触摸事件: $actionString\n坐标: (${event.x.toInt()}, ${event.y.toInt()})\n点数: ${touchPoints.size}"
        touchInfoTextView?.text = touchInfo

        val contentView = findViewById<View>(R.id.demo_content)
        contentView?.invalidate()

        Log.d(TAG, "Touch event processed: $actionString at (${event.x}, ${event.y})")
        
        // 检查触摸是否在按钮区域内，如果是则手动触发点击
        if (event.action == MotionEvent.ACTION_UP) {
            demoButton?.let { button ->
                if (isPointInsideView(event.x, event.y, button)) {
                    Log.i(TAG, "触摸点在按钮区域内，触发点击")
                    button.performClick()
                }
            }
        }
        
        return true
    }
    
    /**
     * 检查坐标点是否在指定View区域内
     */
    private fun isPointInsideView(x: Float, y: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        
        // 判断触摸点是否在View的边界内
        return x >= viewX && x <= viewX + view.width &&
               y >= viewY && y <= viewY + view.height
    }

    private var buttonClickCount = 0
    
    private fun showButtonClicked() {
        buttonClickCount++
        Log.i(TAG, "演示按钮被点击，点击次数: $buttonClickCount")
        
        // 更新按钮文本显示点击次数
        demoButton?.text = "已点击 $buttonClickCount 次"
        
        // 显示点击反馈
        touchInfoTextView?.text = "按钮点击！\n总点击次数: $buttonClickCount"
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "MainScreenActivity started")
    }

    override fun onStop() {
        super.onStop()
        // 停止计数和时间更新
        handler.removeCallbacks(countRunnable)
        handler.removeCallbacks(timeRunnable)
        Log.i(TAG, "MainScreenActivity stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止计数和时间更新
        handler.removeCallbacks(countRunnable)
        handler.removeCallbacks(timeRunnable)
        
        // 注销广播接收器
        try {
            unregisterReceiver(touchEventReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败: " + e.message)
        }
        
        Log.i(TAG, "MainScreenActivity destroyed")
    }
}
