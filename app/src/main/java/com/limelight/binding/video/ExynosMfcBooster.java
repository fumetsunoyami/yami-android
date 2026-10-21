package com.limelight.binding.video;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.view.Surface;

import com.limelight.LimeLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Exynos MFC (Multi-Format Codec) Booster & Screen Recorder Simulation.
 *
 * Forces Samsung Exynos hardware decoder and SurfaceFlinger pipeline to run at
 * maximum operating frequency and lowest latency by emulating/executing video recording.
 */
public class ExynosMfcBooster {
    public static final String MODE_OFF = "off";
    public static final String MODE_DUMMY_ENCODER = "dummy_encoder";
    public static final String MODE_DUMMY_VIRTUAL_DISPLAY = "dummy_virtual_display";
    public static final String MODE_REAL_RECORD = "real_record";

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // "video/avc"
    private static final int DUMMY_WIDTH = 176;
    private static final int DUMMY_HEIGHT = 144;
    private static final int DUMMY_BITRATE = 100_000; // 100 kbps
    private static final int DUMMY_FPS = 60;
    private static final int DUMMY_OPERATING_RATE = 120;
    private static final int DENSITY_DPI = 160;

    private MediaCodec encoder;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private MediaMuxer mediaMuxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;

    private Thread drainThread;
    private volatile boolean isRunning = false;
    private String currentMode = MODE_OFF;

    /**
     * Start MFC Booster with specified mode.
     *
     * @param context Android context
     * @param mode    Booster mode ("off", "dummy_encoder", "dummy_virtual_display", "real_record")
     */
    public synchronized void start(Context context, String mode) {
        if (mode == null || mode.equals(MODE_OFF)) {
            stop();
            return;
        }

        if (isRunning && mode.equals(currentMode)) {
            LimeLog.info("ExynosMfcBooster: Already active in mode: " + mode);
            return;
        }

        stop();

        LimeLog.info("ExynosMfcBooster: Starting in mode '" + mode + "'");
        currentMode = mode;

        try {
            switch (mode) {
                case MODE_DUMMY_ENCODER:
                    startDummyEncoder();
                    break;
                case MODE_DUMMY_VIRTUAL_DISPLAY:
                    startDummyVirtualDisplay(context);
                    break;
                case MODE_REAL_RECORD:
                    startRealRecord(context);
                    break;
                default:
                    LimeLog.warning("ExynosMfcBooster: Unknown mode: " + mode);
                    stop();
                    break;
            }
        } catch (Exception e) {
            LimeLog.severe("ExynosMfcBooster: Failed to start (" + mode + "): " + e.getMessage());
            e.printStackTrace();
            stop();
        }
    }

    /**
     * Configure and start hardware video encoder.
     */
    private void setupEncoder(int width, int height, int bitrate, int fps, int operatingRate) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate);
        }

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();
        LimeLog.info("ExynosMfcBooster: AVC hardware encoder started (" + width + "x" + height + ")");
    }

    /**
     * Level 1: Dummy Hardware Encoder.
     * Renders a single black frame and drops first output buffer, staying in Executing state.
     */
    private void startDummyEncoder() throws Exception {
        setupEncoder(DUMMY_WIDTH, DUMMY_HEIGHT, DUMMY_BITRATE, DUMMY_FPS, DUMMY_OPERATING_RATE);

        // Draw exactly 1 black frame to trigger the hardware pipeline
        Canvas canvas = null;
        try {
            canvas = inputSurface.lockCanvas(null);
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
            }
        } finally {
            if (canvas != null) {
                inputSurface.unlockCanvasAndPost(canvas);
            }
        }

        // Dequeue and discard the first output buffer
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        for (int i = 0; i < 20; i++) {
            int index = encoder.dequeueOutputBuffer(bufferInfo, 50_000);
            if (index >= 0) {
                encoder.releaseOutputBuffer(index, false);
                LimeLog.info("ExynosMfcBooster: Level 1 initial buffer released (" + index + ")");
                break;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                LimeLog.info("ExynosMfcBooster: Level 1 encoder format: " + encoder.getOutputFormat());
            }
        }

        isRunning = true;
        LimeLog.info("ExynosMfcBooster: Level 1 (Dummy Hardware Encoder) active");
    }

    /**
     * Level 2: Encoder + VirtualDisplay.
     * VirtualDisplay sends frames to encoder; background loop constantly drains buffers without file I/O.
     */
    private void startDummyVirtualDisplay(Context context) throws Exception {
        setupEncoder(DUMMY_WIDTH, DUMMY_HEIGHT, DUMMY_BITRATE, DUMMY_FPS, DUMMY_OPERATING_RATE);

        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            }
            virtualDisplay = dm.createVirtualDisplay(
                    "YamiExynosBoosterDisplay",
                    DUMMY_WIDTH,
                    DUMMY_HEIGHT,
                    DENSITY_DPI,
                    inputSurface,
                    flags
            );
            LimeLog.info("ExynosMfcBooster: VirtualDisplay created (" + DUMMY_WIDTH + "x" + DUMMY_HEIGHT + ")");
        } else {
            LimeLog.warning("ExynosMfcBooster: DisplayManager unavailable");
        }

        isRunning = true;

        // Drain background thread to release all output buffers without writing to disk
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            LimeLog.info("ExynosMfcBooster: Level 2 drain loop started");
            while (isRunning) {
                try {
                    MediaCodec codec = encoder;
                    if (codec == null) break;

                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000);
                    if (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                } catch (IllegalStateException e) {
                    // Codec stopped or released
                    break;
                } catch (Exception e) {
                    LimeLog.warning("ExynosMfcBooster: Drain loop error: " + e.getMessage());
                    break;
                }
            }
            LimeLog.info("ExynosMfcBooster: Level 2 drain loop finished");
        }, "ExynosMfcDrainThread");
        drainThread.start();
    }

    /**
     * Real Record: Record frames into MP4 file in Movies/Artemis using MediaMuxer.
     */
    private void startRealRecord(Context context) throws Exception {
        File movieDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Artemis");
        if (!movieDir.exists()) {
            if (!movieDir.mkdirs()) {
                File fallback = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (fallback != null) {
                    movieDir = new File(fallback, "Artemis");
                    movieDir.mkdirs();
                }
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outputFile = new File(movieDir, "Artemis_Record_" + timeStamp + ".mp4");
        LimeLog.info("ExynosMfcBooster: Output MP4 file: " + outputFile.getAbsolutePath());

        mediaMuxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerStarted = false;
        videoTrackIndex = -1;

        setupEncoder(DUMMY_WIDTH, DUMMY_HEIGHT, 2_000_000, 60, 120);

        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            }
            virtualDisplay = dm.createVirtualDisplay(
                    "YamiExynosRecordDisplay",
                    DUMMY_WIDTH,
                    DUMMY_HEIGHT,
                    DENSITY_DPI,
                    inputSurface,
                    flags
            );
        }

        isRunning = true;

        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            LimeLog.info("ExynosMfcBooster: Real record muxing thread started");
            while (isRunning) {
                try {
                    MediaCodec codec = encoder;
                    if (codec == null) break;

                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted && mediaMuxer != null) {
                            MediaFormat newFormat = codec.getOutputFormat();
                            videoTrackIndex = mediaMuxer.addTrack(newFormat);
                            mediaMuxer.start();
                            muxerStarted = true;
                            LimeLog.info("ExynosMfcBooster: MediaMuxer started with track " + videoTrackIndex);
                        }
                    } else if (outputIndex >= 0) {
                        ByteBuffer encodedData = codec.getOutputBuffer(outputIndex);
                        if (encodedData != null && muxerStarted && bufferInfo.size > 0) {
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                encodedData.position(bufferInfo.offset);
                                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                } catch (IllegalStateException e) {
                    break;
                } catch (Exception e) {
                    LimeLog.warning("ExynosMfcBooster: Recording error: " + e.getMessage());
                    break;
                }
            }
            LimeLog.info("ExynosMfcBooster: Real record muxing thread terminated");
        }, "ExynosMfcRecordThread");
        drainThread.start();
    }

    /**
     * Stop and safely release all hardware and muxer resources.
     */
    public synchronized void stop() {
        if (!isRunning && currentMode.equals(MODE_OFF)) {
            return;
        }

        LimeLog.info("ExynosMfcBooster: Stopping booster and cleaning up resources...");
        isRunning = false;

        if (drainThread != null) {
            try {
                drainThread.join(500);
            } catch (InterruptedException ignored) {}
            drainThread = null;
        }

        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception e) {
                LimeLog.warning("ExynosMfcBooster: VirtualDisplay release error: " + e.getMessage());
            }
            virtualDisplay = null;
        }

        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Exception e) {
                LimeLog.warning("ExynosMfcBooster: inputSurface release error: " + e.getMessage());
            }
            inputSurface = null;
        }

        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {}
            try {
                encoder.release();
            } catch (Exception ignored) {}
            encoder = null;
        }

        if (mediaMuxer != null) {
            try {
                if (muxerStarted) {
                    mediaMuxer.stop();
                }
            } catch (Exception ignored) {}
            try {
                mediaMuxer.release();
            } catch (Exception ignored) {}
            mediaMuxer = null;
            muxerStarted = false;
        }

        currentMode = MODE_OFF;
        LimeLog.info("ExynosMfcBooster: Stopped successfully");
    }

    /**
     * Check whether booster is actively running.
     */
    public synchronized boolean isRunning() {
        return isRunning;
    }

    /**
     * Get active mode.
     */
    public synchronized String getCurrentMode() {
        return currentMode;
    }
}
