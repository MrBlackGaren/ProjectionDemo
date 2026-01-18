package com.projection.screen.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 屏幕捕获与编码活动类
 * <p>
 * 该类演示了如何使用Android的MediaCodec API将虚拟屏内容编码为H.264格式
 * 并模拟将编码后的数据通过网络发送到外部设备（如车机）的过程。
 * 核心技术包括：VirtualDisplay虚拟屏创建、MediaCodec视频编码、H.264数据流处理。
 */
public class ScreenCaptureActivity extends AppCompatActivity {
    /** 日志标签，用于调试和问题追踪 */
    private static final String TAG = "ScreenCaptureActivity";
    
    /** MediaCodec编码器实例，用于将虚拟屏内容编码为H.264格式 */
    private MediaCodec mCodec;
    
    /** VirtualDisplay实例，代表一个虚拟显示设备，用于捕获屏幕内容 */
    private VirtualDisplay mVirtualDisplay;
    
    /** MediaProjection实例，用于获取屏幕录制权限（需要在外部请求） */
    private MediaProjection mMediaProjection; 
    
    /** Surface实例，作为编码器的输入目标，虚拟屏内容将渲染到这个Surface上 */
    private Surface mInputSurface;

    // 参数配置
    /** 虚拟屏和编码输出的宽度（像素） */
    private int WIDTH = 1280;
    
    /** 虚拟屏和编码输出的高度（像素） */
    private int HEIGHT = 720;
    
    /** 视频编码的比特率（2Mbps），影响视频质量和文件大小 */
    private int BIT_RATE = 2000000; 
    
    // Socket相关配置
    /** 车机APP的IP地址 */
    private String carDeviceIp; 
    
    /** 车机APP监听的端口 */
    private static final int CAR_DEVICE_PORT = 8888; 
    
    /** Socket连接实例 */
    private Socket mSocket;
    
    /** Socket输出流，用于发送H.264数据 */
    private OutputStream mOutputStream;
    
    /** 线程池，用于处理Socket连接和数据发送 */
    private ExecutorService mExecutorService;
    
    /** 触摸事件接收器，用于接收车机APP发送的触摸事件 */
    private TouchEventReceiver mTouchEventReceiver;
    
    /** DemoPresentation实例，用于在虚拟屏上显示内容 */
    private DemoPresentation mDemoPresentation;
    
    /** 缓存的SPS数据，用于新连接建立时重新发送 */
    private byte[] cachedSPSWithStartCode;
    
    /** 缓存的PPS数据，用于新连接建立时重新发送 */
    private byte[] cachedPPSWithStartCode;
    
    // UI组件
    private Button mScanButton;
    private Button mDirectConnectButton;
    private Button mDisconnectButton;
    private TextView mCurrentIp;
    private TextView mStatusText;
    private ListView mDeviceList;
    
    // 设备列表和适配器
    private ArrayAdapter<String> mDeviceListAdapter;
    private List<String> mScannedDevices;
    private boolean isScanning;
    
    // 连接状态
    private volatile boolean isConnected = false;
    
    // 心跳机制
    private Handler mHeartbeatHandler;
    private static final int HEARTBEAT_INTERVAL = 3000; // 3秒发送一次心跳
    private static final byte[] HEARTBEAT_DATA = new byte[]{'H', 'B'}; // 心跳数据包
    private Handler handler;

    /**
     * 获取当前设备的本地IP地址
     * 
     * @return 本地IP地址字符串（如192.168.1.100），如果获取失败则返回null
     */
    private String getLocalIpAddress() {
        try {
            Log.d(TAG, "开始获取本地IP地址");
            
            // 检查网络连接状态
            ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connManager == null) {
                Log.e(TAG, "无法获取ConnectivityManager");
                return null;
            }
            
            NetworkInfo activeNetwork = connManager.getActiveNetworkInfo();
            if (activeNetwork == null || !activeNetwork.isConnected()) {
                Log.e(TAG, "当前没有活动的网络连接");
                return null;
            }
            
            Log.d(TAG, "当前网络类型: " + activeNetwork.getTypeName() + ", 连接状态: " + activeNetwork.isConnected());
            
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            // 检查networkInterfaces是否为null，避免NullPointerException
            if (networkInterfaces != null) {
                while (networkInterfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = networkInterfaces.nextElement();
                    
                    // 跳过虚拟网络接口和禁用的接口
                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                        Log.d(TAG, "跳过网络接口: " + networkInterface.getDisplayName() + " (回环或已禁用)");
                        continue;
                    }
                    
                    Log.d(TAG, "正在检查网络接口: " + networkInterface.getDisplayName());
                    
                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        String ipAddress = inetAddress.getHostAddress();
                        
                        Log.d(TAG, "检查IP地址: " + ipAddress + ", 回环地址: " + inetAddress.isLoopbackAddress());
                        
                        // 跳过回环地址和IPv6地址
                        if (!inetAddress.isLoopbackAddress() && ipAddress.indexOf(':') == -1) {
                            Log.i(TAG, "找到本地IP地址: " + ipAddress + " (接口: " + networkInterface.getDisplayName() + ")");
                            return ipAddress;
                        }
                    }
                }
                Log.e(TAG, "遍历了所有网络接口，但没有找到有效的IPv4地址");
            } else {
                Log.e(TAG, "NetworkInterface.getNetworkInterfaces()返回null");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "获取本地IP地址时权限不足: " + e.getMessage(), e);
            Toast.makeText(this, "需要网络权限才能扫描设备", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "获取本地IP地址失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 检查指定IP地址的端口是否开放
     * 
     * @param ipAddress IP地址字符串
     * @param port 端口号
     * @return 如果端口开放则返回true，否则返回false
     */
    private boolean checkPort(String ipAddress, int port) {
        try (Socket socket = new Socket()) {
            // 创建SocketAddress对象，指定IP地址和端口
            SocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName(ipAddress), port);
            socket.connect(socketAddress, 500); // 500ms超时
            return true;
        } catch (IOException e) {
            // 连接失败，端口可能未开放
            return false;
        }
    }

    /**
     * 扫描局域网，寻找运行车机APP的设备
     * <p>
     * 该方法会：
     * 1. 获取当前设备的本地IP地址
     * 2. 构建局域网子网范围（如192.168.1.0/24）
     * 3. 扫描子网内所有IP地址的指定端口
     * 4. 对特定IP地址（192.168.3.74）进行额外检查
     * 5. 返回所有开放指定端口的IP地址列表
     * 
     * @return 找到的车机设备IP地址列表，如果未找到或扫描超时则返回空列表
     */
    private java.util.List<String> scanLocalNetwork() {
        // 使用专门的线程池进行扫描，支持超时控制
        ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
        
        try {
            // 提交扫描任务到线程池
            java.util.concurrent.Future<java.util.List<String>> future = scanExecutor.submit(() -> {
                java.util.List<String> foundDevices = new java.util.ArrayList<>();
                String localIp = getLocalIpAddress();
                if (localIp == null) {
                    Log.e(TAG, "无法获取本地IP地址，扫描失败");
                    // 即使无法获取本地IP，也尝试直接检查指定IP
                    checkSpecificIp(foundDevices);
                    return foundDevices;
                }

                Log.i(TAG, "本地IP地址: " + localIp);

                // 构建子网前缀（如192.168.1.）
                String subnetPrefix = localIp.substring(0, localIp.lastIndexOf('.') + 1);
                Log.i(TAG, "子网前缀: " + subnetPrefix);
                
                // 检查当前子网是否与目标IP在同一网段
                if (!subnetPrefix.equals("192.168.3.")) {
                    Log.i(TAG, "当前子网与目标IP 192.168.3.74 不在同一网段，将进行额外检查");
                    checkSpecificIp(foundDevices);
                }

                // 扫描子网内的所有IP地址（1-254）
                int scannedCount = 0;
                for (int i = 1; i <= 254; i++) {
                    if (!isScanning) {
                        // 如果扫描被取消，立即返回
                        Log.i(TAG, "扫描已取消");
                        return foundDevices;
                    }
                    
                    String ipAddress = subnetPrefix + i;
                    
                    // 跳过本地IP地址
                    if (ipAddress.equals(localIp)) {
                        continue;
                    }

                    scannedCount++;
                    Log.d(TAG, "正在扫描IP: " + ipAddress + " (" + scannedCount + "/254)");
                    
                    // 检查指定端口是否开放
                    if (checkPort(ipAddress, CAR_DEVICE_PORT)) {
                        Log.i(TAG, "找到车机设备: " + ipAddress);
                        foundDevices.add(ipAddress);
                    }
                }

                if (foundDevices.isEmpty()) {
                    Log.e(TAG, "未找到运行车机APP的设备");
                    // 再次尝试检查特定IP
                    checkSpecificIp(foundDevices);
                } else {
                    Log.i(TAG, "共找到 " + foundDevices.size() + " 个车机设备");
                }
                
                return foundDevices;
            });
            
            // 设置60秒超时
            return future.get(60, TimeUnit.SECONDS);
            
        } catch (java.util.concurrent.TimeoutException e) {
            Log.e(TAG, "扫描超时（60秒）");
            // 超时后也尝试检查特定IP
            java.util.List<String> foundDevices = new java.util.ArrayList<>();
            checkSpecificIp(foundDevices);
            return foundDevices;
        } catch (Exception e) {
            Log.e(TAG, "扫描过程中发生错误: " + e.getMessage(), e);
            // 发生错误时也尝试检查特定IP
            java.util.List<String> foundDevices = new java.util.ArrayList<>();
            checkSpecificIp(foundDevices);
            return foundDevices;
        } finally {
            // 关闭扫描线程池
            scanExecutor.shutdownNow();
        }
    }
    
    /**
     * 检查特定IP地址（192.168.3.74）是否可用
     * 
     * @param foundDevices 已找到的设备列表，用于添加检查通过的IP
     */
    private void checkSpecificIp(java.util.List<String> foundDevices) {
        String specificIp = "192.168.3.74";
        Log.i(TAG, "正在检查特定IP地址: " + specificIp);
        
        try {
            if (checkPort(specificIp, CAR_DEVICE_PORT)) {
                Log.i(TAG, "特定IP地址 " + specificIp + " 的8888端口开放");
                if (!foundDevices.contains(specificIp)) {
                    foundDevices.add(specificIp);
                }
            } else {
                Log.i(TAG, "特定IP地址 " + specificIp + " 的8888端口未开放");
            }
        } catch (Exception e) {
            Log.e(TAG, "检查特定IP地址 " + specificIp + " 时发生错误: " + e.getMessage(), e);
        }
    }


    
    /**
     * 配置MediaCodec编码器和创建VirtualDisplay
     * <p>
     * 该方法在成功连接到车机后调用，执行以下操作：
     * 1. 配置MediaCodec编码器，设置编码参数
     * 2. 创建VirtualDisplay虚拟屏，绑定到编码器的输入Surface
     * 3. 设置MediaCodec回调，处理编码输出数据
     * 
     * @throws IOException 如果MediaCodec创建失败
     */
    private void configureMediaCodecAndCreateVirtualDisplay() throws IOException {
        Log.i(TAG, "configureMediaCodecAndCreateVirtualDisplay: ");
        // 1. 配置 MediaCodec 编码器
        // 创建视频格式：H.264编码，指定宽度、高度
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        
        // 设置颜色格式为Surface输入模式，这是编码虚拟屏内容的最佳选择
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        // 设置编码比特率
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        
        // 设置帧率为30fps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        
        // 设置关键帧间隔为1秒（30帧）
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        // 创建H.264编码器实例
        mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        
        // 配置编码器为编码模式（CONFIGURE_FLAG_ENCODE）
        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        
        // 重要：获取编码器的输入Surface
        // 虚拟屏内容将渲染到这个Surface上，编码器会自动处理输入数据
        mInputSurface = mCodec.createInputSurface();
        
        // 设置编码回调，处理编码后的数据 - 必须在start()之前调用
        mCodec.setCallback(new MediaCodec.Callback() {
            /**
             * 处理编码器错误
             * @param codec 发生错误的编码器实例
             * @param e 错误信息
             */
            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "编码器错误: " + e.getMessage(), e);
            }

            /**
             * 当输入缓冲区可用时调用（在Surface输入模式下，此回调通常不会被触发）
             * @param codec 编码器实例
             * @param index 可用的输入缓冲区索引
             */
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                // Surface输入模式下，无需手动处理输入缓冲区
            }

            /**
             * 当编码输出缓冲区可用时调用
             * @param codec 编码器实例
             * @param index 可用的输出缓冲区索引
             * @param info 输出缓冲区的信息（包含数据大小、时间戳等）
             */
            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                // 获取输出缓冲区
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                
                if (outputBuffer == null) {
                    Log.e(TAG, "输出缓冲区为空，无法获取编码数据");
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                
                Log.d(TAG, "编码完成一帧数据: 大小=" + info.size + " 字节, 时间戳=" + info.presentationTimeUs + "us, 标志=" + info.flags);
                
                // 使用MediaMuxer将数据写入MP4文件
                if (mIsMuxerStarted && mVideoTrackIndex != -1 && mMediaMuxer != null) {
                    // 确保ByteBuffer的position和limit正确
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    
                    // 将数据写入MP4文件
                    mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, info);
                }
                
                // 将编码后的数据复制到字节数组中
                // 【关键点】这里的 byte[] 就是编码后的H.264视频数据
                // 在实际应用中，这些数据可以通过USB/Wi-Fi等方式传输到外部设备（如车机）
                byte[] h264Data = new byte[info.size];
                // 重新设置position以便读取数据
                outputBuffer.position(info.offset);
                outputBuffer.get(h264Data);
                
                // 发送H.264数据到车机
                sendH264DataToCar(h264Data);
                
                // 释放输出缓冲区，以便编码器可以继续使用它
                codec.releaseOutputBuffer(index, false);
            }

            /**
             * 当输出格式改变时调用（通常在编码开始时调用一次）
             * @param codec 编码器实例
             * @param format 新的输出格式
             */
            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.i(TAG, "输出格式已更改: " + format);
                
                // 输出格式改变时，发送SPS/PPS等关键信息
                ByteBuffer sps = format.getByteBuffer("csd-0");
                ByteBuffer pps = format.getByteBuffer("csd-1");
                
                if (sps != null && pps != null) {
                    byte[] spsData = new byte[sps.remaining()];
                    sps.get(spsData);
                    
                    // 重置SPS缓冲区位置，确保MediaMuxer能正确读取
                    sps.position(0);
                    
                    byte[] ppsData = new byte[pps.remaining()];
                    pps.get(ppsData);
                    
                    // 重置PPS缓冲区位置，确保MediaMuxer能正确读取
                    pps.position(0);
                    
                    Log.i(TAG, "SPS原始数据: 大小=" + spsData.length + " 字节, 前8字节=" + bytesToHex(spsData, Math.min(8, spsData.length)));
                    Log.i(TAG, "PPS原始数据: 大小=" + ppsData.length + " 字节, 前8字节=" + bytesToHex(ppsData, Math.min(8, ppsData.length)));
                    
                    // 检查SPS数据是否已包含起始码，如果没有则添加
                    byte[] spsWithStartCode;
                    if (hasStartCode(spsData)) {
                        Log.i(TAG, "SPS数据已包含起始码，直接使用");
                        spsWithStartCode = spsData;
                    } else {
                        Log.i(TAG, "为SPS数据添加起始码");
                        spsWithStartCode = new byte[4 + spsData.length];
                        System.arraycopy(new byte[]{0x00, 0x00, 0x00, 0x01}, 0, spsWithStartCode, 0, 4);
                        System.arraycopy(spsData, 0, spsWithStartCode, 4, spsData.length);
                    }
                    
                    // 检查PPS数据是否已包含起始码，如果没有则添加
                    byte[] ppsWithStartCode;
                    if (hasStartCode(ppsData)) {
                        Log.i(TAG, "PPS数据已包含起始码，直接使用");
                        ppsWithStartCode = ppsData;
                    } else {
                        Log.i(TAG, "为PPS数据添加起始码");
                        ppsWithStartCode = new byte[4 + ppsData.length];
                        System.arraycopy(new byte[]{0x00, 0x00, 0x00, 0x01}, 0, ppsWithStartCode, 0, 4);
                        System.arraycopy(ppsData, 0, ppsWithStartCode, 4, ppsData.length);
                    }
                    
                    Log.i(TAG, "最终SPS数据: 大小=" + spsWithStartCode.length + " 字节");
                    Log.i(TAG, "最终PPS数据: 大小=" + ppsWithStartCode.length + " 字节");
                    
                    // 缓存SPS/PPS数据，用于新连接建立时重新发送
                    cachedSPSWithStartCode = spsWithStartCode;
                    cachedPPSWithStartCode = ppsWithStartCode;
                    Log.i(TAG, "SPS/PPS数据已缓存");
                    
                    sendH264DataToCar(spsWithStartCode);
                    sendH264DataToCar(ppsWithStartCode);
                }
                
                // 使用MediaMuxer时，在输出格式改变时添加视频轨道
                if (mMediaMuxer != null) {
                    mVideoTrackIndex = mMediaMuxer.addTrack(format);
                    // 开始混合（必须在添加所有轨道后调用）
                    mMediaMuxer.start();
                    mIsMuxerStarted = true;
                    Log.i(TAG, "MediaMuxer已启动，视频轨道索引: " + mVideoTrackIndex);
                }
            }
        });
        
        // 启动编码器
        mCodec.start();

        // 获取当前手机屏幕的指标（用于参考，实际使用手动指定的密度）
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
//        Log.d(TAG, "startProjection: 当前屏幕密度=" + metrics.density);

        // 使用标准密度（160dpi），确保虚拟屏内容在不同设备上有一致的显示效果
        int density = DisplayMetrics.DENSITY_DEFAULT;
        Log.d(TAG, "startProjection: 屏幕密度=" + density);

        // 2. 创建 Virtual Display
        // 获取DisplayManager系统服务，用于创建和管理虚拟显示
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        
        // 检查displayManager是否为null
        if (displayManager == null) {
            Log.e(TAG, "DisplayManager获取失败，无法创建虚拟显示");
            throw new IOException("DisplayManager获取失败");
        }
        
        // 检查mInputSurface是否为null
        if (mInputSurface == null) {
            Log.e(TAG, "输入Surface获取失败，无法创建虚拟显示");
            throw new IOException("输入Surface获取失败");
        }
        
        try {
            // 创建虚拟显示
            // 使用与ProjectionForegroundService相同的标志组合，确保虚拟屏能正确渲染
            mVirtualDisplay = displayManager.createVirtualDisplay(
                    "CarScreen",  // 虚拟屏名称，用于调试
                    WIDTH, HEIGHT, density,  // 虚拟屏的宽、高、密度
                    mInputSurface,  // 虚拟屏内容的渲染目标Surface
                    // 使用合适的标志组合，确保虚拟屏内容能正确渲染
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | 
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            );
            
            if (mVirtualDisplay == null) {
                Log.e(TAG, "虚拟屏创建失败，返回null");
                throw new IOException("虚拟屏创建失败");
            }
            
            Log.i(TAG, "虚拟屏创建成功: 宽=" + WIDTH + " 高=" + HEIGHT + " 密度=" + density + " 名称=CarScreen");
        } catch (SecurityException e) {
            Log.e(TAG, "创建虚拟屏时权限不足: " + e.getMessage(), e);
            throw new IOException("创建虚拟屏时权限不足", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "创建虚拟屏参数错误: " + e.getMessage(), e);
            throw new IOException("创建虚拟屏参数错误", e);
        } catch (Exception e) {
            Log.e(TAG, "创建虚拟屏时发生未知错误: " + e.getMessage(), e);
            throw new IOException("创建虚拟屏时发生未知错误", e);
        }
        
        // 3. 在虚拟屏上显示DemoPresentation - 需要在主线程中执行
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
                Log.i(TAG, "找到 " + displays.length + " 个演示显示设备");
                for (Display display : displays) {
                    Log.i(TAG, "演示设备: ID=" + display.getDisplayId() + " 宽=" + display.getWidth() + " 高=" + display.getHeight());
                    // 不严格匹配宽高，只要是我们创建的虚拟显示屏就使用
                    if (display.getName().equals("CarScreen")) {
                        Log.d(TAG, "找到名称匹配的虚拟显示屏: CarScreen");
                        // 找到我们创建的虚拟显示屏
                        try {
                            mDemoPresentation = new DemoPresentation(ScreenCaptureActivity.this, display);
                            mDemoPresentation.show();
                            Log.i(TAG, "在虚拟屏(ID=" + display.getDisplayId() + ")上显示DemoPresentation成功");
                            break;
                        } catch (Exception e) {
                            Log.e(TAG, "创建或显示DemoPresentation失败: " + e.getMessage(), e);
                        }
                    }
                }
                
                if (mDemoPresentation == null) {
                    Log.e(TAG, "未找到匹配的虚拟显示屏来显示DemoPresentation");
                    // 如果没有找到匹配的显示屏，尝试使用第一个显示屏
                    if (displays.length > 0) {
                        Log.i(TAG, "尝试使用第一个显示屏: ID=" + displays[0].getDisplayId());
                        try {
                            mDemoPresentation = new DemoPresentation(ScreenCaptureActivity.this, displays[0]);
                            mDemoPresentation.show();
                            Log.i(TAG, "在显示屏(ID=" + displays[0].getDisplayId() + ")上显示DemoPresentation成功");
                        } catch (Exception e) {
                            Log.e(TAG, "创建或显示DemoPresentation失败: " + e.getMessage(), e);
                        }
                    }
                } else {
                    Log.i(TAG, "DemoPresentation状态: isShowing=" + mDemoPresentation.isShowing());
                }
            }
        });


    }
    
    /**
     * 连接到车机设备
     * <p>
     * 在单独的线程中建立Socket连接，避免阻塞主线程
     * 
     * @param ipAddress 车机设备的IP地址
     */
    private void connectToCarDevice(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            Log.e(TAG, "无效的IP地址，无法连接车机");
            runOnUiThread(() -> {
                mStatusText.setText("连接失败: 无效的IP地址");
                updateConnectionState(false);
            });
            return;
        }
        
        mExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "正在连接到车机: " + ipAddress + ":" + CAR_DEVICE_PORT);
                    mSocket = new Socket(ipAddress, CAR_DEVICE_PORT);
                    mOutputStream = mSocket.getOutputStream();
                    Log.i(TAG, "成功连接到车机");
                    
                    // 更新连接状态
                    updateConnectionState(true);
                    
                    // 连接成功后更新UI
                    runOnUiThread(() -> {
                        Toast.makeText(ScreenCaptureActivity.this, "连接成功，正在启动投屏", Toast.LENGTH_SHORT).show();
                    });
                    
                    // 初始化触摸事件接收器
                    mTouchEventReceiver = new TouchEventReceiver(mSocket, new TouchEventReceiver.TouchEventListener() {
                        @Override
                        public void onTouchEvent(MotionEvent event) {
                            // 处理来自车机的触摸事件
                            handleCarTouchEvent(event);
                        }
                    });
                    
                    // 启动触摸事件接收
                    mTouchEventReceiver.start();
                    
                    // 初始化H.264文件记录
                    initH264Recording();
                    
                    // 注意：不要发送测试数据，因为它包含的SPS/PPS与实际编码器生成的不一致
                    // 这会导致客户端解码失败
                    // sendTestH264Data();
                    
                    // 连接成功后，配置MediaCodec编码器和创建VirtualDisplay
                    // 这会确保客户端能够收到完整的SPS/PPS和I帧
                    try {
                        if (mCodec != null) {
                            // 如果编码器已存在，先停止并释放
                            mCodec.stop();
                            mCodec.release();
                            mCodec = null;
                            Log.i(TAG, "旧编码器已停止并释放");
                        }
                        
                        // 释放旧的VirtualDisplay
                        if (mVirtualDisplay != null) {
                            mVirtualDisplay.release();
                            mVirtualDisplay = null;
                            Log.i(TAG, "旧虚拟屏已释放");
                        }
                        
                        // 先发送缓存的SPS/PPS（如果有）
                        if (cachedSPSWithStartCode != null && cachedPPSWithStartCode != null) {
                            Log.i(TAG, "发送缓存的SPS/PPS数据到新连接的客户端");
                            sendH264DataToCar(cachedSPSWithStartCode);
                            sendH264DataToCar(cachedPPSWithStartCode);
                        }
                        
                        configureMediaCodecAndCreateVirtualDisplay();
                        Log.i(TAG, "编码器已配置完成，将发送SPS/PPS和I帧");
                        
                        // 启动心跳机制
                        runOnUiThread(() -> startHeartbeat());
                        
                    } catch (IOException e) {
                        Log.e(TAG, "配置编码器或创建虚拟屏失败: " + e.getMessage(), e);
                        updateConnectionState(false);
                        runOnUiThread(() -> {
                            mStatusText.setText("投屏失败: " + e.getMessage());
                        });
                    }
                } catch (IOException e) {
                    Log.e(TAG, "连接车机失败: " + e.getMessage(), e);
                    updateConnectionState(false);
                    runOnUiThread(() -> {
                        mStatusText.setText("连接失败: " + e.getMessage());
                        Toast.makeText(ScreenCaptureActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }
    
    private File h264File;
    private FileOutputStream h264FileOutputStream;
    private boolean isRecording = true;
    
    // MediaMuxer相关变量，用于将H.264封装为MP4格式
    private MediaMuxer mMediaMuxer;
    private int mVideoTrackIndex = -1;
    private boolean mIsMuxerStarted = false;
    
    /**
     * 初始化H.264文件记录和MP4封装
     */
    private void initH264Recording() {
        try {
            // 创建保存文件的目录
            File directory = getExternalFilesDir(null);
            if (directory == null) {
                Log.e(TAG, "无法获取外部存储目录");
                return;
            }
            
            // 初始化H.264原始文件保存
            h264File = new File(directory, "screen_capture_" + System.currentTimeMillis() + ".h264");
            h264FileOutputStream = new FileOutputStream(h264File);
            
            // 初始化MediaMuxer，用于将H.264封装为MP4格式
            File mp4File = new File(directory, "screen_capture_" + System.currentTimeMillis() + ".mp4");
            mMediaMuxer = new MediaMuxer(mp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            isRecording = true;
            Log.i(TAG, "H.264文件记录已初始化，保存路径: " + h264File.getAbsolutePath());
            Log.i(TAG, "MP4文件记录已初始化，保存路径: " + mp4File.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "初始化文件记录失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 停止H.264文件记录和MP4封装
     */
    private void stopH264Recording() {
        isRecording = false;
        
        // 停止H.264原始文件记录
        if (h264FileOutputStream != null) {
            try {
                h264FileOutputStream.close();
                Log.i(TAG, "H.264文件记录已停止，文件大小: " + h264File.length() + " 字节");
            } catch (IOException e) {
                Log.e(TAG, "关闭H.264文件输出流失败: " + e.getMessage(), e);
            } finally {
                h264FileOutputStream = null;
            }
        }
        
        // 停止MediaMuxer并释放资源
        if (mMediaMuxer != null) {
            try {
                if (mIsMuxerStarted) {
                    mMediaMuxer.stop();
                    Log.i(TAG, "MediaMuxer已停止");
                }
                mMediaMuxer.release();
                Log.i(TAG, "MediaMuxer资源已释放");
            } catch (Exception e) {
                Log.e(TAG, "关闭MediaMuxer失败: " + e.getMessage(), e);
            } finally {
                mMediaMuxer = null;
                mIsMuxerStarted = false;
                mVideoTrackIndex = -1;
            }
        }
    }
    
    /**
     * 保存H.264数据到本地文件
     */
    private void saveH264DataToFile(byte[] data) {
        if (!isRecording || h264FileOutputStream == null) {
            return;
        }
        
        try {
            // 直接写入H.264数据，不添加长度前缀（用于文件播放）
            h264FileOutputStream.write(data);
            h264FileOutputStream.flush();
            
            Log.d(TAG, "已保存H.264数据到文件: " + data.length + " 字节");
        } catch (IOException e) {
            Log.e(TAG, "保存H.264数据到文件失败: " + e.getMessage(), e);
            stopH264Recording();
        }
    }
    
    /**
     * 发送H.264视频数据到车机
     * <p>
     * 在单独的线程中发送数据，避免阻塞MediaCodec回调线程
     * 
     * @param data H.264编码的视频数据
     */
    private void sendH264DataToCar(final byte[] data) {
        if (mSocket == null) {
            Log.i(TAG, "Socket为空，无法发送数据");
            return;
        }
        if (!mSocket.isConnected()) {
            Log.i(TAG, "Socket未连接，无法发送数据");
            return;
        }
        if (mOutputStream == null) {
            Log.i(TAG, "输出流为空，无法发送数据");
            return;
        }
        
        Log.i(TAG, "准备发送H.264数据: 大小=" + data.length + " 字节, 目标IP=" + carDeviceIp + ":" + CAR_DEVICE_PORT);
        Log.i(TAG, "Socket信息: " + mSocket.toString());
        Log.i(TAG, "Socket连接状态: " + mSocket.isConnected());
        Log.i(TAG, "Socket是否已关闭: " + mSocket.isClosed());
        Log.i(TAG, "Socket是否输入已关闭: " + mSocket.isInputShutdown());
        Log.i(TAG, "Socket是否输出已关闭: " + mSocket.isOutputShutdown());
        
        // 打印原始数据的前20字节，用于调试
        Log.d(TAG, "原始数据的前20字节: " + bytesToHex(data, Math.min(20, data.length)));
        
        // 保存H.264数据到本地文件
        saveH264DataToFile(data);
        
        mExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "在发送线程中，Socket连接状态: " + mSocket.isConnected());
                    Log.d(TAG, "在发送线程中，输出流: " + mOutputStream);
                    
                    byte[] dataToSend = data;

                    // 检查数据是否已经包含起始码（0x00000001或0x000001）
                    boolean hasStartCode = false;
                    if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
                        hasStartCode = true;
                        Log.d(TAG, "H.264数据已包含4字节起始码");
                    } else if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
                        hasStartCode = true;
                        Log.d(TAG, "H.264数据已包含3字节起始码");
                    }

                    // 如果没有起始码，添加4字节的起始码（0x00000001）
                    if (!hasStartCode) {
                        Log.d(TAG, "为H.264数据添加4字节起始码");
                        byte[] startCode = new byte[]{0x00, 0x00, 0x00, 0x01};
                        dataToSend = new byte[startCode.length + data.length];
                        System.arraycopy(startCode, 0, dataToSend, 0, startCode.length);
                        System.arraycopy(data, 0, dataToSend, startCode.length, data.length);
                    } else {
                        // 如果已经有起始码，直接使用原数据
                        dataToSend = data;
                    }
                    
                    // 打印要发送数据的前20字节，用于调试
                    Log.d(TAG, "要发送数据的前20字节: " + bytesToHex(dataToSend, Math.min(20, dataToSend.length)));

                    // 直接发送H.264数据，不添加长度前缀
                    Log.i(TAG, "开始发送H.264数据: " + dataToSend.length + " 字节");
                    
                    // 使用write方法发送数据
                    mOutputStream.write(dataToSend);
                    Log.i(TAG, "write方法调用完成，已发送" + dataToSend.length + " 字节");
                    
                    // 刷新输出流，确保数据立即发送
                    mOutputStream.flush();
                    Log.i(TAG, "flush方法调用完成");
                    
                    Log.i(TAG, "成功发送H.264数据: 总大小=" + dataToSend.length + " 字节" + 
                          ", Socket状态: connected=" + mSocket.isConnected() + ", outputStream=" + mOutputStream);
                    
                } catch (IOException e) {
                    Log.e(TAG, "发送数据失败: " + e.getMessage(), e);
                    e.printStackTrace();
                    // 尝试重新连接
                    try {
                        closeSocket();
                        // 使用已保存的车机IP地址重新连接
                        connectToCarDevice(carDeviceIp);
                    } catch (IOException ex) {
                        Log.e(TAG, "重新连接失败: " + ex.getMessage(), ex);
                        ex.printStackTrace();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "发送数据时发生未知异常: " + e.getMessage(), e);
                    e.printStackTrace();
                }
            }
        });
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     * @param data 要转换的字节数组
     * @param length 要转换的长度
     * @return 十六进制字符串
     */
    private String bytesToHex(byte[] data, int length) {
        if (data == null || data.length == 0 || length <= 0) {
            return "";
        }
        
        final StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length && i < data.length; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString();
    }
    
    /**
     * 检查数据是否已包含H.264起始码
     * @param data 要检查的数据
     * @return true如果数据以起始码开头
     */
    private boolean hasStartCode(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        // 检查4字节起始码 (0x00000001)
        if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return true;
        }
        // 检查3字节起始码 (0x000001)
        if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            return true;
        }
        return false;
    }
    
    /**
     * 发送固定的H.264测试数据到客户端
     * 用于测试网络传输和基础解码流程
     */
    private void sendTestH264Data() {
        if (mSocket == null) {
            Log.e(TAG, "Socket为空，无法发送测试数据");
            return;
        }
        if (!mSocket.isConnected()) {
            Log.e(TAG, "Socket未连接，无法发送测试数据");
            return;
        }
        if (mOutputStream == null) {
            Log.e(TAG, "输出流为空，无法发送测试数据");
            return;
        }
        
        // 固定的H.264测试数据（包含SPS、PPS和一个简单的I帧）
        byte[] testData = new byte[] {
            // SPS (Sequence Parameter Set)
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x67, (byte)0x42, (byte)0x00, (byte)0x28, 
            (byte)0xDA, (byte)0x01, (byte)0x40, (byte)0x50, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x00, 
            (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x07, (byte)0xD0, (byte)0x80, (byte)0x11, (byte)0x00, 
            (byte)0x00, (byte)0x03, (byte)0x00, (byte)0x08, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x01, 
            (byte)0x72, (byte)0x00,
            // PPS (Picture Parameter Set)
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x68, (byte)0xCE, (byte)0x38, (byte)0x80,
            // I帧 (Intra frame)
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x65, (byte)0x88, (byte)0x84, (byte)0x01, 
            (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, 
            (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, 
            (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01, (byte)0x01
        };
        
        Log.i(TAG, "开始发送H.264测试数据，总大小: " + testData.length + " 字节");
        Log.d(TAG, "测试数据前30字节: " + bytesToHex(testData, Math.min(30, testData.length)));
        
        try {
            // 发送测试数据
            mOutputStream.write(testData);
            Log.i(TAG, "write方法调用完成，已发送" + testData.length + " 字节");
            
            // 刷新输出流
            mOutputStream.flush();
            Log.i(TAG, "flush方法调用完成，测试数据发送成功");
            
        } catch (IOException e) {
            Log.e(TAG, "发送测试H.264数据失败: " + e.getMessage(), e);
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "发送测试H.264数据时发生未知异常: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    
    /**
     * 关闭Socket连接
     * 
     * @throws IOException 如果关闭失败
     */
    private void closeSocket() throws IOException {
        // 停止H.264文件记录
        stopH264Recording();
        
        if (mOutputStream != null) {
            mOutputStream.close();
            mOutputStream = null;
        }
        if (mSocket != null) {
            mSocket.close();
            mSocket = null;
        }
    }
    
    /**
     * 处理来自车机的触摸事件
     * <p>
     * 将触摸事件传递给虚拟屏上运行的应用程序
     * 
     * @param event 触摸事件
     */
    private void handleCarTouchEvent(MotionEvent event) {
        // 将触摸事件传递给虚拟屏上的DemoPresentation
        Log.i(TAG, "处理车机触摸事件: action=" + event.getAction() + ", x=" + event.getX() + ", y=" + event.getY());
        
        if (mDemoPresentation != null) {
            // 复制MotionEvent，因为原始event会在回调后被回收
            // Handler.post()是异步的，必须使用副本
            final MotionEvent eventCopy = MotionEvent.obtain(event);
            
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
            }
            handler.post(() -> {
                try {
                    if (mDemoPresentation != null) {
                        mDemoPresentation.onTouchEvent(eventCopy);
                    }
                } finally {
                    // 使用完后回收副本
                    eventCopy.recycle();
                }
            });
            Log.i(TAG, "触摸事件已传递给DemoPresentation");
        }
    }
    
    /**
     * 释放资源
     * <p>
     * 在Activity销毁时调用，释放所有相关资源
     */
    private void releaseResources() {
        Log.i(TAG, "===== 释放所有资源 =====");
        
        // 停止心跳
        stopHeartbeat();
        
        // 更新连接状态
        isConnected = false;
        
        try {
            // 关闭DemoPresentation
            if (mDemoPresentation != null) {
                mDemoPresentation.dismiss();
                mDemoPresentation = null;
                Log.i(TAG, "DemoPresentation已关闭");
            }
            
            // 停止编码器
            if (mCodec != null) {
                mCodec.stop();
                mCodec.release();
                mCodec = null;
            }
            
            // 释放虚拟屏
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            
            // 停止触摸事件接收
            if (mTouchEventReceiver != null) {
                mTouchEventReceiver.release();
                mTouchEventReceiver = null;
            }
            
            // 关闭Socket连接
            closeSocket();
            
            // 关闭线程池
            if (mExecutorService != null) {
                mExecutorService.shutdown();
                mExecutorService = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "释放资源失败: " + e.getMessage(), e);
        }
        
        Log.i(TAG, "===== 资源释放完成 =====");
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_capture); // 使用新创建的布局
        Log.i(TAG, "ScreenCaptureActivity onCreate");
        
        // 初始化UI组件
        initUI();

        updateIpAddress();
        
        // 初始化设备列表
        initDeviceList();
        
        // 设置点击监听器
        setListeners();
    }
    
    /**
     * 初始化UI组件
     */
    private void initUI() {
        mScanButton = findViewById(R.id.btn_scan);
        mDirectConnectButton = findViewById(R.id.btn_direct_connect);
        mDisconnectButton = findViewById(R.id.btn_disconnect);
        mCurrentIp = findViewById(R.id.current_ip);
        mStatusText = findViewById(R.id.status);
        mDeviceList = findViewById(R.id.device_list);
        
        // 初始化心跳Handler
        mHeartbeatHandler = new Handler(Looper.getMainLooper());
        
        // 初始状态：断开连接按钮禁用
        mDisconnectButton.setEnabled(false);
    }

    private void updateIpAddress() {
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        String ip = String.format(Locale.CHINA, "%d.%d.%d.%d",
                (ipAddress & 0xFF),
                (ipAddress >> 8 & 0xFF),
                (ipAddress >> 16 & 0xFF),
                (ipAddress >> 24 & 0xFF));
        mCurrentIp.setText(String.format("当前ip: %s", ip));
    }
    
    /**
     * 初始化设备列表
     */
    private void initDeviceList() {
        mScannedDevices = new ArrayList<>();
        mDeviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mScannedDevices);
        mDeviceList.setAdapter(mDeviceListAdapter);
    }
    
    /**
     * 设置点击监听器
     */
    private void setListeners() {
        // 扫描按钮点击事件
        mScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        
        // 直接连接按钮点击事件
        mDirectConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectToSelectedDevice("192.168.3.74");
            }
        });
        
        // 断开连接按钮点击事件
        mDisconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnectFromCarDevice();
            }
        });
        
        // 设备列表点击事件
        mDeviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String deviceIp = mScannedDevices.get(position);
                connectToSelectedDevice(deviceIp);
            }
        });
    }
    
    /**
     * 断开与车机的连接
     */
    private void disconnectFromCarDevice() {
        Log.i(TAG, "用户主动断开连接");
        
        // 停止心跳
        stopHeartbeat();
        
        // 关闭连接
        try {
            closeSocket();
        } catch (IOException e) {
            Log.e(TAG, "断开连接失败: " + e.getMessage(), e);
        }
        
        // 释放编码器资源
        if (mCodec != null) {
            try {
                mCodec.stop();
                mCodec.release();
                mCodec = null;
            } catch (Exception e) {
                Log.e(TAG, "释放编码器失败: " + e.getMessage());
            }
        }
        
        // 释放虚拟屏
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        
        // 关闭DemoPresentation
        if (mDemoPresentation != null) {
            mDemoPresentation.dismiss();
            mDemoPresentation = null;
        }
        
        // 更新连接状态
        updateConnectionState(false);
    }
    
    /**
     * 更新连接状态和UI
     */
    private void updateConnectionState(boolean connected) {
        isConnected = connected;
        runOnUiThread(() -> {
            if (connected) {
                mStatusText.setText("连接状态: 已连接到 " + carDeviceIp);
                mDisconnectButton.setEnabled(true);
                mScanButton.setEnabled(false);
                mDirectConnectButton.setEnabled(false);
                mDeviceList.setEnabled(false);
            } else {
                mStatusText.setText("连接状态: 未连接");
                mDisconnectButton.setEnabled(false);
                mScanButton.setEnabled(true);
                mDirectConnectButton.setEnabled(true);
                mDeviceList.setEnabled(true);
            }
        });
    }
    
    /**
     * 启动心跳发送
     */
    private void startHeartbeat() {
        mHeartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
        Log.i(TAG, "心跳机制已启动");
    }
    
    /**
     * 停止心跳发送
     */
    private void stopHeartbeat() {
        mHeartbeatHandler.removeCallbacks(heartbeatRunnable);
        Log.i(TAG, "心跳机制已停止");
    }
    
    /**
     * 心跳任务
     */
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected && mSocket != null && mSocket.isConnected() && !mSocket.isClosed()) {
                sendHeartbeat();
                mHeartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        }
    };
    
    /**
     * 发送心跳包
     */
    private void sendHeartbeat() {
        if (mOutputStream == null) {
            return;
        }
        
        mExecutorService.execute(() -> {
            try {
                // 检查Socket状态
                if (mSocket == null || mSocket.isClosed() || !mSocket.isConnected()) {
                    Log.w(TAG, "Socket已断开，停止心跳");
                    runOnUiThread(() -> {
                        stopHeartbeat();
                        updateConnectionState(false);
                        Toast.makeText(ScreenCaptureActivity.this, "连接已断开", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // 发送心跳数据
                mOutputStream.write(HEARTBEAT_DATA);
                mOutputStream.flush();
                Log.d(TAG, "心跳包已发送");
                
            } catch (IOException e) {
                Log.e(TAG, "发送心跳失败，连接可能已断开: " + e.getMessage());
                runOnUiThread(() -> {
                    stopHeartbeat();
                    updateConnectionState(false);
                    Toast.makeText(ScreenCaptureActivity.this, "连接已断开", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * 开始扫描设备
     */
    private void startScan() {
        if (isScanning) {
            Toast.makeText(this, "正在扫描中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 设置扫描状态
        isScanning = true;
        
        // 清空之前的扫描结果
        mScannedDevices.clear();
        mDeviceListAdapter.notifyDataSetChanged();
        
        // 更新UI状态
        mStatusText.setText("正在扫描...");
        mScanButton.setText("扫描中");
        mScanButton.setEnabled(false);
        
        Toast.makeText(this, "开始扫描设备...", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "开始扫描设备");
        
        // 在后台线程中执行扫描
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 执行扫描
                final java.util.List<String> foundDevices = scanLocalNetwork();
                
                // 扫描完成后更新UI
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 更新扫描状态
                        isScanning = false;
                        
                        // 更新UI状态
                        mScanButton.setText("扫描设备");
                        mScanButton.setEnabled(true);
                        
                        if (foundDevices.isEmpty()) {
                            mStatusText.setText("未找到设备");
                            Toast.makeText(ScreenCaptureActivity.this, "未找到设备", Toast.LENGTH_SHORT).show();
                        } else {
                            mStatusText.setText("找到 " + foundDevices.size() + " 个设备");
                            Toast.makeText(ScreenCaptureActivity.this, "找到 " + foundDevices.size() + " 个设备", Toast.LENGTH_SHORT).show();
                            
                            // 更新设备列表
                            mScannedDevices.addAll(foundDevices);
                            mDeviceListAdapter.notifyDataSetChanged();
                        }
                    }
                });
            }
        }).start();
    }
    
    /**
     * 连接到选中的设备
     * 
     * @param deviceIp 选中设备的IP地址
     */
    private void connectToSelectedDevice(String deviceIp) {
        if (deviceIp == null || deviceIp.isEmpty()) {
            Toast.makeText(this, "无效的设备IP", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(this, "正在连接到设备: " + deviceIp, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "正在连接到设备: " + deviceIp);
        
        // 更新UI状态
        mStatusText.setText("正在连接到: " + deviceIp);
        mScanButton.setEnabled(false);
        mDeviceList.setEnabled(false);
        
        // 初始化线程池
        if (mExecutorService == null || mExecutorService.isShutdown()) {
            mExecutorService = Executors.newSingleThreadExecutor();
        }
        
        // 保存车机IP地址
        carDeviceIp = deviceIp;
        
        // 连接到车机
        connectToCarDevice(carDeviceIp);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Activity销毁，释放资源");
        releaseResources();
    }
}
