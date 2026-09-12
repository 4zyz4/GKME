package com.zyz4.gkme.input.usb;

import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class DualSenseHapticSender {
    private static final String TAG = "DS5Haptics";
    // Deep enough to absorb bursty audio delivery without evicting in-flight frames.
    private static final int QUEUE_CAPACITY = 64;
    private static final int DIRECT_BUFFER_SIZE = 4096;
    private static final long STATS_INTERVAL_MS = 2000L;
    // 10 ms of 48 kHz 4-channel S16LE silence. The PC drops all-zero windows, so
    // the worker fills idle gaps with this to keep the isochronous endpoint fed.
    private static final int SILENCE_FRAME_BYTES = 480 * 8;

    private static final class HapticFrame {
        final byte[] data;
        final float gain;
        final boolean nativePcm;

        HapticFrame(byte[] data, float gain, boolean nativePcm) {
            this.data = data;
            this.gain = gain;
            this.nativePcm = nativePcm;
        }
    }

    private final BlockingQueue<HapticFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY, false);
    private volatile boolean running;
    private Thread worker;
    private final AtomicLong enqueuedNativePcmFrames = new AtomicLong();
    private final AtomicLong droppedNativePcmFrames = new AtomicLong();

    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        queue.clear();
        enqueuedNativePcmFrames.set(0);
        droppedNativePcmFrames.set(0);

        worker = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(DIRECT_BUFFER_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer silenceBuffer = ByteBuffer.allocateDirect(SILENCE_FRAME_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            boolean nativeMode = false;
            long statsStartedMs = SystemClock.uptimeMillis();
            long sentNativePcmFrames = 0;
            long failedNativePcmFrames = 0;
            long sentNativePcmBytes = 0;
            while (running) {
                try {
                    HapticFrame frame;
                    if (nativeMode) {
                        // The PC drops all-zero windows, so idle gaps must be filled
                        // locally to keep the isochronous endpoint from underrunning.
                        frame = queue.poll(1, TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            HapticNative.nativeSendNativeHapticPcm(silenceBuffer, SILENCE_FRAME_BYTES);
                            continue;
                        }
                    } else {
                        frame = queue.take();
                    }
                    if (frame == null || frame.data == null || frame.data.length == 0 ||
                            frame.data.length > DIRECT_BUFFER_SIZE) {
                        continue;
                    }

                    directBuffer.clear();
                    directBuffer.put(frame.data);
                    directBuffer.flip();
                    if (frame.nativePcm) {
                        nativeMode = true;
                        if (HapticNative.nativeSendNativeHapticPcm(directBuffer, frame.data.length)) {
                            sentNativePcmFrames++;
                            sentNativePcmBytes += frame.data.length;
                        }
                        else {
                            failedNativePcmFrames++;
                        }

                        long nowMs = SystemClock.uptimeMillis();
                        if (nowMs - statsStartedMs >= STATS_INTERVAL_MS) {
                            long enqueued = enqueuedNativePcmFrames.getAndSet(0);
                            long dropped = droppedNativePcmFrames.getAndSet(0);
                            Log.i(TAG, "Native PCM USB stats: queued=" + enqueued +
                                    " sent=" + sentNativePcmFrames +
                                    " failed=" + failedNativePcmFrames +
                                    " dropped=" + dropped +
                                    " bytes=" + sentNativePcmBytes);
                            sentNativePcmFrames = 0;
                            failedNativePcmFrames = 0;
                            sentNativePcmBytes = 0;
                            statsStartedMs = nowMs;
                        }
                    }
                    else {
                        HapticNative.nativeSendHapticFeedback(directBuffer, frame.data.length, frame.gain);
                    }
                }
                catch (InterruptedException ignored) {
                    break;
                }
            }

            queue.clear();
        }, "DualSense-Haptics");

        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        queue.clear();
    }

    public boolean enqueue(byte[] frame, float intensityGain) {
        if (!running || frame == null || frame.length == 0) {
            return false;
        }

        return enqueueFrame(new HapticFrame(frame, intensityGain, false));
    }

    public boolean enqueueNativePcm(byte[] frame) {
        if (!running || frame == null || frame.length == 0 || frame.length > DIRECT_BUFFER_SIZE) {
            return false;
        }

        boolean enqueued = enqueueFrame(new HapticFrame(frame, 1.0f, true));
        if (enqueued) {
            enqueuedNativePcmFrames.incrementAndGet();
        }
        return enqueued;
    }

    private boolean enqueueFrame(HapticFrame hapticFrame) {
        if (queue.offer(hapticFrame)) {
            return true;
        }

        HapticFrame droppedFrame = queue.poll();
        if (droppedFrame != null && droppedFrame.nativePcm) {
            droppedNativePcmFrames.incrementAndGet();
        }
        return queue.offer(hapticFrame);
    }
}
