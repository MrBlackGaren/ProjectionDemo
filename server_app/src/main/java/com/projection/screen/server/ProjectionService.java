package com.projection.screen.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjection.Callback;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

/**
 * 投影服务 - 用于处理MediaProjection的前台服务
 * Android 12+要求MediaProjection必须在前台服务中使用
 */
public class ProjectionService extends Service {
    private static final String TAG = "ProjectionService";
    private static final String CHANNEL_ID = "projection_service_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaCodec mCodec;
    private Surface mInputSurface;
    
    private int WIDTH = 1280;
    private int HEIGHT = 720;
    private int BIT_RATE = 2000000;
    
    // Socket和OutputStream引用，用于发送H.264数据
    private java.net.Socket mSocket;
    private java.io.OutputStream mOutputStream;
    
    private final IBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        ProjectionService getService() {
            return ProjectionService.this;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ProjectionService onCreate");
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "ProjectionService onStartCommand");
        
        // 必须先启动前台服务，然后才能创建MediaProjection
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 如果MediaProjection已经存在，先停止并释放旧的资源
        if (mMediaProjection != null) {
            Log.w(TAG, "MediaProjection已存在，先停止旧的投影");
            try {
                if (mVirtualDisplay != null) {
                    mVirtualDisplay.release();
                    mVirtualDisplay = null;
                }
                if (mCodec != null) {
                    mCodec.stop();
                    mCodec.release();
                    mCodec = null;
                }
                mMediaProjection.stop();
                mMediaProjection = null;
                Log.i(TAG, "旧的MediaProjection已停止");
            } catch (Exception e) {
                Log.e(TAG, "停止旧的MediaProjection失败: " + e.getMessage());
                // 继续执行，尝试创建新的MediaProjection
            }
        }
        
        if (intent != null) {
            int resultCode = intent.getIntExtra("result_code", -2); // 使用-2作为默认值，因为-1是RESULT_OK
            Intent data = intent.getParcelableExtra("data");
            Log.i(TAG, "收到Intent: resultCode=" + resultCode + ", data=" + data);
            
            // 注意：RESULT_OK的值是-1，所以检查resultCode != -2
            if (resultCode != -2 && data != null) {
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (manager != null) {
                    mMediaProjection = manager.getMediaProjection(resultCode, data);
                    if (mMediaProjection != null) {
                        Log.i(TAG, "MediaProjection创建成功");
                        startProjection();
                    } else {
                        Log.e(TAG, "无法创建MediaProjection");
                        stopSelf();
                    }
                } else {
                    Log.e(TAG, "无法获取MediaProjectionManager");
                    stopSelf();
                }
            } else {
                Log.e(TAG, "Intent数据无效: resultCode=" + resultCode + ", data=" + data);
                stopSelf();
            }
        } else {
            Log.e(TAG, "Intent为null");
            stopSelf();
        }
        
        return START_STICKY;
    }
    
    /**
     * 启动投影
     */
    private void startProjection() {
        Log.i(TAG, "startProjection");
        
        try {
            configureMediaCodecAndCreateVirtualDisplay();
        } catch (IOException e) {
            Log.e(TAG, "启动投影失败: " + e.getMessage(), e);
            stopSelf();
        }
    }
    
    /**
     * 配置MediaCodec编码器和创建VirtualDisplay
     */
    private void configureMediaCodecAndCreateVirtualDisplay() throws IOException {
        Log.i(TAG, "configureMediaCodecAndCreateVirtualDisplay");
        
        // 必须先注册MediaProjection回调，然后才能创建VirtualDisplay
        if (mMediaProjection != null) {
            mMediaProjection.registerCallback(new Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection已停止");
                    // 停止编码和释放资源
                    if (mCodec != null) {
                        try {
                            mCodec.stop();
                            mCodec.release();
                        } catch (Exception e) {
                            Log.e(TAG, "释放编码器失败: " + e.getMessage());
                        }
                        mCodec = null;
                    }
                    
                    if (mVirtualDisplay != null) {
                        mVirtualDisplay.release();
                        mVirtualDisplay = null;
                    }
                    
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            Log.i(TAG, "MediaProjection回调已注册");
        }
        
        // 1. 配置 MediaCodec 编码器
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mCodec.createInputSurface();
        
        // 设置编码回调（这里需要从Activity传递回调）
        // 暂时不设置，等Activity连接后再设置
        
        // 2. 创建MediaProjection VirtualDisplay
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int density = metrics.densityDpi;
        
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            throw new IOException("DisplayManager获取失败");
        }
        
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "MainScreenCapture",
                WIDTH, HEIGHT, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface,
                null,
                null
        );
        
        if (mVirtualDisplay == null) {
            throw new IOException("MediaProjection VirtualDisplay创建失败");
        }
        
        Log.i(TAG, "MediaProjection VirtualDisplay创建成功: 宽=" + WIDTH + " 高=" + HEIGHT);
    }
    
    /**
     * 设置Socket和OutputStream，用于发送H.264数据
     */
    public void setSocket(java.net.Socket socket, java.io.OutputStream outputStream) {
        mSocket = socket;
        mOutputStream = outputStream;
        Log.i(TAG, "Socket和OutputStream已设置");
    }
    
    /**
     * 设置编码回调并启动编码器
     */
    public void setCodecCallback(MediaCodec.Callback callback) {
        if (mCodec != null && callback != null) {
            mCodec.setCallback(callback);
            mCodec.start();
            Log.i(TAG, "编码回调已设置，编码器已启动");
        } else {
            Log.e(TAG, "无法设置编码回调: mCodec=" + (mCodec != null) + ", callback=" + (callback != null));
        }
    }
    
    /**
     * 获取编码器（不启动，等待设置回调后再启动）
     */
    public MediaCodec getCodec() {
        return mCodec;
    }
    
    // 用于发送数据的线程池
    private java.util.concurrent.ExecutorService mSendExecutor;
    
    /**
     * 发送H.264数据到客户端
     */
    public void sendH264Data(byte[] data) {
        if (mSocket == null || mOutputStream == null) {
            Log.w(TAG, "Socket或OutputStream为null，无法发送数据");
            return;
        }
        
        if (!mSocket.isConnected()) {
            Log.w(TAG, "Socket未连接，无法发送数据");
            return;
        }
        
        // 在后台线程中发送数据，避免NetworkOnMainThreadException
        if (mSendExecutor == null) {
            mSendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        
        final byte[] dataToSend = data.clone(); // 复制数据，避免被修改
        mSendExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mSocket != null && mOutputStream != null && mSocket.isConnected()) {
                        mOutputStream.write(dataToSend);
                        mOutputStream.flush();
                    }
                } catch (java.io.IOException e) {
                    Log.e(TAG, "发送H.264数据失败: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "投影服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于屏幕投影的前台服务");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * 创建通知
     */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕投影")
                .setContentText("正在投影主屏幕内容")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ProjectionService onDestroy");
        
        // 关闭发送线程池
        if (mSendExecutor != null) {
            mSendExecutor.shutdown();
            try {
                if (!mSendExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    mSendExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                mSendExecutor.shutdownNow();
            }
            mSendExecutor = null;
        }
        
        // 释放资源
        if (mCodec != null) {
            try {
                mCodec.stop();
                mCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "释放编码器失败: " + e.getMessage());
            }
            mCodec = null;
        }
        
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }
}
