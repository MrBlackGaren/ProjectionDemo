package com.projection.screen.server;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 优化后的音频录制与AAC编码 Activity
 * 实现了内存复用、异步 IO 写入和线程解耦，性能更优。
 */
public class AudioCaptureActivity extends AppCompatActivity {
    private static final String TAG = "AudioCaptureActivity";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_COUNT = 2;
    private static final int BIT_RATE = 128000;

    private AudioRecord mAudioRecord;
    private MediaCodec mAudioEncoder;
    private volatile boolean isRecording = false;
    private ExecutorService mExecutorService;
    private BufferedOutputStream mFileOutputStream;
    private String mOutputPath;

    // 优化：限容队列，防止 OOM
    private final BlockingQueue<Integer> mInputBufferQueue = new LinkedBlockingQueue<>(16);
    private final BlockingQueue<byte[]> mOutputDataQueue = new LinkedBlockingQueue<>(64);
    // 优化：内存池，复用字节数组，减少 GC 压力
    private final BlockingQueue<byte[]> mBufferPool = new LinkedBlockingQueue<>(64);

    private Button mBtnStart;
    private Button mBtnStop;
    private TextView mTvStatus;
    private TextView mTvPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_capture);

        mBtnStart = findViewById(R.id.btn_start_record);
        mBtnStop = findViewById(R.id.btn_stop_record);
        mTvStatus = findViewById(R.id.tv_status);
        mTvPath = findViewById(R.id.tv_file_path);

        mBtnStart.setOnClickListener(v -> startRecordingWithPermission());
        mBtnStop.setOnClickListener(v -> stopRecording());

        // 初始化内存池，AAC 帧加上 ADTS 头通常很小，4KB 足够复用
        for (int i = 0; i < 60; i++) {
            mBufferPool.offer(new byte[4096]);
        }

        mExecutorService = Executors.newFixedThreadPool(3); // 1个读取，1个写入，1个额外
    }

    private void startRecordingWithPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        if (isRecording) return;

        try {
            mInputBufferQueue.clear();
            mOutputDataQueue.clear();
            initEncoder();
            initAudioRecord();

            File outFile = new File(getExternalFilesDir(null), "recorded_audio_" + System.currentTimeMillis() + ".aac");
            mOutputPath = outFile.getAbsolutePath();
            mFileOutputStream = new BufferedOutputStream(new FileOutputStream(outFile));

            mAudioRecord.startRecording();
            mAudioEncoder.start();
            isRecording = true;

            mBtnStart.setEnabled(false);
            mBtnStop.setEnabled(true);
            mTvStatus.setText("状态: 录制中...");
            mTvPath.setText("保存路径: " + mOutputPath);

            // 两个循环分别处理 采集入队 和 异步写入出队
            mExecutorService.execute(this::recordingLoop);
            mExecutorService.execute(this::writingLoop);

        } catch (IOException e) {
            Log.e(TAG, "启动录制失败", e);
            Toast.makeText(this, "启动录制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2);
    }

    private void initEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 10);

        mAudioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        mAudioEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                mInputBufferQueue.offer(index); // 快速入队，不阻塞
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0) {
                    int outPacketSize = info.size + 7;
                    // 优化：从内存池中拿数组，避免 new
                    byte[] data = mBufferPool.poll();
                    if (data == null) data = new byte[4096]; // 池空了才 new

                    addADTStoPacket(data, outPacketSize);
                    outputBuffer.position(info.offset);
                    outputBuffer.get(data, 7, info.size);

                    // 将带有长度信息的封装对象或约定前两个字节/特定长度丢入 IO 队列
                    // 这里为了简单，我们约定通过 BlockingQueue 传递这个大小
                    // 或者更优雅点：把长度存在数组前几位。这里我们直接入队。
                    byte[] payload = new byte[outPacketSize]; // 这一步很难完全避免，除非引入更复杂的封装
                    System.arraycopy(data, 0, payload, 0, outPacketSize);
                    mOutputDataQueue.offer(payload);

                    // 归还临时缓冲区到池
                    mBufferPool.offer(data);
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {}
            @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
        });

        mAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private void recordingLoop() {
        while (isRecording) {
            try {
                Integer inputBufferIndex = mInputBufferQueue.take();
                ByteBuffer inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    int readSize = mAudioRecord.read(inputBuffer, inputBuffer.capacity());
                    if (readSize > 0) {
                        mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, readSize, System.nanoTime() / 1000, 0);
                    } else {
                        mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, System.nanoTime() / 1000, 0);
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    // 优化：专门的 IO 写入线程，不阻塞编码回调
    private void writingLoop() {
        while (isRecording || !mOutputDataQueue.isEmpty()) {
            try {
                byte[] data = mOutputDataQueue.poll();
                if (data != null) {
                    if (mFileOutputStream != null) {
                        mFileOutputStream.write(data);
                    }
                } else {
                    Thread.sleep(10); // 队列空时稍作休息
                }
            } catch (Exception e) {
                Log.e(TAG, "写入异常", e);
            }
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        try {
            if (mAudioRecord != null) { mAudioRecord.stop(); mAudioRecord.release(); mAudioRecord = null; }
            if (mAudioEncoder != null) { mAudioEncoder.stop(); mAudioEncoder.release(); mAudioEncoder = null; }
            if (mFileOutputStream != null) { mFileOutputStream.flush(); mFileOutputStream.close(); mFileOutputStream = null; }
            mBtnStart.setEnabled(true);
            mBtnStop.setEnabled(false);
            mTvStatus.setText("状态: 已停止");
        } catch (IOException e) { Log.e(TAG, "停止失败", e); }
    }

    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2; int freqIdx = 4; int chanCfg = 2;
        packet[0] = (byte) 0xFF; packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        if (mExecutorService != null) mExecutorService.shutdownNow();
    }
}