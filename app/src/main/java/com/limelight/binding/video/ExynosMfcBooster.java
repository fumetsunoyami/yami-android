package com.limelight.binding.video;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.limelight.LimeLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Exynos MFC Booster & Screen Recorder Simulation (Yami).
 *
 * Implements 4 modes:
 * - off: Disabled
 * - dummy_display: VirtualDisplay via DisplayManager (No system permission required)
 * - simulated_projection: System-level screen recording simulation via MediaProjection
 *                         (Triggers Samsung One UI native screen recording governor, 0 byte storage)
 * - real_record: Actual screen recording with user-configurable resolution, saved to Movies/Yami
 */
public class ExynosMfcBooster {
    public static final String MODE_OFF = "off";
    public static final String MODE_DUMMY_DISPLAY = "dummy_display";
    public static final String MODE_SIMULATED_PROJECTION = "simulated_projection";
    public static final String MODE_REAL_RECORD = "real_record";

    public static final String RES_NATIVE = "native";
    public static final String RES_1080P = "1080p";
    public static final String RES_720P = "720p";

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int DUMMY_WIDTH = 1280;
    private static final int DUMMY_HEIGHT = 720;
    private static final int DUMMY_BITRATE = 8_000_000; // 8 Mbps to trigger Exynos high-frequency MFC state
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
    private String currentResolution = RES_NATIVE;

    private Context appContext;

    /**
     * Start the booster. If mode requires MediaProjection (simulated_projection or real_record),
     * it will wait for MediaProjectionService or start with the active projection if available.
     */
    public synchronized void start(Context context, String mode, String resolution) {
        if (mode == null || mode.equals(MODE_OFF)) {
            stop();
            return;
        }

        this.appContext = context.getApplicationContext();
        this.currentResolution = (resolution != null) ? resolution : RES_NATIVE;

        if (isRunning && mode.equals(currentMode)) {
            LimeLog.info("ExynosMfcBooster: Already active in mode: " + mode);
            return;
        }

        stop();

        LimeLog.info("ExynosMfcBooster: Starting with mode='" + mode + "', resolution='" + currentResolution + "'");
        currentMode = mode;

        try {
            switch (mode) {
                case MODE_DUMMY_DISPLAY:
                    startDummyDisplay(context);
                    break;
                case MODE_SIMULATED_PROJECTION:
                    startSimulatedProjection(context);
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
            LimeLog.severe("ExynosMfcBooster: Start failed (" + mode + "): " + e.getMessage());
            e.printStackTrace();
            stop();
        }
    }

    /**
     * Mode 2: Encoder + VirtualDisplay (Internal, no system permission required).
     */
    private void startDummyDisplay(Context context) throws Exception {
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
            LimeLog.info("ExynosMfcBooster: Internal VirtualDisplay created (" + DUMMY_WIDTH + "x" + DUMMY_HEIGHT + ")");
        }

        startDrainThread(false);
    }

    /**
     * Mode 3: Simulated Screen Recording using MediaProjection.
     * Triggers Samsung One UI's native screen recording governor profile for maximum MFC clock,
     * while discarding all output buffers (0 byte storage overhead).
     */
    private void startSimulatedProjection(Context context) throws Exception {
        MediaProjection projection = MediaProjectionService.getActiveMediaProjection();
        if (projection == null) {
            LimeLog.info("ExynosMfcBooster: Awaiting MediaProjection for simulated_projection");
            MediaProjectionService.setCallback(new MediaProjectionService.MediaProjectionCallback() {
                @Override
                public void onProjectionReady(MediaProjection p) {
                    synchronized (ExynosMfcBooster.this) {
                        if (currentMode.equals(MODE_SIMULATED_PROJECTION) && !isRunning) {
                            try {
                                initSimulatedProjection(context, p);
                            } catch (Exception e) {
                                LimeLog.severe("ExynosMfcBooster: Deferred projection init failed: " + e.getMessage());
                                stop();
                            }
                        }
                    }
                }

                @Override
                public void onProjectionStopped() {
                    synchronized (ExynosMfcBooster.this) {
                        if (currentMode.equals(MODE_SIMULATED_PROJECTION)) {
                            stop();
                        }
                    }
                }
            });
            return;
        }

        initSimulatedProjection(context, projection);
    }

    private void initSimulatedProjection(Context context, MediaProjection projection) throws Exception {
        Point size = resolveResolution(context, currentResolution);
        int simWidth = size.x;
        int simHeight = size.y;
        int simBitrate = (simWidth >= 1920) ? 12_000_000 : 8_000_000;

        setupEncoder(simWidth, simHeight, simBitrate, 60, 120);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int dpi = metrics.densityDpi;

        virtualDisplay = projection.createVirtualDisplay(
                "YamiSimulatedScreenRecord",
                simWidth,
                simHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
        );

        LimeLog.info("ExynosMfcBooster: MediaProjection VirtualDisplay created (Auto-mirror active, " + simWidth + "x" + simHeight + ")");
        startDrainThread(false);
    }

    /**
     * Mode 4: Real Recording with configurable resolution (native, 1080p, 720p).
     * Saves MP4 file to /Movies/Yami.
     */
    private void startRealRecord(Context context) throws Exception {
        MediaProjection projection = MediaProjectionService.getActiveMediaProjection();
        if (projection == null) {
            LimeLog.info("ExynosMfcBooster: Awaiting MediaProjection for real_record");
            MediaProjectionService.setCallback(new MediaProjectionService.MediaProjectionCallback() {
                @Override
                public void onProjectionReady(MediaProjection p) {
                    synchronized (ExynosMfcBooster.this) {
                        if (currentMode.equals(MODE_REAL_RECORD) && !isRunning) {
                            try {
                                initRealRecord(context, p);
                            } catch (Exception e) {
                                LimeLog.severe("ExynosMfcBooster: Deferred real record init failed: " + e.getMessage());
                                stop();
                            }
                        }
                    }
                }

                @Override
                public void onProjectionStopped() {
                    synchronized (ExynosMfcBooster.this) {
                        if (currentMode.equals(MODE_REAL_RECORD)) {
                            stop();
                        }
                    }
                }
            });
            return;
        }

        initRealRecord(context, projection);
    }

    private void initRealRecord(Context context, MediaProjection projection) throws Exception {
        Point size = resolveResolution(context, currentResolution);
        int recWidth = size.x;
        int recHeight = size.y;
        int recBitrate = (recWidth >= 1920) ? 15_000_000 : 8_000_000;

        File movieDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Yami");
        if (!movieDir.exists()) {
            if (!movieDir.mkdirs()) {
                File fallback = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (fallback != null) {
                    movieDir = new File(fallback, "Yami");
                    movieDir.mkdirs();
                }
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outputFile = new File(movieDir, "Yami_Record_" + timeStamp + ".mp4");
        LimeLog.info("ExynosMfcBooster: Recording to: " + outputFile.getAbsolutePath() + " (" + recWidth + "x" + recHeight + ")");

        mediaMuxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerStarted = false;
        videoTrackIndex = -1;

        setupEncoder(recWidth, recHeight, recBitrate, 60, 120);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int dpi = metrics.densityDpi;

        virtualDisplay = projection.createVirtualDisplay(
                "YamiRealScreenRecord",
                recWidth,
                recHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
        );

        startDrainThread(true);
    }

    /**
     * Compute recording resolution based on preference.
     */
    private Point resolveResolution(Context context, String resolutionPref) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point realSize = new Point();
        display.getRealSize(realSize);

        int screenW = Math.max(realSize.x, realSize.y);
        int screenH = Math.min(realSize.x, realSize.y);

        if (RES_720P.equalsIgnoreCase(resolutionPref)) {
            return new Point(1280, 720);
        } else if (RES_1080P.equalsIgnoreCase(resolutionPref)) {
            return new Point(1920, 1080);
        } else {
            // Native: cap at 1920x1080 (max level supported by mobile AVC encoder) to prevent codec crash
            if (screenW > 1920 || screenH > 1080) {
                float aspect = (float) screenW / (float) screenH;
                int maxW = 1920;
                int maxH = (int) (maxW / aspect);
                maxH = (maxH / 2) * 2;
                return new Point(maxW, maxH);
            }
            int w = (screenW / 2) * 2;
            int h = (screenH / 2) * 2;
            return new Point(w, h);
        }
    }

    /**
     * Setup MediaCodec video encoder.
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
        LimeLog.info("ExynosMfcBooster: Hardware encoder started (" + width + "x" + height + " @ " + bitrate + "bps)");
    }

    /**
     * Start background drain thread.
     * @param writeToMuxer true if writing MP4 to MediaMuxer, false if discarding buffers immediately.
     */
    private void startDrainThread(boolean writeToMuxer) {
        isRunning = true;
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            LimeLog.info("ExynosMfcBooster: Drain thread started (writeToMuxer=" + writeToMuxer + ")");

            while (isRunning) {
                try {
                    MediaCodec codec = encoder;
                    if (codec == null) break;

                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (writeToMuxer && !muxerStarted && mediaMuxer != null) {
                            MediaFormat newFormat = codec.getOutputFormat();
                            videoTrackIndex = mediaMuxer.addTrack(newFormat);
                            mediaMuxer.start();
                            muxerStarted = true;
                            LimeLog.info("ExynosMfcBooster: MediaMuxer started track " + videoTrackIndex);
                        }
                    } else if (outputIndex >= 0) {
                        if (writeToMuxer && muxerStarted && mediaMuxer != null && bufferInfo.size > 0) {
                            ByteBuffer encodedData = codec.getOutputBuffer(outputIndex);
                            if (encodedData != null && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
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
                    LimeLog.warning("ExynosMfcBooster: Drain thread exception: " + e.getMessage());
                    break;
                }
            }
            LimeLog.info("ExynosMfcBooster: Drain thread terminated");
        }, "ExynosMfcDrainThread");
        drainThread.start();
    }

    /**
     * Stop booster and clean up all hardware and service resources.
     */
    public synchronized void stop() {
        if (!isRunning && currentMode.equals(MODE_OFF)) {
            return;
        }

        LimeLog.info("ExynosMfcBooster: Stopping booster...");
        isRunning = false;

        MediaProjectionService.setCallback(null);

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
                LimeLog.warning("ExynosMfcBooster: Error releasing VirtualDisplay: " + e.getMessage());
            }
            virtualDisplay = null;
        }

        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Exception e) {
                LimeLog.warning("ExynosMfcBooster: Error releasing inputSurface: " + e.getMessage());
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

        if (appContext != null) {
            try {
                appContext.stopService(new Intent(appContext, MediaProjectionService.class));
            } catch (Exception ignored) {}
        }

        currentMode = MODE_OFF;
        LimeLog.info("ExynosMfcBooster: Stopped completely");
    }

    public synchronized boolean isRunning() {
        return isRunning;
    }

    public synchronized String getCurrentMode() {
        return currentMode;
    }
}
