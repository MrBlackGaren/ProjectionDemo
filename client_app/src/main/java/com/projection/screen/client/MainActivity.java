package com.projection.screen.client;

import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements SurfaceHolder.Callback, View.OnTouchListener {
    private static final String TAG = "ClientDisplay";
    private static final int PORT = 8888;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private TextView tvIpAddress;
    private TextView tvPort;
    private TextView tvStatus;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private MediaCodec mediaCodec;
    private HandlerThread decodingThread;
    private Handler decodingHandler;
    private ExecutorService executorService;
    // 独立的触摸事件发送线程池，避免被视频接收阻塞
    private ExecutorService touchEventExecutor;

    private boolean isRunning = false;
    private int frameCount = 0;
    
    // 缓存SPS和PPS数据，用于处理分开发送的情况
    private byte[] cachedSPS = null;
    private byte[] cachedPPS = null;
    
    // 用于保存H.264数据到本地文件
    private FileOutputStream h264FileOutputStream;
    
    // 用于防止设备休眠导致网络连接中断
    private PowerManager.WakeLock wakeLock;
    
    // Surface是否已准备好
    private volatile boolean surfaceReady = false;
    
    // 解码器是否已配置并启动
    private volatile boolean decoderConfigured = false;
    
    // 连接状态
    private volatile boolean isClientConnected = false;
    
    // 心跳检测
    private Handler heartbeatCheckHandler;
    private long lastHeartbeatTime = 0;
    private static final int HEARTBEAT_CHECK_INTERVAL = 5000; // 5秒检查一次
    private static final int HEARTBEAT_TIMEOUT = 10000; // 10秒没收到心跳视为断开
    private static final byte[] HEARTBEAT_DATA = new byte[]{'H', 'B'}; // 心跳数据包

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "===== onCreate方法开始执行 =====");
        setContentView(R.layout.activity_main);
        Log.i(TAG, "设置布局完成");

        initViews();
        Log.i(TAG, "初始化视图完成");
        
        updateIpAddress();
        Log.i(TAG, "更新IP地址完成");
        
        try {
            startServer();
            Log.i(TAG, "启动服务器方法调用完成");
        } catch (Exception e) {
            Log.e(TAG, "启动服务器方法调用失败: " + e.getMessage(), e);
        }
        
        // 初始化WakeLock，防止设备休眠导致网络中断
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarDisplay:NetworkLock");
        Log.i(TAG, "WakeLock初始化完成");
        
        // 初始化心跳检测Handler
        heartbeatCheckHandler = new Handler(getMainLooper());
        
        Log.i(TAG, "===== onCreate方法执行完成 =====");
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        surfaceView = findViewById(R.id.surfaceView);
        tvIpAddress = findViewById(R.id.tv_ip_address);
        tvPort = findViewById(R.id.tv_port);
        tvStatus = findViewById(R.id.tv_status);

        // SurfaceView在布局中使用 constraintDimensionRatio="H,16:9" 自适应屏幕
        // 获取并打印实际的SurfaceView尺寸
        ViewTreeObserver vto = surfaceView.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                surfaceView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int width = surfaceView.getWidth();
                int height = surfaceView.getHeight();
                Log.i(TAG, "onGlobalLayout SurfaceView实际尺寸: " + width + "x" + height);
            }
        });
        // 确保SurfaceView获取焦点
        surfaceView.requestFocus();

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceView.setOnTouchListener(this);

        tvPort.setText(getString(R.string.tv_port, PORT));
        updateStatus(getString(R.string.status_listening));
    }

    /**
     * 更新IP地址显示
     */
    private void updateIpAddress() {
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        String ip = String.format(Locale.CHINA, "%d.%d.%d.%d",
                (ipAddress & 0xFF),
                (ipAddress >> 8 & 0xFF),
                (ipAddress >> 16 & 0xFF),
                (ipAddress >> 24 & 0xFF));
        tvIpAddress.setText(getString(R.string.tv_ip_address, ip));
    }

    /**
     * 更新连接状态显示
     */
    private void updateStatus(String status) {
        runOnUiThread(() -> tvStatus.setText(getString(R.string.tv_status, status)));
    }
    
    /**
     * 更新连接状态（内部状态和UI）
     */
    private void updateConnectionState(boolean connected) {
        isClientConnected = connected;
        if (connected) {
            lastHeartbeatTime = System.currentTimeMillis();
            updateStatus(getString(R.string.status_connected));
        } else {
            updateStatus(getString(R.string.status_disconnected));
        }
    }
    
    /**
     * 启动心跳检测
     */
    private void startHeartbeatCheck() {
        lastHeartbeatTime = System.currentTimeMillis();
        heartbeatCheckHandler.postDelayed(heartbeatCheckRunnable, HEARTBEAT_CHECK_INTERVAL);
        Log.i(TAG, "心跳检测已启动");
    }
    
    /**
     * 停止心跳检测
     */
    private void stopHeartbeatCheck() {
        heartbeatCheckHandler.removeCallbacks(heartbeatCheckRunnable);
        Log.i(TAG, "心跳检测已停止");
    }
    
    /**
     * 心跳检测任务
     */
    private final Runnable heartbeatCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (isClientConnected) {
                long currentTime = System.currentTimeMillis();
                long timeSinceLastHeartbeat = currentTime - lastHeartbeatTime;
                
                if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT) {
                    Log.w(TAG, "心跳超时，连接可能已断开，超时时间: " + timeSinceLastHeartbeat + "ms");
                    // 不立即断开，只更新状态显示
                    updateStatus("连接不稳定");
                } else {
                    Log.d(TAG, "心跳正常，距上次心跳: " + timeSinceLastHeartbeat + "ms");
                }
                
                // 继续检测
                heartbeatCheckHandler.postDelayed(this, HEARTBEAT_CHECK_INTERVAL);
            }
        }
    };
    
    /**
     * 检查是否是心跳数据包
     */
    private boolean isHeartbeat(byte[] data, int length) {
        if (length == 2 && data[0] == 'H' && data[1] == 'B') {
            return true;
        }
        return false;
    }
    
    /**
     * 处理接收到的心跳包
     */
    private void handleHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis();
        Log.d(TAG, "收到心跳包，更新心跳时间");
        
        // 确保连接状态正确
        if (!isClientConnected) {
            updateConnectionState(true);
        }
    }

    /**
     * 启动服务器
     */
    private void startServer() {
        Log.i(TAG, "准备启动服务器");
        
        // 关闭旧的线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
        }
        
        // 创建新的线程池
        executorService = Executors.newSingleThreadExecutor();
        
        // 创建触摸事件发送线程池（独立于视频接收线程）
        if (touchEventExecutor == null || touchEventExecutor.isShutdown()) {
            touchEventExecutor = Executors.newSingleThreadExecutor();
        }
        
        executorService.execute(() -> {
            try {
                // 确保所有旧资源已释放
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                    serverSocket = null;
                    Log.i(TAG, "已关闭旧的ServerSocket");
                }
                
                // 强制释放旧的Socket资源
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                    clientSocket = null;
                }
                if (inputStream != null) {
                    inputStream.close();
                    inputStream = null;
                }
                if (outputStream != null) {
                    outputStream.close();
                    outputStream = null;
                }
                if (h264FileOutputStream != null) {
                    h264FileOutputStream.close();
                    h264FileOutputStream = null;
                }
                
                // 初始化MediaCodec相关资源
                cachedSPS = null;
                cachedPPS = null;
                if (mediaCodec != null) {
                    try {
                        mediaCodec.stop();
                        mediaCodec.release();
                        mediaCodec = null;
                    } catch (Exception e) {
                        Log.e(TAG, "释放解码器资源失败: " + e.getMessage());
                    }
                }
                
                Log.i(TAG, "创建ServerSocket，监听端口: " + PORT);
                // 设置SO_REUSEADDR选项，允许地址重用
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                Log.i(TAG, "服务器已成功启动，监听端口: " + PORT);

                while (true) {
                    Log.i(TAG, "等待客户端连接...");
                    try {
                        clientSocket = serverSocket.accept();
                        Log.i(TAG, "客户端已连接: " + clientSocket.getInetAddress().getHostAddress());
                        Log.i(TAG, "客户端Socket信息: " + clientSocket.toString());
                        
                        // 更新连接状态
                        updateConnectionState(true);

                        // 获取输入输出流
                        Log.i(TAG, "获取客户端输入输出流");
                        inputStream = clientSocket.getInputStream();
                        outputStream = clientSocket.getOutputStream();
                        Log.i(TAG, "输入输出流获取成功");

                        // 初始化MediaCodec解码器
                        Log.i(TAG, "准备初始化MediaCodec解码器");
                        initMediaCodec();
                        Log.i(TAG, "MediaCodec解码器初始化完成");

                        // 初始化H.264文件输出流
                        Log.i(TAG, "准备初始化H.264文件输出流");
                        try {
                            // 使用当前时间生成文件名
                            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
                            String timestamp = dateFormat.format(new Date());
                            String filename = "received_h264_data_" + timestamp + ".h264";
                            File h264File = new File(getExternalFilesDir(null), filename);
                            h264FileOutputStream = new FileOutputStream(h264File);
                            Log.i(TAG, "H.264文件输出流初始化成功，保存路径: " + h264File.getAbsolutePath());
                        } catch (IOException e) {
                            Log.e(TAG, "初始化H.264文件输出流失败: " + e.getMessage(), e);
                        }
                        
                        // 启动心跳检测
                        startHeartbeatCheck();

                        // 接收H.264数据
                        Log.i(TAG, "准备接收H.264数据");
                        receiveH264Data();
                        
                        // 接收数据结束，连接已断开
                        updateConnectionState(false);
                        stopHeartbeatCheck();
                        
                    } catch (IOException e) {
                        Log.e(TAG, "startServer IO异常，客户端连接处理失败: ");
                        
                        // 更新连接状态
                        updateConnectionState(false);
                        stopHeartbeatCheck();
                        
                        // 关闭可能已打开的资源
                        try {
                            if (clientSocket != null && !clientSocket.isClosed()) {
                                clientSocket.close();
                                clientSocket = null;
                            }
                            if (inputStream != null) {
                                inputStream.close();
                                inputStream = null;
                            }
                            if (outputStream != null) {
                                outputStream.close();
                                outputStream = null;
                            }
                        } catch (IOException ex) {
                            Log.e(TAG, "关闭资源失败: " + ex.getMessage(), ex);
                        }
                        
                        // 继续等待下一个连接
                        continue;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "服务器错误: " + e.getMessage(), e);
                updateStatus(getString(R.string.status_disconnected));
                
                // 服务器异常关闭后，尝试重新启动服务器
                try {
                    Thread.sleep(3000); // 等待3秒后重新启动
                    startServer();
                } catch (InterruptedException ie) {
                    Log.e(TAG, "重新启动服务器被中断: " + ie.getMessage());
                }
            }
        });
    }

    /**
     * 初始化解码线程（解码器实例在收到SPS/PPS后创建）
     */
    private void initMediaCodec() {
        Log.i(TAG, "初始化解码线程");
        
        // 如果解码线程已存在，先停止
        if (decodingThread != null) {
            decodingThread.quitSafely();
            decodingThread = null;
            decodingHandler = null;
        }
        
        // 创建解码线程
        decodingThread = new HandlerThread("DecodingThread");
        decodingThread.start();
        decodingHandler = new Handler(decodingThread.getLooper());
        Log.i(TAG, "解码线程创建成功");
        
        // 重置解码器状态
        cachedSPS = null;
        cachedPPS = null;
        decoderConfigured = false;
        
        // 如果已有解码器，释放它
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.w(TAG, "释放旧解码器: " + e.getMessage());
            }
            mediaCodec = null;
        }
    }

    /**
     * 配置MediaCodec解码器
     * @param sps SPS数据
     * @param pps PPS数据
     */
    private void configureMediaCodec(byte[] sps, byte[] pps) {
        try {
            Log.i(TAG, "开始配置MediaCodec解码器");
            
            // 检查SurfaceHolder和Surface的状态
            if (surfaceHolder == null) {
                Log.e(TAG, "SurfaceHolder为null，无法配置解码器");
                return;
            }
            
            Surface surface = surfaceHolder.getSurface();
            if (surface == null) {
                Log.e(TAG, "Surface为null，无法配置解码器");
                return;
            }
            
            if (!surface.isValid()) {
                Log.e(TAG, "Surface无效，无法配置解码器");
                return;
            }
            
            Log.d(TAG, "Surface有效: " + surface.isValid());
            
            // 确保解码器处于未配置或已停止状态
            if (mediaCodec != null) {
                try {
                    // 检查解码器状态
                    try {
                        mediaCodec.stop();
                        Log.d(TAG, "解码器已停止");
                    } catch (IllegalStateException e) {
                        // 如果已经停止，忽略异常
                        Log.d(TAG, "解码器可能已经停止: " + e.getMessage());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "停止解码器时发生异常: " + e.getMessage());
                }
            }
            
            // 不移除SPS/PPS中的起始码，某些设备需要它们
            byte[] cleanSPS = sps;
            byte[] cleanPPS = pps;
            
            Log.i(TAG, "SPS大小: " + sps.length + " 字节");
            Log.i(TAG, "PPS大小: " + pps.length + " 字节");
            Log.i(TAG, "SPS前8字节: " + bytesToHex(cleanSPS, Math.min(8, cleanSPS.length)));
            Log.i(TAG, "PPS前8字节: " + bytesToHex(cleanPPS, Math.min(8, cleanPPS.length)));
            
            // 使用固定的视频分辨率1280x720来配置解码器，与服务端的编码分辨率匹配
            int fixedWidth = 1280;
            int fixedHeight = 720;
            
            Log.d(TAG, "使用固定的视频分辨率配置解码器: " + fixedWidth + "x" + fixedHeight);
            
            // 使用清理后的SPS/PPS数据创建媒体格式，使用固定的视频分辨率
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, fixedWidth, fixedHeight);
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(cleanSPS));
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(cleanPPS));
            
            // 添加必要的解码参数
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, fixedWidth * fixedHeight);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30); // 设置帧率为30fps
            
            Log.d(TAG, "创建解码器格式: " + mediaFormat);
            
            // 配置解码器
            mediaCodec.configure(mediaFormat, surface, null, 0);
            Log.d(TAG, "解码器配置成功");

            // 启动解码器
            mediaCodec.start();
            decoderConfigured = true;
            Log.i(TAG, "===== 解码器启动成功，准备接收数据 =====");
            
            // 启动输出处理线程
            startOutputThread();

        } catch (Exception e) {
            Log.e(TAG, "配置MediaCodec失败: " + e.getMessage(), e);
            decoderConfigured = false;
            // 如果配置失败，释放解码器
            if (mediaCodec != null) {
                try {
                    mediaCodec.release();
                } catch (Exception ex) {
                    Log.e(TAG, "释放解码器失败: " + ex.getMessage());
                }
                mediaCodec = null;
            }
        }
    }
    
    /**
     * 移除H.264数据中的起始码（0x00000001或0x000001）
     * @param data 包含起始码的H.264数据
     * @return 移除起始码后的数据
     */
    private byte[] removeStartCode(byte[] data) {
        if (data == null || data.length < 4) {
            return data;
        }
        
        // 检查是否包含4字节起始码
        if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            byte[] cleanData = new byte[data.length - 4];
            System.arraycopy(data, 4, cleanData, 0, cleanData.length);
            return cleanData;
        }
        
        // 检查是否包含3字节起始码
        if (data[0] == 0 && data[1] == 0 && data[2] == 1) {
            byte[] cleanData = new byte[data.length - 3];
            System.arraycopy(data, 3, cleanData, 0, cleanData.length);
            return cleanData;
        }
        
        // 没有起始码，直接返回原数据
        return data;
    }

    /**
     * 接收H.264数据
     */
    private void receiveH264Data() {
        isRunning = true;
        byte[] buffer = new byte[65536]; // 增大缓冲区到64KB
        byte[] frameBuffer = new byte[0];
        frameCount = 0; // 重置帧计数

        Log.i(TAG, "===== 开始接收H.264数据 =====");
        Log.i(TAG, "isRunning: " + isRunning + ", inputStream: " + (inputStream != null ? "非空" : "空"));

        try {
            while (isRunning && inputStream != null) {
                // 读取数据
                int bytesRead = inputStream.read(buffer);
                
                if (bytesRead == -1) {
                    Log.i(TAG, "输入流已关闭，连接断开");
                    updateConnectionState(false);
                    break;
                } else if (bytesRead == 0) {
                    continue;
                }
                
                // 检查是否是心跳包
                if (isHeartbeat(buffer, bytesRead)) {
                    handleHeartbeat();
                    continue;
                }

                Log.i(TAG, "接收到数据: " + bytesRead + " 字节");
                Log.d(TAG, "数据前20字节: " + bytesToHex(buffer, Math.min(20, bytesRead)));
                
                // 更新心跳时间（收到任何数据都更新）
                lastHeartbeatTime = System.currentTimeMillis();
                
                // 追加到帧缓冲区
                byte[] newBuffer = new byte[frameBuffer.length + bytesRead];
                System.arraycopy(frameBuffer, 0, newBuffer, 0, frameBuffer.length);
                System.arraycopy(buffer, 0, newBuffer, frameBuffer.length, bytesRead);
                frameBuffer = newBuffer;
                
                Log.d(TAG, "帧缓冲区大小: " + frameBuffer.length + " 字节");

                // 处理帧缓冲区中的所有完整NAL单元
                frameBuffer = processNALUnits(frameBuffer);
            }
        } catch (IOException e) {
            Log.e(TAG, "接收数据失败: " + e.getMessage(), e);
            updateConnectionState(false);
        } catch (Exception e) {
            Log.e(TAG, "接收数据时发生未知异常: " + e.getMessage(), e);
            updateConnectionState(false);
        } finally {
            Log.i(TAG, "===== receiveH264Data方法执行结束 =====");
        }
    }
    
    /**
     * 处理帧缓冲区中的所有完整NAL单元
     * @param frameBuffer 帧缓冲区
     * @return 剩余未处理的数据
     */
    private byte[] processNALUnits(byte[] frameBuffer) {
        if (frameBuffer == null || frameBuffer.length < 5) {
            return frameBuffer;
        }
        
        int currentIndex = 0;
        
        // 找到第一个起始码
        int firstStartCode = findStartCode(frameBuffer, 0);
        if (firstStartCode == -1) {
            Log.d(TAG, "未找到起始码，保留缓冲区等待更多数据");
            // 如果缓冲区过大，丢弃前面的数据
            if (frameBuffer.length > 1024 * 1024) {
                Log.w(TAG, "缓冲区过大，清空");
                return new byte[0];
            }
            return frameBuffer;
        }
        
        // 丢弃第一个起始码之前的数据
        if (firstStartCode > 0) {
            Log.d(TAG, "丢弃起始码之前的 " + firstStartCode + " 字节");
            byte[] trimmed = new byte[frameBuffer.length - firstStartCode];
            System.arraycopy(frameBuffer, firstStartCode, trimmed, 0, trimmed.length);
            frameBuffer = trimmed;
        }
        
        currentIndex = 0;
        
        // 循环处理所有完整的NAL单元
        while (true) {
            // 找到当前NAL单元的起始码
            int nalStart = findStartCode(frameBuffer, currentIndex);
            if (nalStart == -1) {
                break;
            }
            
            // 确定起始码长度（3或4字节）
            int startCodeLen = getStartCodeLength(frameBuffer, nalStart);
            
            // 找到下一个NAL单元的起始码
            int nextNalStart = findStartCode(frameBuffer, nalStart + startCodeLen);
            
            if (nextNalStart != -1) {
                // 有完整的NAL单元，提取并处理
                int nalLength = nextNalStart - nalStart;
                byte[] nalUnit = new byte[nalLength];
                System.arraycopy(frameBuffer, nalStart, nalUnit, 0, nalLength);
                
                frameCount++;
                int nalType = getNALTypeFromBuffer(nalUnit, 0, startCodeLen);
                Log.d(TAG, "处理第 " + frameCount + " 个NAL单元: " + nalLength + " 字节, NAL类型=" + nalType);
                
                // 保存到文件
                saveNALUnit(nalUnit);
                
                // 解码NAL单元
                decodeH264Frame(nalUnit);
                
                // 移动到下一个NAL单元
                currentIndex = nextNalStart;
            } else {
                // 没有找到下一个起始码
                // 检查剩余数据的NAL类型
                int nalType = getNALTypeFromBuffer(frameBuffer, nalStart, startCodeLen);
                int remainingLength = frameBuffer.length - nalStart;
                
                // 只有 SPS(7) 和 PPS(8) 可以立即处理（它们通常很小）
                // 视频帧（IDR=5, 非IDR=1）必须等待下一个起始码来确定边界
                if (nalType == 7 || nalType == 8) {
                    // SPS/PPS 通常小于 100 字节，可以立即处理
                    byte[] nalUnit = new byte[remainingLength];
                    System.arraycopy(frameBuffer, nalStart, nalUnit, 0, remainingLength);
                    
                    frameCount++;
                    String nalTypeName = (nalType == 7) ? "SPS" : "PPS";
                    Log.i(TAG, "处理第 " + frameCount + " 个NAL单元(" + nalTypeName + "): " + remainingLength + " 字节");
                    
                    // 保存到文件
                    saveNALUnit(nalUnit);
                    
                    // 解码NAL单元
                    decodeH264Frame(nalUnit);
                    
                    // 清空缓冲区
                    return new byte[0];
                } else {
                    // 视频帧和其他NAL类型需要等待下一个起始码
                    // 保留在缓冲区中等待更多数据
                    break;
                }
            }
        }
        
        // 返回剩余未处理的数据
        if (currentIndex > 0 && currentIndex < frameBuffer.length) {
            byte[] remaining = new byte[frameBuffer.length - currentIndex];
            System.arraycopy(frameBuffer, currentIndex, remaining, 0, remaining.length);
            Log.d(TAG, "保留 " + remaining.length + " 字节待处理");
            return remaining;
        } else if (currentIndex == 0) {
            // 没有处理任何数据，返回原缓冲区
            return frameBuffer;
        } else {
            // 所有数据都已处理
            return new byte[0];
        }
    }
    
    /**
     * 从缓冲区获取NAL类型
     */
    private int getNALTypeFromBuffer(byte[] data, int offset, int startCodeLen) {
        int nalByteOffset = offset + startCodeLen;
        if (nalByteOffset < data.length) {
            return data[nalByteOffset] & 0x1F;
        }
        return -1;
    }
    
    /**
     * 获取起始码长度
     */
    private int getStartCodeLength(byte[] data, int offset) {
        if (offset + 4 <= data.length && 
            data[offset] == 0 && data[offset+1] == 0 && 
            data[offset+2] == 0 && data[offset+3] == 1) {
            return 4;
        }
        if (offset + 3 <= data.length && 
            data[offset] == 0 && data[offset+1] == 0 && data[offset+2] == 1) {
            return 3;
        }
        return 0;
    }
    
    /**
     * 保存NAL单元到文件
     */
    private void saveNALUnit(byte[] nalUnit) {
        if (h264FileOutputStream != null) {
            try {
                h264FileOutputStream.write(nalUnit);
                h264FileOutputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "保存NAL单元失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 寻找H.264起始码（0x00000001或0x000001）
     * @param data 要搜索的数据
     * @param startIndex 开始搜索的索引
     * @return 起始码的起始索引，如果没有找到则返回-1
     */
    private int findStartCode(byte[] data, int startIndex) {
        if (data == null || data.length < startIndex + 3) {
            return -1;
        }
        
        // 检查4字节起始码
        for (int i = startIndex; i < data.length - 3; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                return i;
            }
        }
        
        // 检查3字节起始码
        for (int i = startIndex; i < data.length - 2; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) {
                return i;
            }
        }
        
        return -1;
    }

    /**
     * 解码H.264帧数据
     */
    private void decodeH264Frame(byte[] data) {
        if (decodingHandler == null) {
            Log.e(TAG, "解码线程未初始化");
            return;
        }
        
        // 创建一个本地副本
        final byte[] frameData = data.clone();
        
        decodingHandler.post(() -> {
            try {
                // 获取NAL类型
                int nalType = getNALType(frameData);
                Log.d(TAG, "处理NAL单元: 类型=" + nalType + ", 大小=" + frameData.length + " 字节");
                
                // 处理SPS (NAL类型 7)
                if (nalType == 7) {
                    Log.i(TAG, "===== 收到SPS数据 =====");
                    Log.d(TAG, "SPS数据: " + bytesToHex(frameData, Math.min(50, frameData.length)));
                    cachedSPS = frameData;
                    
                    if (cachedPPS != null && surfaceReady) {
                        Log.i(TAG, "SPS+PPS都已收到，且Surface已准备好，配置解码器");
                        runOnUiThread(() -> tryConfigureDecoder());
                    }
                    return;
                }
                
                // 处理PPS (NAL类型 8)
                if (nalType == 8) {
                    Log.i(TAG, "===== 收到PPS数据 =====");
                    Log.d(TAG, "PPS数据: " + bytesToHex(frameData, Math.min(30, frameData.length)));
                    cachedPPS = frameData;
                    
                    if (cachedSPS != null && surfaceReady) {
                        Log.i(TAG, "SPS+PPS都已收到，且Surface已准备好，配置解码器");
                        runOnUiThread(() -> tryConfigureDecoder());
                    }
                    return;
                }
                
                // 处理视频帧 (IDR帧: NAL类型5, 非IDR帧: NAL类型1)
                if (nalType == 5) {
                    Log.i(TAG, "收到IDR帧(关键帧): " + frameData.length + " 字节");
                } else if (nalType == 1) {
                    Log.d(TAG, "收到非IDR帧: " + frameData.length + " 字节");
                } else {
                    Log.d(TAG, "收到其他NAL单元: 类型=" + nalType + ", 大小=" + frameData.length);
                }
                
                // 检查解码器是否就绪（使用同步锁确保状态一致）
                boolean canDecode;
                synchronized (decoderLock) {
                    canDecode = decoderConfigured && mediaCodec != null && !isConfiguringDecoder;
                }
                
                if (!canDecode) {
                    Log.w(TAG, "解码器未就绪，跳过帧 (decoderConfigured=" + decoderConfigured + 
                          ", mediaCodec=" + (mediaCodec != null) + ", isConfiguring=" + isConfiguringDecoder + ")");
                    return;
                }
                
                // 送入解码器
                submitFrameToDecoder(frameData);
                
            } catch (Exception e) {
                Log.e(TAG, "解码失败: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * 获取NAL单元类型
     */
    private int getNALType(byte[] data) {
        if (data == null || data.length < 5) {
            return -1;
        }
        
        // 检查4字节起始码
        if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return data[4] & 0x1F;
        }
        
        // 检查3字节起始码
        if (data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return data[3] & 0x1F;
        }
        
        return -1;
    }
    
    /**
     * 将帧数据提交到解码器
     */
    private void submitFrameToDecoder(byte[] frameData) {
        // 检查解码器状态
        synchronized (decoderLock) {
            if (mediaCodec == null || !decoderConfigured || isConfiguringDecoder) {
                Log.w(TAG, "解码器未就绪，跳过帧");
                return;
            }
        }
        
        try {
            // 获取可用的输入缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                // 获取输入缓冲区并写入数据
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frameData);
                    
                    // 使用基于帧计数的时间戳
                    long timestamp = frameCount * 33333; // 约30fps
                    
                    // 将数据送入解码器
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, timestamp, 0);
                    Log.i(TAG, "帧已送入解码器: " + frameData.length + " 字节");
                } else {
                    Log.e(TAG, "无法获取输入缓冲区");
                }
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.w(TAG, "输入缓冲区暂时不可用，稍后重试");
                decodingHandler.postDelayed(() -> submitFrameToDecoder(frameData), 50);
                return;
            } else {
                Log.w(TAG, "获取输入缓冲区失败: " + inputBufferIndex);
                return;
            }
            
            // 输出由独立的输出线程处理，不在此处调用drainOutputBuffer
            
        } catch (IllegalStateException e) {
            // 只记录警告，不重置解码器（除非是严重错误）
            String msg = e.getMessage();
            if (msg != null && msg.contains("stop")) {
                // 解码器正在停止，这是预期的
                Log.w(TAG, "MediaCodec正在停止: " + msg);
            } else {
                Log.e(TAG, "MediaCodec状态错误: " + msg, e);
            }
        } catch (Exception e) {
            Log.e(TAG, "提交帧到解码器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理解码器输出缓冲区
     */
    private void drainOutputBuffer() {
        if (mediaCodec == null) {
            Log.w(TAG, "drainOutputBuffer: mediaCodec为null");
            return;
        }
        
        try {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex;
            int renderedFrames = 0;
            
            // 持续处理所有可用的输出缓冲区
            while (true) {
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 50000); // 增加到50ms
                
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 暂时没有可用的输出缓冲区
                    if (renderedFrames > 0) {
                        Log.i(TAG, "本次共渲染 " + renderedFrames + " 帧");
                    }
                    break;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mediaCodec.getOutputFormat();
                    Log.i(TAG, "输出格式已更改: " + newFormat);
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    Log.i(TAG, "输出缓冲区已更改");
                    continue;
                } else if (outputBufferIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        // 渲染解码后的数据到Surface
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                        renderedFrames++;
                        Log.i(TAG, "帧已渲染到Surface, 时间戳=" + bufferInfo.presentationTimeUs + ", flags=" + bufferInfo.flags);
                    } else {
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    }
                    // 继续处理下一个输出缓冲区
                    continue;
                } else {
                    Log.w(TAG, "获取输出缓冲区返回未知值: " + outputBufferIndex);
                    break;
                }
            }
        } catch (IllegalStateException e) {
            // 不要在并发冲突时重置解码器
            Log.w(TAG, "处理输出缓冲区时MediaCodec状态警告: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "处理输出缓冲区异常: " + e.getMessage(), e);
        }
    }
    
    // 单独的输出处理线程，持续轮询解码器输出
    private Thread outputThread;
    private volatile boolean outputThreadRunning = false;
    
    private void startOutputThread() {
        if (outputThread != null && outputThread.isAlive()) {
            return;
        }
        
        outputThreadRunning = true;
        outputThread = new Thread(() -> {
            Log.i(TAG, "输出处理线程已启动");
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int pollCount = 0;
            
            while (outputThreadRunning && mediaCodec != null && decoderConfigured) {
                try {
                    int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 100000); // 100ms
                    pollCount++;
                    
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // 每10次超时打印一次日志
                        if (pollCount % 10 == 0) {
                            Log.i(TAG, "[输出线程] 等待输出中... pollCount=" + pollCount);
                        }
                        continue;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = mediaCodec.getOutputFormat();
                        Log.i(TAG, "[输出线程] 输出格式已更改: " + newFormat);
                        continue;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Log.i(TAG, "[输出线程] 输出缓冲区已更改");
                        continue;
                    } else if (outputBufferIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                            Log.i(TAG, "[输出线程] 帧已渲染到Surface, pts=" + bufferInfo.presentationTimeUs);
                        } else {
                            mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                        }
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "[输出线程] MediaCodec状态错误: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "[输出线程] 异常: " + e.getMessage());
                }
            }
            Log.i(TAG, "输出处理线程已结束");
        }, "DecoderOutputThread");
        outputThread.start();
    }
    
    private void stopOutputThread() {
        outputThreadRunning = false;
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }
    }
    
    /**
     * 重置解码器
     */
    private void resetDecoder() {
        decoderConfigured = false;
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception ex) {
                Log.e(TAG, "释放解码器失败: " + ex.getMessage());
            }
            mediaCodec = null;
        }
    }

    /**
     * 检查是否是SPS帧
     */
    private boolean isSPSFrame(byte[] data) {
        // 检查4字节起始码的情况
        if (data.length >= 5 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            int nalType = data[4] & 0x1F; // 提取NAL类型（低5位）
            return nalType == 7; // SPS的NAL类型是7
        }
        
        // 检查3字节起始码的情况
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            int nalType = data[3] & 0x1F; // 提取NAL类型（低5位）
            return nalType == 7; // SPS的NAL类型是7
        }
        
        return false;
    }
    
    /**
     * 检查是否是PPS帧
     */
    private boolean isPPSFrame(byte[] data) {
        // 检查4字节起始码的情况
        if (data.length >= 5 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            int nalType = data[4] & 0x1F; // 提取NAL类型（低5位）
            return nalType == 8; // PPS的NAL类型是8
        }
        
        // 检查3字节起始码的情况
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            int nalType = data[3] & 0x1F; // 提取NAL类型（低5位）
            return nalType == 8; // PPS的NAL类型是8
        }
        
        return false;
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     * @param data 字节数组
     * @param length 要转换的长度
     * @return 十六进制字符串
     */
    private String bytesToHex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02x ", data[i]));
        }
        return sb.toString();
    }
    
    /**
     * 从SPS数据中提取视频分辨率
     * @param sps SPS数据
     * @return 视频分辨率数组 [width, height]
     */
    private int[] getVideoResolutionFromSPS(byte[] sps) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(sps);
            
            // 跳过起始码
            int startCodeSize = (sps[0] == 0 && sps[1] == 0 && sps[2] == 0 && sps[3] == 1) ? 4 : 3;
            buffer.position(startCodeSize);
            
            // 跳过nal_unit_type、profile_idc、constraint_set_flags、level_idc
            buffer.position(buffer.position() + 4);
            
            // 解析seq_parameter_set_id（ue(v)）
            skipUEGolomb(buffer);
            
            // 解析profile_idc相关参数
            int profileIdc = sps[startCodeSize];
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || 
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 || 
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                // 跳过chroma_format_idc（ue(v)）
                skipUEGolomb(buffer);
                
                // 跳过bit_depth_luma_minus8（ue(v)）
                skipUEGolomb(buffer);
                
                // 跳过bit_depth_chroma_minus8（ue(v)）
                skipUEGolomb(buffer);
                
                // 跳过qpprime_y_zero_transform_bypass_flag（1 bit）
                buffer.position(buffer.position() + 1);
                
                // 跳过seq_scaling_matrix_present_flag（1 bit）
                if (buffer.get(buffer.position()) != 0) {
                    buffer.position(buffer.position() + 1);
                    // 跳过缩放矩阵
                    int scalingMatrixCount = 8;
                    for (int i = 0; i < scalingMatrixCount; i++) {
                        if (buffer.get(buffer.position()) != 0) {
                            buffer.position(buffer.position() + 1);
                            for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                                skipUEGolomb(buffer);
                            }
                        } else {
                            buffer.position(buffer.position() + 1);
                        }
                    }
                } else {
                    buffer.position(buffer.position() + 1);
                }
            }
            
            // 跳过log2_max_frame_num_minus4（ue(v)）
            skipUEGolomb(buffer);
            
            // 解析pic_order_cnt_type（ue(v)）
            int picOrderCntType = readUEGolomb(buffer);
            if (picOrderCntType == 0) {
                // 跳过log2_max_pic_order_cnt_lsb_minus4（ue(v)）
                skipUEGolomb(buffer);
            } else if (picOrderCntType == 1) {
                // 跳过delta_pic_order_always_zero_flag（1 bit）
                buffer.position(buffer.position() + 1);
                
                // 跳过offset_for_non_ref_pic（se(v)）
                skipSEGolomb(buffer);
                
                // 跳过offset_for_top_to_bottom_field（se(v)）
                skipSEGolomb(buffer);
                
                // 跳过num_ref_frames_in_pic_order_cnt_cycle（ue(v)）
                int numRefFrames = readUEGolomb(buffer);
                for (int i = 0; i < numRefFrames; i++) {
                    skipSEGolomb(buffer);
                }
            }
            
            // 跳过num_ref_frames（ue(v)）
            skipUEGolomb(buffer);
            
            // 跳过gaps_in_frame_num_value_allowed_flag（1 bit）
            buffer.position(buffer.position() + 1);
            
            // 解析pic_width_in_mbs_minus1（ue(v)）
            int picWidthInMbsMinus1 = readUEGolomb(buffer);
            
            // 解析pic_height_in_map_units_minus1（ue(v)）
            int picHeightInMapUnitsMinus1 = readUEGolomb(buffer);
            
            // 解析frame_mbs_only_flag（1 bit）
            boolean frameMbsOnlyFlag = (buffer.get(buffer.position()) != 0);
            buffer.position(buffer.position() + 1);
            
            // 解析mb_adaptive_frame_field_flag（1 bit）
            boolean mbAdaptiveFrameFieldFlag = false;
            if (!frameMbsOnlyFlag) {
                mbAdaptiveFrameFieldFlag = (buffer.get(buffer.position()) != 0);
                buffer.position(buffer.position() + 1);
            }
            
            // 跳过direct_8x8_inference_flag（1 bit）
            buffer.position(buffer.position() + 1);
            
            // 解析frame_cropping_flag（1 bit）
            boolean frameCroppingFlag = (buffer.get(buffer.position()) != 0);
            buffer.position(buffer.position() + 1);
            
            // 解析裁剪参数
            int frameCropLeftOffset = 0;
            int frameCropRightOffset = 0;
            int frameCropTopOffset = 0;
            int frameCropBottomOffset = 0;
            if (frameCroppingFlag) {
                frameCropLeftOffset = readUEGolomb(buffer);
                frameCropRightOffset = readUEGolomb(buffer);
                frameCropTopOffset = readUEGolomb(buffer);
                frameCropBottomOffset = readUEGolomb(buffer);
            }
            
            // 计算实际视频宽度和高度
            int macroblockSize = 16;
            int width = (picWidthInMbsMinus1 + 1) * macroblockSize;
            int height = (picHeightInMapUnitsMinus1 + 1) * macroblockSize * (frameMbsOnlyFlag ? 1 : 2);
            
            // 应用裁剪
            width -= (frameCropLeftOffset + frameCropRightOffset) * 2;
            height -= (frameCropTopOffset + frameCropBottomOffset) * 2;
            
            // 确保宽度和高度为正数
            width = Math.max(width, 0);
            height = Math.max(height, 0);
            
            return new int[]{width, height};
            
        } catch (Exception e) {
            Log.e(TAG, "解析SPS数据获取视频分辨率失败: " + e.getMessage(), e);
            // 如果解析失败，返回默认分辨率
            return new int[]{WIDTH, HEIGHT};
        }
    }
    
    /**
     * 读取并跳过一个无符号指数哥伦布编码值
     * @param buffer 包含数据的ByteBuffer
     */
    private void skipUEGolomb(ByteBuffer buffer) {
        int leadingZeroBits = 0;
        while (buffer.position() < buffer.limit() && buffer.get(buffer.position()) == 0) {
            leadingZeroBits++;
            buffer.position(buffer.position() + 1);
        }
        buffer.position(buffer.position() + leadingZeroBits + 1);
    }
    
    /**
     * 读取一个无符号指数哥伦布编码值
     * @param buffer 包含数据的ByteBuffer
     * @return 解析出的无符号整数
     */
    private int readUEGolomb(ByteBuffer buffer) {
        int leadingZeroBits = 0;
        while (buffer.position() < buffer.limit() && buffer.get(buffer.position()) == 0) {
            leadingZeroBits++;
            buffer.position(buffer.position() + 1);
        }
        
        if (buffer.position() >= buffer.limit()) {
            return 0;
        }
        
        int value = 0;
        for (int i = 0; i < leadingZeroBits + 1; i++) {
            if (buffer.position() < buffer.limit()) {
                value <<= 1;
                value |= buffer.get(buffer.position());
                buffer.position(buffer.position() + 1);
            }
        }
        
        return value - 1;
    }
    
    /**
     * 读取并跳过一个有符号指数哥伦布编码值
     * @param buffer 包含数据的ByteBuffer
     */
    private void skipSEGolomb(ByteBuffer buffer) {
        int value = readUEGolomb(buffer);
        // 无符号值转换为有符号值的计算，但我们只需要跳过，所以不需要实际使用
    }

    /**
     * 提取SPS数据
     */
    private byte[] extractSPS(byte[] data) {
        int start = 0;
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            start = 4;
        } else if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            start = 3;
        }
        
        for (int i = start + 1; i < data.length - 3; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                byte[] sps = new byte[i - start];
                System.arraycopy(data, start, sps, 0, sps.length);
                return sps;
            }
        }
        return null;
    }

    /**
     * 提取PPS数据
     */
    private byte[] extractPPS(byte[] data) {
        int start = 0;
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            start = 4;
        } else if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            start = 3;
        }
        
        // 找到SPS的结束位置
        int spsEnd = start;
        for (int i = start + 1; i < data.length - 3; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                spsEnd = i;
                break;
            }
        }
        
        // 找到PPS的结束位置
        for (int i = spsEnd + 4; i < data.length - 3; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                byte[] pps = new byte[i - (spsEnd + 4)];
                System.arraycopy(data, spsEnd + 4, pps, 0, pps.length);
                return pps;
            }
        }
        
        // 如果没有找到后续帧，PPS就是剩余的数据
        if (spsEnd + 4 < data.length) {
            byte[] pps = new byte[data.length - (spsEnd + 4)];
            System.arraycopy(data, spsEnd + 4, pps, 0, pps.length);
            return pps;
        }
        
        return null;
    }

    /**
     * 发送触摸事件到客户端
     */
    // 服务端 VirtualDisplay 的分辨率
    private static final int SERVER_VIDEO_WIDTH = 1280;
    private static final int SERVER_VIDEO_HEIGHT = 720;
    
    // SurfaceView 的实际像素尺寸（在 surfaceChanged 时更新）
    private int surfaceViewWidth = 0;
    private int surfaceViewHeight = 0;
    
    private void sendTouchEvent(MotionEvent event) {
        // 添加详细的状态检查日志
        Log.i(TAG, "sendTouchEvent: outputStream=" + (outputStream != null) + 
              ", clientSocket=" + (clientSocket != null) + 
              ", isConnected=" + (clientSocket != null && clientSocket.isConnected()));
        
        if (outputStream == null || clientSocket == null || !clientSocket.isConnected()) {
            Log.e(TAG, "Socket未连接，无法发送触摸事件");
            return;
        }
        
        // 获取触摸坐标
        float touchX = event.getX();
        float touchY = event.getY();
        int action = event.getAction();
        
        // 坐标转换：将 SurfaceView 坐标转换为服务端 VirtualDisplay 坐标
        int serverX, serverY;
        if (surfaceViewWidth > 0 && surfaceViewHeight > 0) {
            // 按比例转换坐标
            serverX = (int) (touchX * SERVER_VIDEO_WIDTH / surfaceViewWidth);
            serverY = (int) (touchY * SERVER_VIDEO_HEIGHT / surfaceViewHeight);
            
            // 确保坐标在有效范围内
            serverX = Math.max(0, Math.min(serverX, SERVER_VIDEO_WIDTH - 1));
            serverY = Math.max(0, Math.min(serverY, SERVER_VIDEO_HEIGHT - 1));
        } else {
            // 如果 SurfaceView 尺寸未知，直接使用原始坐标
            serverX = (int) touchX;
            serverY = (int) touchY;
        }

        final int finalX = serverX;
        final int finalY = serverY;
        
        if (touchEventExecutor == null || touchEventExecutor.isShutdown()) {
            Log.e(TAG, "touchEventExecutor未初始化或已关闭，无法发送触摸事件");
            return;
        }
        
        touchEventExecutor.execute(() -> {
            Log.i(TAG, "开始发送触摸事件数据...");
            try {
                // 构建触摸事件数据：10字节
                // [0]: 'T' 事件类型标识
                // [1]: action (触摸动作)
                // [2-5]: x 坐标 (big-endian)
                // [6-9]: y 坐标 (big-endian)
                byte[] touchData = new byte[10];
                touchData[0] = (byte) 'T';
                touchData[1] = (byte) (action & 0xFF);
                touchData[2] = (byte) (finalX >> 24 & 0xFF);
                touchData[3] = (byte) (finalX >> 16 & 0xFF);
                touchData[4] = (byte) (finalX >> 8 & 0xFF);
                touchData[5] = (byte) (finalX & 0xFF);
                touchData[6] = (byte) (finalY >> 24 & 0xFF);
                touchData[7] = (byte) (finalY >> 16 & 0xFF);
                touchData[8] = (byte) (finalY >> 8 & 0xFF);
                touchData[9] = (byte) (finalY & 0xFF);

                outputStream.write(touchData);
                outputStream.flush();

                Log.i(TAG, "发送触摸事件: action=" + action + 
                      ", 原始坐标=(" + (int)touchX + "," + (int)touchY + ")" +
                      ", 转换后=(" + finalX + "," + finalY + ")");

            } catch (IOException e) {
                Log.e(TAG, "发送触摸事件失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "===== Surface已创建 =====");
        surfaceHolder = holder;
        surfaceReady = true;
        
        // 如果已经有缓存的SPS/PPS数据，尝试配置解码器
        if (cachedSPS != null && cachedPPS != null) {
            Log.i(TAG, "Surface创建完成，发现缓存的SPS/PPS，准备配置解码器");
            tryConfigureDecoder();
        } else {
            Log.i(TAG, "Surface创建完成，等待SPS/PPS数据");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "===== Surface已更改: " + width + "x" + height + " =====");
        surfaceHolder = holder;
        surfaceReady = true;
        
        // 保存 SurfaceView 的实际像素尺寸，用于触摸坐标转换
        surfaceViewWidth = width;
        surfaceViewHeight = height;
        Log.i(TAG, "SurfaceView 实际尺寸已更新: " + surfaceViewWidth + "x" + surfaceViewHeight);
        
        // Surface尺寸变化，可能需要重新配置解码器
        // 但为了避免重复配置，只在必要时重新配置
        if (!decoderConfigured && cachedSPS != null && cachedPPS != null) {
            Log.i(TAG, "Surface尺寸变化，尝试配置解码器");
            tryConfigureDecoder();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "===== Surface已销毁 =====");
        surfaceReady = false;
        decoderConfigured = false;
        
        // 停止解码器但不释放所有资源
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
                Log.i(TAG, "解码器已停止并释放");
            } catch (Exception e) {
                Log.e(TAG, "停止解码器失败: " + e.getMessage());
            }
            mediaCodec = null;
        }
    }
    
    /**
     * 尝试配置解码器
     */
    // 解码器配置锁，防止竞态条件
    private final Object decoderLock = new Object();
    private volatile boolean isConfiguringDecoder = false;
    
    private void tryConfigureDecoder() {
        synchronized (decoderLock) {
            // 如果解码器已经配置成功，不要重复配置
            if (decoderConfigured && mediaCodec != null) {
                Log.d(TAG, "解码器已配置，跳过重复配置");
                return;
            }
            
            // 如果正在配置中，跳过
            if (isConfiguringDecoder) {
                Log.d(TAG, "解码器正在配置中，跳过");
                return;
            }
            
            if (!surfaceReady) {
                Log.w(TAG, "Surface未准备好，无法配置解码器");
                return;
            }
            
            if (cachedSPS == null || cachedPPS == null) {
                Log.w(TAG, "SPS或PPS为空，无法配置解码器");
                return;
            }
            
            isConfiguringDecoder = true;
            
            // 如果解码器已存在，先释放
            if (mediaCodec != null) {
                try {
                    mediaCodec.stop();
                    mediaCodec.release();
                    Log.d(TAG, "释放旧解码器");
                } catch (Exception e) {
                    Log.e(TAG, "释放旧解码器失败: " + e.getMessage());
                }
                mediaCodec = null;
                decoderConfigured = false;
            }
            
            try {
                mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                Log.i(TAG, "创建新的解码器实例");
                configureMediaCodec(cachedSPS, cachedPPS);
            } catch (IOException e) {
                Log.e(TAG, "创建解码器失败: " + e.getMessage(), e);
            } finally {
                isConfiguringDecoder = false;
            }
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Log.i(TAG, "onTouch 被调用: action=" + event.getAction() + ", x=" + event.getX() + ", y=" + event.getY());
        sendTouchEvent(event);
        return true;
    }

    /**
     * 释放资源
     */
    private void releaseResources() {
        Log.i(TAG, "===== 释放资源 =====");
        isRunning = false;
        decoderConfigured = false;
        
        // 停止输出处理线程
        stopOutputThread();

        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (clientSocket != null) {
                clientSocket.close();
                clientSocket = null;
            }
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
            // 关闭H.264文件输出流
            if (h264FileOutputStream != null) {
                h264FileOutputStream.close();
                h264FileOutputStream = null;
                Log.i(TAG, "H.264文件输出流已关闭");
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭资源失败: " + e.getMessage(), e);
        }

        try {
            if (mediaCodec != null) {
                mediaCodec.stop();
                mediaCodec.release();
                mediaCodec = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放MediaCodec失败: " + e.getMessage(), e);
        }

        if (decodingThread != null) {
            decodingThread.quitSafely();
            decodingThread = null;
            decodingHandler = null;
        }
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
        if (touchEventExecutor != null) {
            touchEventExecutor.shutdown();
            touchEventExecutor = null;
        }
        
        // 清除缓存
        cachedSPS = null;
        cachedPPS = null;
        
        // 停止心跳检测
        stopHeartbeatCheck();
        
        // 重置连接状态
        isClientConnected = false;
        
        Log.i(TAG, "===== 资源释放完成 =====");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "Activity已暂停");
        // 保持WakeLock，防止系统休眠导致网络中断
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L); // 最多10分钟，防止无限持有
            Log.i(TAG, "WakeLock已获取");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "Activity已停止");
        // 保持WakeLock，防止系统休眠导致网络中断
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L); // 最多10分钟，防止无限持有
            Log.i(TAG, "WakeLock已获取");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "Activity已恢复");
        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock已释放");
        }
        
        // 恢复时检查连接状态并更新UI
        if (isClientConnected && clientSocket != null && clientSocket.isConnected()) {
            updateStatus(getString(R.string.status_connected));
        } else if (clientSocket == null || !clientSocket.isConnected()) {
            isClientConnected = false;
            updateStatus(getString(R.string.status_listening));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Activity已销毁");
        // 停止心跳检测
        stopHeartbeatCheck();
        
        // 释放所有资源
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        releaseResources();
    }
}
