package com.projection.screen.server;

import android.util.Log;
import android.view.MotionEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 触摸事件接收器
 * <p>
 * 负责从车机APP接收触摸事件并传递给应用程序
 */
public class TouchEventReceiver {
    private static final String TAG = "TouchEventReceiver";

    private Socket mSocket;
    private InputStream mInputStream;
    private ExecutorService mExecutorService;
    private TouchEventListener mTouchEventListener;
    private boolean mIsRunning;

    /**
     * 触摸事件监听器接口
     */
    public interface TouchEventListener {
        void onTouchEvent(MotionEvent event);
    }

    /**
     * 构造函数
     * 
     * @param socket Socket连接
     * @param listener 触摸事件监听器
     */
    public TouchEventReceiver(Socket socket, TouchEventListener listener) {
        this.mSocket = socket;
        this.mTouchEventListener = listener;
        this.mExecutorService = Executors.newSingleThreadExecutor();
    }

    /**
     * 启动触摸事件接收
     */
    public void start() {
        mIsRunning = true;
        mExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mInputStream = mSocket.getInputStream();
                    Log.i(TAG, "开始接收触摸事件");

                    byte[] buffer = new byte[1024];
                    while (mIsRunning && mInputStream != null) {
                        int bytesRead = mInputStream.read(buffer);
                        if (bytesRead == -1) break;

                        if (bytesRead > 0) {
                            processTouchData(buffer, bytesRead);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "接收触摸事件失败: " + e.getMessage(), e);
                }
            }
        });
    }

    /**
     * 处理触摸事件数据
     * 
     * @param data 触摸事件数据
     * @param length 数据长度
     */
    private void processTouchData(byte[] data, int length) {
        if (data[0] != 'T') {
            // 不是触摸事件
            return;
        }

        if (length != 10) {
            Log.e(TAG, "触摸事件数据长度不正确: " + length);
            return;
        }

        // 解析触摸事件
        int action = data[1] & 0xFF;
        int x = ((data[2] & 0xFF) << 24) |
                ((data[3] & 0xFF) << 16) |
                ((data[4] & 0xFF) << 8) |
                (data[5] & 0xFF);
        int y = ((data[6] & 0xFF) << 24) |
                ((data[7] & 0xFF) << 16) |
                ((data[8] & 0xFF) << 8) |
                (data[9] & 0xFF);

        Log.d(TAG, "收到触摸事件: action=" + action + ", x=" + x + ", y=" + y);

        // 创建MotionEvent
        long downTime = System.currentTimeMillis();
        long eventTime = System.currentTimeMillis();
        float pressure = 1.0f;
        float size = 1.0f;
        int metaState = 0;
        float xPrecision = 1.0f;
        float yPrecision = 1.0f;
        int deviceId = 0;
        int edgeFlags = 0;

        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                pressure,
                size,
                metaState,
                xPrecision,
                yPrecision,
                deviceId,
                edgeFlags
        );

        // 传递触摸事件
        if (mTouchEventListener != null) {
            mTouchEventListener.onTouchEvent(event);
        }

        // 回收MotionEvent
        event.recycle();
    }

    /**
     * 停止触摸事件接收
     */
    public void stop() {
        mIsRunning = false;
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭输入流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
        mExecutorService.shutdown();
        mTouchEventListener = null;
    }
}
