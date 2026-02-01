package com.projection.screen.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.wifi.WifiManager;
import android.os.Build;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    
    /** MediaProjectionManager实例，用于创建MediaProjection */
    private MediaProjectionManager mMediaProjectionManager;
    
    /** ProjectionService实例 */
    private ProjectionService mProjectionService;
    
    /** Service连接 */
    private ServiceConnection mServiceConnection;
    
    /** Surface实例，作为编码器的输入目标，虚拟屏内容将渲染到这个Surface上 */
    private Surface mInputSurface;
    
    /** 投屏模式：true=主屏幕，false=虚拟屏 */
    private boolean isMainScreenMode = false;

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
    private RadioGroup mProjectionModeGroup;
    private RadioButton mRadioVirtualDisplay;
    private RadioButton mRadioMainScreen;
    
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
    
    // MediaProjection请求码
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

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
     * 重新配置编码器以适应当前模式（在模式切换时调用）
     */
    private void reconfigureEncoderForCurrentMode() {
        if (mExecutorService == null || mExecutorService.isShutdown()) {
            mExecutorService = Executors.newSingleThreadExecutor();
        }
        
        mExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "开始重新配置编码器，当前模式: " + (isMainScreenMode ? "主屏幕" : "虚拟屏"));
                    
                    // 清理旧模式的资源
                    if (isMainScreenMode) {
                        // 切换到主屏幕模式：清理虚拟屏模式的资源
                        if (mCodec != null) {
                            try {
                                mCodec.stop();
                                mCodec.release();
                                mCodec = null;
                                Log.i(TAG, "已停止虚拟屏模式的编码器");
                            } catch (Exception e) {
                                Log.w(TAG, "停止虚拟屏编码器失败: " + e.getMessage());
                                mCodec = null;
                            }
                        }
                        if (mVirtualDisplay != null) {
                            try {
                                mVirtualDisplay.release();
                                mVirtualDisplay = null;
                                Log.i(TAG, "已释放虚拟屏模式的VirtualDisplay");
                            } catch (Exception e) {
                                Log.w(TAG, "释放虚拟屏VirtualDisplay失败: " + e.getMessage());
                                mVirtualDisplay = null;
                            }
                        }
                        if (mDemoPresentation != null) {
                            mDemoPresentation.dismiss();
                            mDemoPresentation = null;
                            Log.i(TAG, "已关闭虚拟屏模式的DemoPresentation");
                        }
                    } else {
                        // 切换到虚拟屏模式：清理主屏幕模式的资源
                        // 先停止服务（这会释放服务中的编码器）
                        if (mProjectionService != null) {
                            Intent serviceIntent = new Intent(ScreenCaptureActivity.this, ProjectionService.class);
                            stopService(serviceIntent);
                            Log.i(TAG, "已停止主屏幕模式的服务");
                        }
                        
                        // 等待一小段时间，确保服务中的编码器回调执行完毕
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // 清空服务引用，防止编码回调中访问已释放的服务
                        mProjectionService = null;
                        
                        if (mServiceConnection != null) {
                            try {
                                unbindService(mServiceConnection);
                                Log.i(TAG, "已解绑主屏幕模式的服务");
                            } catch (Exception e) {
                                Log.w(TAG, "解绑服务失败: " + e.getMessage());
                            }
                            mServiceConnection = null;
                        }
                    }
                    
                    // 重置MediaMuxer状态（重新配置编码器时需要重新初始化）
                    // 注意：必须在停止编码器之后重置，避免编码回调访问已释放的MediaMuxer
                    if (mMediaMuxer != null) {
                        try {
                            if (mIsMuxerStarted) {
                                mMediaMuxer.stop();
                            }
                            mMediaMuxer.release();
                        } catch (Exception e) {
                            Log.w(TAG, "释放旧MediaMuxer失败: " + e.getMessage());
                        }
                        mMediaMuxer = null;
                        mIsMuxerStarted = false;
                        mVideoTrackIndex = -1;
                        Log.i(TAG, "已重置MediaMuxer状态");
                    }
                    
                    // 重新初始化H.264文件记录（因为MediaMuxer已重置）
                    initH264Recording();
                    
                    // 重新配置编码器
                    configureMediaCodecAndCreateVirtualDisplay();
                    Log.i(TAG, "编码器重新配置完成");
                    
                    // 发送缓存的SPS/PPS（如果有）
                    if (cachedSPSWithStartCode != null && cachedPPSWithStartCode != null) {
                        Log.i(TAG, "发送缓存的SPS/PPS数据");
                        sendH264DataToCar(cachedSPSWithStartCode);
                        sendH264DataToCar(cachedPPSWithStartCode);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "重新配置编码器失败: " + e.getMessage(), e);
                    runOnUiThread(() -> {
                        Toast.makeText(ScreenCaptureActivity.this, "重新配置编码器失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }
    
    /**
     * 配置MediaCodec编码器和创建VirtualDisplay或MediaProjection
     * <p>
     * 该方法在成功连接到车机后调用，执行以下操作：
     * 1. 配置MediaCodec编码器，设置编码参数
     * 2. 根据模式选择创建VirtualDisplay虚拟屏或MediaProjection主屏幕捕获
     * 3. 设置MediaCodec回调，处理编码输出数据
     * 
     * @throws IOException 如果MediaCodec创建失败
     */
    private void configureMediaCodecAndCreateVirtualDisplay() throws IOException {
        if (isMainScreenMode) {
            // 主屏幕模式：需要先请求MediaProjection权限
            // 注意：RESULT_OK的值是-1，所以用-2表示未设置
            if (mMediaProjectionResultCode == -2 || mMediaProjectionData == null) {
                Log.i(TAG, "主屏幕模式：请求MediaProjection权限");
                requestMediaProjectionPermission();
                return; // 等待权限回调
            }
            // 权限已获取，启动服务
            configureMediaCodecAndCreateMediaProjection();
        } else {
            // 虚拟屏模式：使用原有逻辑
            configureMediaCodecAndCreateVirtualDisplayInternal();
        }
    }
    
    /**
     * 请求MediaProjection权限
     */
    private void requestMediaProjectionPermission() {
        if (mMediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager未初始化");
            return;
        }
        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }
    
    // 保存MediaProjection的resultCode和data
    // 注意：RESULT_OK的值是-1，所以用-2表示未设置
    private int mMediaProjectionResultCode = -2;
    private Intent mMediaProjectionData = null;
    
    /**
     * 配置MediaCodec编码器和创建MediaProjection（主屏幕捕获）
     * 通过前台服务来处理MediaProjection
     */
    private void configureMediaCodecAndCreateMediaProjection() {
        Log.i(TAG, "configureMediaCodecAndCreateMediaProjection: 主屏幕模式");
        Log.i(TAG, "检查MediaProjection数据: resultCode=" + mMediaProjectionResultCode + ", data=" + mMediaProjectionData);
        
        // 注意：RESULT_OK的值是-1，所以用-2表示未设置
        if (mMediaProjectionResultCode == -2 || mMediaProjectionData == null) {
            Log.e(TAG, "MediaProjection数据无效，无法启动服务: resultCode=" + mMediaProjectionResultCode + ", data=" + mMediaProjectionData);
            return;
        }
        
        // 启动前台服务，传递resultCode和data
        Intent serviceIntent = new Intent(this, ProjectionService.class);
        serviceIntent.putExtra("result_code", mMediaProjectionResultCode);
        serviceIntent.putExtra("data", mMediaProjectionData);
        
        // 绑定服务
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "ProjectionService已连接");
                ProjectionService.LocalBinder binder = (ProjectionService.LocalBinder) service;
                mProjectionService = binder.getService();
                
                // 等待编码器创建完成（服务可能在后台线程中创建编码器）
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 检查Socket和编码器是否都准备好了
                        if (mSocket == null || mOutputStream == null) {
                            Log.w(TAG, "Socket尚未准备好，100ms后重试");
                            handler.postDelayed(this, 100);
                            return;
                        }
                        
                        mCodec = mProjectionService.getCodec();
                        if (mCodec != null) {
                            // 先设置Socket，再设置编码回调
                            mProjectionService.setSocket(mSocket, mOutputStream);
                            Log.i(TAG, "Socket和OutputStream已传递给服务");
                            
                            mProjectionService.setCodecCallback(createMediaCodecCallback());
                            Log.i(TAG, "编码回调已设置");
                            
                            // 启动MainScreenActivity
                            Intent intent = new Intent(ScreenCaptureActivity.this, MainScreenActivity.class);
                            startActivity(intent);
                            Log.i(TAG, "MainScreenActivity已启动");
                        } else {
                            Log.w(TAG, "编码器尚未创建，100ms后重试");
                            handler.postDelayed(this, 100);
                        }
                    }
                }, 100); // 延迟100ms等待服务创建编码器
            }
            
            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "ProjectionService已断开");
                mProjectionService = null;
            }
        };
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }
    
    /**
     * 配置MediaCodec编码器和创建VirtualDisplay（虚拟屏模式）
     * 
     * @throws IOException 如果MediaCodec创建失败
     */
    private void configureMediaCodecAndCreateVirtualDisplayInternal() throws IOException {
        Log.i(TAG, "configureMediaCodecAndCreateVirtualDisplayInternal: 虚拟屏模式");
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
        mCodec.setCallback(createMediaCodecCallback());
        
        // 启动编码器
        mCodec.start();

        // 获取当前手机屏幕的指标（用于参考，实际使用手动指定的密度）
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int density = DisplayMetrics.DENSITY_DEFAULT;
        Log.d(TAG, "startProjection: 屏幕密度=" + density);

        // 2. 创建 Virtual Display
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        
        if (displayManager == null) {
            Log.e(TAG, "DisplayManager获取失败，无法创建虚拟显示");
            throw new IOException("DisplayManager获取失败");
        }
        
        if (mInputSurface == null) {
            Log.e(TAG, "输入Surface获取失败，无法创建虚拟显示");
            throw new IOException("输入Surface获取失败");
        }
        
        try {
            mVirtualDisplay = displayManager.createVirtualDisplay(
                    "CarScreen",
                    WIDTH, HEIGHT, density,
                    mInputSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | 
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            );
            
            if (mVirtualDisplay == null) {
                throw new IOException("虚拟屏创建失败");
            }
            
            Log.i(TAG, "虚拟屏创建成功: 宽=" + WIDTH + " 高=" + HEIGHT + " 密度=" + density);
        } catch (Exception e) {
            Log.e(TAG, "创建虚拟屏失败: " + e.getMessage(), e);
            throw new IOException("创建虚拟屏失败", e);
        }
        
        // 3. 在虚拟屏上显示DemoPresentation
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
                Log.i(TAG, "找到 " + displays.length + " 个演示显示设备");
                for (Display display : displays) {
                    if (display.getName().equals("CarScreen")) {
                        try {
                            mDemoPresentation = new DemoPresentation(ScreenCaptureActivity.this, display);
                            mDemoPresentation.show();
                            Log.i(TAG, "在虚拟屏上显示DemoPresentation成功");
                            break;
                        } catch (Exception e) {
                            Log.e(TAG, "创建或显示DemoPresentation失败: " + e.getMessage(), e);
                        }
                    }
                }
                
                if (mDemoPresentation == null && displays.length > 0) {
                    try {
                        mDemoPresentation = new DemoPresentation(ScreenCaptureActivity.this, displays[0]);
                        mDemoPresentation.show();
                        Log.i(TAG, "在第一个显示屏上显示DemoPresentation成功");
                    } catch (Exception e) {
                        Log.e(TAG, "创建或显示DemoPresentation失败: " + e.getMessage(), e);
                    }
                }
            }
        });
    }
    
    /**
     * 创建MediaCodec回调
     */
    private MediaCodec.Callback createMediaCodecCallback() {
        return new MediaCodec.Callback() {
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
                // 检查编码器是否仍然有效
                if (codec == null) {
                    Log.w(TAG, "编码器为null，忽略输出缓冲区");
                    return;
                }
                
                // 检查当前模式是否匹配（防止模式切换后回调还在执行）
                boolean shouldProcess = false;
                if (isMainScreenMode) {
                    // 主屏幕模式：检查服务是否存在，并且编码器引用匹配
                    // 注意：主屏幕模式下，mCodec可能是服务中的编码器引用
                    if (mProjectionService != null) {
                        MediaCodec serviceCodec = mProjectionService.getCodec();
                        shouldProcess = (serviceCodec == codec);
                    }
                } else {
                    // 虚拟屏模式：检查Activity中的编码器引用是否匹配
                    shouldProcess = (mCodec == codec && mCodec != null);
                }
                
                if (!shouldProcess) {
                    Log.w(TAG, "编码器不匹配或模式已切换，释放缓冲区但不处理数据。isMainScreenMode=" + isMainScreenMode + ", mCodec=" + mCodec + ", codec=" + codec);
                    try {
                        codec.releaseOutputBuffer(index, false);
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "释放输出缓冲区失败（编码器可能已释放）: " + e.getMessage());
                    }
                    return;
                }
                
                // 获取输出缓冲区
                ByteBuffer outputBuffer = null;
                try {
                    outputBuffer = codec.getOutputBuffer(index);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "获取输出缓冲区失败（编码器可能已释放）: " + e.getMessage());
                    try {
                        codec.releaseOutputBuffer(index, false);
                    } catch (IllegalStateException e2) {
                        // 忽略
                    }
                    return;
                }
                
                if (outputBuffer == null) {
                    Log.e(TAG, "输出缓冲区为空，无法获取编码数据");
                    try {
                        codec.releaseOutputBuffer(index, false);
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "释放输出缓冲区失败: " + e.getMessage());
                    }
                    return;
                }
                
                Log.d(TAG, "编码完成一帧数据: 大小=" + info.size + " 字节, 时间戳=" + info.presentationTimeUs + "us, 标志=" + info.flags);
                
                // 使用MediaMuxer将数据写入MP4文件
                // 注意：需要检查MediaMuxer状态，避免访问已释放的MediaMuxer
                if (mIsMuxerStarted && mVideoTrackIndex != -1 && mMediaMuxer != null) {
                    try {
                        // 确保ByteBuffer的position和limit正确
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        
                        // 将数据写入MP4文件
                        mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, info);
                    } catch (IllegalStateException e) {
                        // MediaMuxer可能已经被释放或状态不正确
                        Log.w(TAG, "写入MediaMuxer失败（可能已释放）: " + e.getMessage());
                        mMediaMuxer = null;
                        mIsMuxerStarted = false;
                        mVideoTrackIndex = -1;
                    }
                }
                
                // 将编码后的数据复制到字节数组中
                // 【关键点】这里的 byte[] 就是编码后的H.264视频数据
                // 在实际应用中，这些数据可以通过USB/Wi-Fi等方式传输到外部设备（如车机）
                byte[] h264Data = new byte[info.size];
                // 重新设置position以便读取数据
                outputBuffer.position(info.offset);
                outputBuffer.get(h264Data);
                
                // 发送H.264数据到车机
                // 主屏幕模式下，通过服务发送；虚拟屏模式下，直接发送
                if (isMainScreenMode && mProjectionService != null) {
                    mProjectionService.sendH264Data(h264Data);
                } else {
                    sendH264DataToCar(h264Data);
                }
                
                // 释放输出缓冲区，以便编码器可以继续使用它
                try {
                    codec.releaseOutputBuffer(index, false);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "释放输出缓冲区失败（编码器可能已释放）: " + e.getMessage());
                }
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
                    
                    // 主屏幕模式下，通过服务发送；虚拟屏模式下，直接发送
                    if (isMainScreenMode && mProjectionService != null) {
                        mProjectionService.sendH264Data(spsWithStartCode);
                        mProjectionService.sendH264Data(ppsWithStartCode);
                    } else {
                    // 主屏幕模式下，通过服务发送；虚拟屏模式下，直接发送
                    if (isMainScreenMode && mProjectionService != null) {
                        mProjectionService.sendH264Data(spsWithStartCode);
                        mProjectionService.sendH264Data(ppsWithStartCode);
                    } else {
                        sendH264DataToCar(spsWithStartCode);
                        sendH264DataToCar(ppsWithStartCode);
                    }
                    }
                }
                
                // 使用MediaMuxer时，在输出格式改变时添加视频轨道
                // 注意：需要确保MediaMuxer已经初始化且未启动
                if (mMediaMuxer != null && !mIsMuxerStarted) {
                    try {
                        mVideoTrackIndex = mMediaMuxer.addTrack(format);
                        // 开始混合（必须在添加所有轨道后调用）
                        mMediaMuxer.start();
                        mIsMuxerStarted = true;
                        Log.i(TAG, "MediaMuxer已启动，视频轨道索引: " + mVideoTrackIndex);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "MediaMuxer添加轨道失败: " + e.getMessage());
                        // MediaMuxer可能已经释放或状态不正确，重置状态
                        mMediaMuxer = null;
                        mIsMuxerStarted = false;
                        mVideoTrackIndex = -1;
                    }
                } else if (mMediaMuxer != null && mIsMuxerStarted) {
                    Log.w(TAG, "MediaMuxer已经启动，跳过添加轨道");
                }
            }
        };
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
                    
                    // 如果服务已连接，立即设置Socket
                    if (mProjectionService != null) {
                        mProjectionService.setSocket(mSocket, mOutputStream);
                        Log.i(TAG, "Socket已更新到服务");
                    }
                    
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
                    
                    
                    // 连接成功后，配置MediaCodec编码器和创建VirtualDisplay
                    // 这会确保客户端能够收到完整的SPS/PPS和I帧
                    try {
                        // 如果当前模式与已存在的资源不匹配，先清理
                        if (isMainScreenMode) {
                            // 主屏幕模式：清理虚拟屏模式的资源
                            if (mCodec != null) {
                                try {
                                    mCodec.stop();
                                    mCodec.release();
                                    mCodec = null;
                                    Log.i(TAG, "清理虚拟屏模式的编码器");
                                } catch (IllegalStateException e) {
                                    Log.w(TAG, "清理编码器失败: " + e.getMessage());
                                    mCodec = null;
                                }
                            }
                            if (mVirtualDisplay != null) {
                                try {
                                    mVirtualDisplay.release();
                                    mVirtualDisplay = null;
                                    Log.i(TAG, "清理虚拟屏模式的VirtualDisplay");
                                } catch (Exception e) {
                                    Log.w(TAG, "清理VirtualDisplay失败: " + e.getMessage());
                                    mVirtualDisplay = null;
                                }
                            }
                            if (mDemoPresentation != null) {
                                mDemoPresentation.dismiss();
                                mDemoPresentation = null;
                                Log.i(TAG, "清理虚拟屏模式的DemoPresentation");
                            }
                        } else {
                            // 虚拟屏模式：清理主屏幕模式的资源
                            if (mServiceConnection != null) {
                                try {
                                    unbindService(mServiceConnection);
                                    Log.i(TAG, "清理主屏幕模式的服务连接");
                                } catch (Exception e) {
                                    Log.w(TAG, "解绑服务失败: " + e.getMessage());
                                }
                                mServiceConnection = null;
                            }
                            if (mProjectionService != null) {
                                stopService(new Intent(ScreenCaptureActivity.this, ProjectionService.class));
                                mProjectionService = null;
                                Log.i(TAG, "清理主屏幕模式的服务");
                            }
                        }
                        
                        // 只在虚拟屏模式下才需要检查Activity中的编码器
                        // 主屏幕模式下，编码器在服务中管理
                        if (!isMainScreenMode && mCodec != null) {
                            try {
                                // 如果编码器已存在，先停止并释放
                                mCodec.stop();
                                mCodec.release();
                                mCodec = null;
                                Log.i(TAG, "旧编码器已停止并释放");
                            } catch (IllegalStateException e) {
                                // 编码器可能已经被释放，忽略错误
                                Log.w(TAG, "编码器已被释放，无需停止: " + e.getMessage());
                                mCodec = null;
                            }
                        } else if (isMainScreenMode && mCodec != null) {
                            // 主屏幕模式下，Activity中的mCodec引用应该为null（编码器在服务中）
                            Log.w(TAG, "主屏幕模式下发现Activity中的编码器引用，清空它");
                            mCodec = null;
                        }
                        
                        // 释放旧的VirtualDisplay（只在虚拟屏模式下）
                        if (!isMainScreenMode && mVirtualDisplay != null) {
                            try {
                                mVirtualDisplay.release();
                                mVirtualDisplay = null;
                                Log.i(TAG, "旧虚拟屏已释放");
                            } catch (Exception e) {
                                Log.w(TAG, "释放虚拟屏失败: " + e.getMessage());
                                mVirtualDisplay = null;
                            }
                        } else if (isMainScreenMode && mVirtualDisplay != null) {
                            // 主屏幕模式下，VirtualDisplay在服务中，清空引用
                            Log.w(TAG, "主屏幕模式下发现Activity中的VirtualDisplay引用，清空它");
                            mVirtualDisplay = null;
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
     * 根据投屏模式将触摸事件传递给DemoPresentation或MainScreenActivity
     * 
     * @param event 触摸事件
     */
    private void handleCarTouchEvent(MotionEvent event) {
        Log.i(TAG, "处理车机触摸事件: action=" + event.getAction() + ", x=" + event.getX() + ", y=" + event.getY() + ", 模式=" + (isMainScreenMode ? "主屏幕" : "虚拟屏"));
        
        final MotionEvent eventCopy = MotionEvent.obtain(event);
        
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        
        handler.post(() -> {
            try {
                if (isMainScreenMode) {
                    // 主屏幕模式：发送广播给MainScreenActivity
                    Intent intent = new Intent("com.projection.screen.server.TOUCH_EVENT");
                    intent.putExtra("action", eventCopy.getAction());
                    intent.putExtra("x", eventCopy.getX());
                    intent.putExtra("y", eventCopy.getY());
                    intent.putExtra("pressure", eventCopy.getPressure());
                    intent.putExtra("size", eventCopy.getSize());
                    sendBroadcast(intent);
                    Log.i(TAG, "触摸事件已通过广播发送给MainScreenActivity");
                } else {
                    // 虚拟屏模式：传递给DemoPresentation
                    if (mDemoPresentation != null) {
                        mDemoPresentation.onTouchEvent(eventCopy);
                        Log.i(TAG, "触摸事件已传递给DemoPresentation");
                    }
                }
            } finally {
                eventCopy.recycle();
            }
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            Log.i(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode + ", data=" + data);
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "MediaProjection权限已授予");
                // 保存resultCode和data，传递给服务
                mMediaProjectionResultCode = resultCode;
                mMediaProjectionData = new Intent(data); // 创建副本，避免被回收
                Log.i(TAG, "MediaProjection数据已保存: resultCode=" + mMediaProjectionResultCode + ", data=" + mMediaProjectionData);
                
                // 继续配置编码器和创建MediaProjection（通过服务）
                configureMediaCodecAndCreateMediaProjection();
            } else {
                Log.e(TAG, "MediaProjection权限被拒绝");
                runOnUiThread(() -> {
                    mStatusText.setText("投屏失败: 需要屏幕录制权限");
                    Toast.makeText(this, "需要屏幕录制权限才能投屏主屏幕", Toast.LENGTH_SHORT).show();
                });
                updateConnectionState(false);
            }
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
            
            // 根据模式释放资源
            if (isMainScreenMode) {
                // 主屏幕模式：停止服务（服务会释放所有资源）
                if (mServiceConnection != null) {
                    try {
                        unbindService(mServiceConnection);
                        Log.i(TAG, "服务已解绑");
                    } catch (Exception e) {
                        Log.e(TAG, "解绑服务失败: " + e.getMessage());
                    }
                    mServiceConnection = null;
                }
                
                if (mProjectionService != null) {
                    stopService(new Intent(this, ProjectionService.class));
                    mProjectionService = null;
                    Log.i(TAG, "服务已停止");
                }
                
                // 关闭MainScreenActivity（如果正在运行）
                Intent intent = new Intent("com.projection.screen.server.TOUCH_EVENT");
                intent.putExtra("finish", true);
                sendBroadcast(intent);
                Log.i(TAG, "MainScreenActivity关闭信号已发送");
            } else {
                // 虚拟屏模式：释放编码器和VirtualDisplay
                if (mCodec != null) {
                    try {
                        mCodec.stop();
                        mCodec.release();
                        mCodec = null;
                        Log.i(TAG, "编码器已释放");
                    } catch (Exception e) {
                        Log.e(TAG, "释放编码器失败: " + e.getMessage());
                    }
                }
                
                if (mVirtualDisplay != null) {
                    mVirtualDisplay.release();
                    mVirtualDisplay = null;
                    Log.i(TAG, "虚拟屏已释放");
                }
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
        mProjectionModeGroup = findViewById(R.id.projection_mode_group);
        mRadioVirtualDisplay = findViewById(R.id.radio_virtual_display);
        mRadioMainScreen = findViewById(R.id.radio_main_screen);
        // 绑定按钮并设置点击事件
        findViewById(R.id.btn_goto_audio).setOnClickListener(v -> {
            Intent intent = new Intent(ScreenCaptureActivity.this, AudioCaptureActivity.class);
            startActivity(intent);
        });
        
        // 初始化心跳Handler
        mHeartbeatHandler = new Handler(Looper.getMainLooper());
        
        // 初始化MediaProjectionManager
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
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
        
        // 投屏模式选择事件
        mProjectionModeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.radio_main_screen) {
                    isMainScreenMode = true;
                    Log.i(TAG, "投屏模式切换为：主屏幕");
                    // 切换到主屏幕模式时，清空旧的MediaProjection数据（如果之前是虚拟屏模式）
                    // 这样可以确保下次连接时重新请求权限
                    mMediaProjectionResultCode = -2;
                    mMediaProjectionData = null;
                    Log.i(TAG, "切换到主屏幕模式：已清空MediaProjection数据");
                    
                    // 如果已连接，需要重新配置编码器
                    if (isConnected && mSocket != null && mSocket.isConnected()) {
                        Log.i(TAG, "模式切换且已连接，重新配置编码器");
                        reconfigureEncoderForCurrentMode();
                    }
                } else if (checkedId == R.id.radio_virtual_display) {
                    isMainScreenMode = false;
                    Log.i(TAG, "投屏模式切换为：虚拟屏");
                    // 虚拟屏模式下，清空MediaProjection数据（因为虚拟屏不需要MediaProjection）
                    mMediaProjectionResultCode = -2;
                    mMediaProjectionData = null;
                    Log.i(TAG, "虚拟屏模式：已清空MediaProjection数据");
                    
                    // 如果已连接，需要重新配置编码器
                    if (isConnected && mSocket != null && mSocket.isConnected()) {
                        Log.i(TAG, "模式切换且已连接，重新配置编码器");
                        reconfigureEncoderForCurrentMode();
                    }
                }
            }
        });
    }
    
    /**
     * 断开与车机的连接
     */
    private void disconnectFromCarDevice() {
        Log.i(TAG, "用户主动断开连接");
        
        // 先更新状态，防止重复操作
        if (!isConnected) {
            Log.w(TAG, "已经处于未连接状态，无需断开");
            return;
        }
        
        // 停止心跳
        stopHeartbeat();
        
        // 关闭连接
        try {
            closeSocket();
        } catch (IOException e) {
            Log.e(TAG, "断开连接失败: " + e.getMessage(), e);
        }
        
        // 停止触摸事件接收
        if (mTouchEventReceiver != null) {
            mTouchEventReceiver.release();
            mTouchEventReceiver = null;
        }
        
        // 根据模式释放资源
        if (isMainScreenMode) {
            // 主屏幕模式：停止服务（服务会释放所有资源）
            if (mServiceConnection != null) {
                try {
                    unbindService(mServiceConnection);
                    Log.i(TAG, "服务已解绑");
                } catch (Exception e) {
                    Log.e(TAG, "解绑服务失败: " + e.getMessage());
                }
                mServiceConnection = null;
            }
            
            if (mProjectionService != null) {
                stopService(new Intent(this, ProjectionService.class));
                mProjectionService = null;
                Log.i(TAG, "服务已停止");
            }
            
            // 清空Activity中的编码器引用（主屏幕模式下编码器在服务中）
            mCodec = null;
            mVirtualDisplay = null;
            
            // 清空MediaProjection数据（MediaProjection的resultData是一次性的，不能重复使用）
            mMediaProjectionResultCode = -2;
            mMediaProjectionData = null;
            Log.i(TAG, "主屏幕模式断开连接：已清空MediaProjection数据，下次连接需要重新请求权限");
            
            // 关闭MainScreenActivity（如果正在运行）
            Intent intent = new Intent("com.projection.screen.server.TOUCH_EVENT");
            intent.putExtra("finish", true);
            sendBroadcast(intent);
            Log.i(TAG, "MainScreenActivity关闭信号已发送");
        } else {
            // 虚拟屏模式：释放编码器和VirtualDisplay
            if (mCodec != null) {
                try {
                    mCodec.stop();
                    mCodec.release();
                    mCodec = null;
                    Log.i(TAG, "编码器已释放");
                } catch (IllegalStateException e) {
                    // 编码器可能已经被释放，忽略错误
                    Log.w(TAG, "编码器已被释放: " + e.getMessage());
                    mCodec = null;
                } catch (Exception e) {
                    Log.e(TAG, "释放编码器失败: " + e.getMessage());
                    mCodec = null;
                }
            }
            
            if (mVirtualDisplay != null) {
                try {
                    mVirtualDisplay.release();
                    mVirtualDisplay = null;
                    Log.i(TAG, "虚拟屏已释放");
                } catch (Exception e) {
                    Log.e(TAG, "释放虚拟屏失败: " + e.getMessage());
                    mVirtualDisplay = null;
                }
            }
            
            // 关闭DemoPresentation
            if (mDemoPresentation != null) {
                mDemoPresentation.dismiss();
                mDemoPresentation = null;
                Log.i(TAG, "DemoPresentation已关闭");
            }
            
            // 虚拟屏模式下，清空MediaProjection数据（因为虚拟屏不需要MediaProjection）
            mMediaProjectionResultCode = -2;
            mMediaProjectionData = null;
            Log.i(TAG, "虚拟屏模式断开连接：已清空MediaProjection数据");
        }
        
        // 更新连接状态（最后更新，确保UI正确）
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
     * 检查实际连接状态并同步
     */
    private void syncConnectionState() {
        boolean actuallyConnected = false;
        
        if (isMainScreenMode) {
            // 主屏幕模式：检查服务状态和Socket状态
            if (mProjectionService != null && mSocket != null && mSocket.isConnected() && !mSocket.isClosed()) {
                actuallyConnected = true;
            }
        } else {
            // 虚拟屏模式：检查Socket状态
            if (mSocket != null && mSocket.isConnected() && !mSocket.isClosed()) {
                actuallyConnected = true;
            }
        }
        
        // 如果状态不一致，更新状态
        if (actuallyConnected != isConnected) {
            Log.w(TAG, "连接状态不一致，同步状态: " + isConnected + " -> " + actuallyConnected);
            updateConnectionState(actuallyConnected);
        }
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
            // 同步连接状态
            syncConnectionState();
            
            if (isConnected && mSocket != null && mSocket.isConnected() && !mSocket.isClosed()) {
                sendHeartbeat();
                mHeartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            } else {
                // 如果连接已断开，停止心跳并更新状态
                Log.w(TAG, "心跳检测到连接已断开");
                stopHeartbeat();
                updateConnectionState(false);
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
