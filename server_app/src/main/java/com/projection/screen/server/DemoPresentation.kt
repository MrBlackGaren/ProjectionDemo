package com.projection.screen.server

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DemoPresentation - 在虚拟屏上显示内容的演示窗口
 *
 * Presentation是Android系统用于在辅助显示设备上显示内容的API，
 * 它会自动适配目标显示设备的分辨率和尺寸。
 *
 * 【宽高适配机制】
 *
 * 1. Display尺寸传递：
 *    - Presentation构造函数接收Display对象作为参数
 *    - Display对象代表了目标虚拟屏，其尺寸由创建VirtualDisplay时指定
 *    - 在ProjectionForegroundService中创建VirtualDisplay时，使用了屏幕的实际尺寸：
 *      screenWidth和screenHeight（从Activity获取的设备屏幕尺寸）
 *
 * 2. 自动适配原理：
 *    - Presentation的窗口会自动使用目标Display的尺寸作为其窗口大小
 *    - 系统会将Presentation的内容缩放以适应目标显示设备的分辨率
 *    - 通过getDisplay()方法可以获取当前Presentation所在的Display对象
 *
 * 3. 布局文件匹配：
 *    - presentation_demo.xml中的布局使用match_parent
 *    - 这确保了内容会填满整个Presentation窗口
 *    - 由于Presentation窗口尺寸与虚拟屏尺寸一致，内容自然适配虚拟屏
 *
 * 4. 触摸坐标转换：
 *    - 触摸事件从主屏TextureView捕获后直接传递给Presentation
 *    - 坐标系统基于各自屏幕坐标系，不需要额外转换
 *    - VirtualDisplay与主屏尺寸相同，坐标可以直接使用
 *
 * @param context 上下文对象
 * @param display 目标显示设备，Presentation会自动适配此设备的尺寸
 */
class DemoPresentation : Presentation {

    companion object {
        private const val TAG = "DemoPresentation"
    }

    private var isRunning = true

    /**
     * 触摸轨迹画笔
     * 用于绘制用户触摸时留下的红色轨迹线条
     */
    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    /**
     * 文本画笔
     * 用于绘制触摸信息的文字
     */
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    /**
     * 触摸点列表
     * 存储用户在虚拟屏上触摸的位置，用于绘制轨迹
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
     * 构造函数
     * @param context 上下文
     * @param display 目标显示设备，尺寸由创建VirtualDisplay时决定
     */
    constructor(context: Context, display: Display) : super(context, display) {
        Log.i(TAG, "DemoPresentation created on display: ${display.name}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_demo)

        val contentTextView = findViewById<TextView>(R.id.demo_content)
        val display = display
        val displayInfo = "这是在虚拟屏上运行的演示内容\n\n显示ID: ${display.displayId}\n触摸屏幕可绘制轨迹"
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
        setOnShowListener {
            Log.d(TAG, "onCreate: onShow")
            // 开始计数和时间更新
            handler.postDelayed(countRunnable, 1000)
            handler.postDelayed(timeRunnable, 1000)
        }
        setOnDismissListener {
            Log.d(TAG, "onCreate: onDismiss")
            // 停止计数和时间更新
            handler.removeCallbacks(countRunnable)
            handler.removeCallbacks(timeRunnable)
        }
        Log.i(TAG, "DemoPresentation initialized")
    }

    /**
     * 处理触摸事件回调
     * 当用户在主屏TextureView上触摸时，此方法会被调用
     * @param event 触摸事件对象，包含坐标和动作类型
     * @return true表示事件已处理
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(TAG, "Received touch event: action=${event.action}, x=${event.x}, y=${event.y}")

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

        val contentView = findViewById<android.view.View>(R.id.demo_content)
        contentView?.invalidate()

        Log.d(TAG, "Touch event processed: $actionString at (${event.x}, ${event.y})")
        return true
    }

    private fun showButtonClicked() {
        Log.i(TAG, "演示按钮被点击")
    }

    override fun onStart() {
        super.onStart()
        isRunning = true
        Log.i(TAG, "DemoPresentation started")
    }

    override fun onStop() {
        super.onStop()
        isRunning = false
        Log.i(TAG, "DemoPresentation stopped")
    }
}
