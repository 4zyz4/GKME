package com.zyz4.gkme.input.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;
import android.util.Log;

import com.zyz4.gkme.input.usb.ControllerPacket;
import com.zyz4.gkme.input.usb.GkmeBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class AbstractDualSenseController extends AbstractController {
    private static final int HAPTIC_AUDIO_ENDPOINT_PACKET_SIZE = 0x188;
    private static final int HAPTIC_INIT_REPORT_SIZE = 48;
    private static final int HAPTIC_INIT_TIMEOUT_MS = 1000;
    private static final int REPORT_RIGHT_TRIGGER_TYPE_IDX = 11;
    private static final int REPORT_RIGHT_TRIGGER_DATA_IDX = 12;
    private static final int REPORT_LEFT_TRIGGER_TYPE_IDX = 22;
    private static final int REPORT_LEFT_TRIGGER_DATA_IDX = 23;
    private static final int TRIGGER_DATA_LEN = 10;
    private static final int REPORT_MIN_TRIGGER_LEN =
            REPORT_LEFT_TRIGGER_DATA_IDX + TRIGGER_DATA_LEN;
    private static final int TOUCHPAD_FINGER_COUNT = 2;

    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private Thread inputThread;
    private boolean stopped;
    private boolean advancedAudioHapticsRequested;
    private boolean advancedAudioHapticsPrimed;
    private byte cachedLeftTriggerType;
    private final byte[] cachedLeftTriggerData = new byte[TRIGGER_DATA_LEN];
    private byte cachedRightTriggerType;
    private final byte[] cachedRightTriggerData = new byte[TRIGGER_DATA_LEN];
    private DualSenseHapticSender advancedAudioHapticsSender;
    private final boolean[] activeTouchpadFingers = new boolean[TOUCHPAD_FINGER_COUNT];
    private final int[] activeTouchpadFingerIds = new int[TOUCHPAD_FINGER_COUNT];
    private final float[] activeTouchpadFingerX = new float[TOUCHPAD_FINGER_COUNT];
    private final float[] activeTouchpadFingerY = new float[TOUCHPAD_FINGER_COUNT];

    protected UsbEndpoint inEndpt, outEndpt;
    protected UsbInterface hapticIface;
    protected UsbEndpoint hapticEndpt;

    public AbstractDualSenseController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = GkmeBridge.LI_CTYPE_PS;
        this.capabilities = GkmeBridge.LI_CCAP_GYRO | GkmeBridge.LI_CCAP_ACCEL |
                GkmeBridge.LI_CCAP_RUMBLE | GkmeBridge.LI_CCAP_TOUCHPAD |
                GkmeBridge.LI_CCAP_RGB_LED;
        this.supportedButtonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                        ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                        ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                        ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                        ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG |
                        ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.TOUCHPAD_FLAG;
        this.buttonFlags = supportedButtonFlags;
    }

    private Thread createInputThread() {
        return new Thread() {
            public void run() {
                try {
                    // Delay for a moment before reporting the new gamepad and
                    // accepting new input. This allows time for the old InputDevice
                    // to go away before we reclaim its spot. If the old device is still
                    // around when we call notifyDeviceAdded(), we won't be able to claim
                    // the controller number used by the original InputDevice.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded();

                while (!isInterrupted() && !stopped) {
                    byte[] buffer = new byte[64];

                    int res;

                    //
                    // There's no way that I can tell to determine if a device has failed
                    // or if the timeout has simply expired. We'll check how long the transfer
                    // took to fail and assume the device failed if it happened before the timeout
                    // expired.
                    //

                    do {
                        // Read the next input state packet
                        long lastMillis = SystemClock.uptimeMillis();
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);

                        // If we get a zero length response, treat it as an error
                        if (res == 0) {
                            res = -1;
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            Log.d("DualSenseController", "Detected device I/O error");
                            AbstractDualSenseController.this.stop();
                            break;
                        }
                    } while (res == -1 && !isInterrupted() && !stopped);

                    if (res == -1 || stopped) {
                        break;
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        // Report input if handleRead() returns true
                        reportInput();
                        reportMotion();
                    }
                }
            }
        };
    }

    private static UsbInterface findInterface(UsbDevice device) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_HID && intf.getEndpointCount()>=2) {
                Log.d("DualSenseController", "Found HID interface: " + i);
                return intf;
            }
        }
        return null;
    }

    private void detectHapticEndpoint() {
        hapticIface = null;
        hapticEndpt = null;

        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO) {
                continue;
            }

            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint endpt = intf.getEndpoint(j);
                Log.i("DualSenseController", "Audio iface id=" + intf.getId() +
                        " alt=" + intf.getAlternateSetting() +
                        " ep=" + Integer.toHexString(endpt.getAddress()) +
                        " type=" + endpt.getType() +
                        " dir=" + endpt.getDirection() +
                        " maxPkt=" + endpt.getMaxPacketSize());
                if (endpt.getDirection() == UsbConstants.USB_DIR_OUT &&
                        endpt.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        endpt.getMaxPacketSize() == HAPTIC_AUDIO_ENDPOINT_PACKET_SIZE) {
                    hapticIface = intf;
                    hapticEndpt = endpt;
                    Log.i("DualSenseController", "Found advanced haptics endpoint iface=" +
                            intf.getId() + " alt=" + intf.getAlternateSetting() +
                            " addr=" + endpt.getAddress());
                    return;
                }
            }
        }
        Log.i("DualSenseController", "No advanced haptics endpoint found");
    }

    private List<UsbInterface> ifaces=new ArrayList<>();

    public boolean start() {
        ifaces.clear();
        advancedAudioHapticsRequested = false;
        advancedAudioHapticsPrimed = false;
        inEndpt = null;
        outEndpt = null;
        hapticIface = null;
        hapticEndpt = null;
        Log.d("DualSenseController", "start");
        // Force claim all interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            Log.d("DualSenseController", "Claiming interface " + i + " (class=" + iface.getInterfaceClass() + " alt=" + iface.getAlternateSetting() + ")");

            if (!connection.claimInterface(iface, true)) {
                Log.d("DualSenseController", "Failed to claim interfaces");
                return false;
            }else{
                ifaces.add(iface);
                Log.d("DualSenseController", "Claimed interface " + i + " new alt=" + iface.getAlternateSetting());
            }
        }
        Log.d("DualSenseController", "getInterfaceCount:" + device.getInterfaceCount());
        detectHapticEndpoint();
        if (hasAdvancedAudioHapticsSupport()) {
            capabilities |= GkmeBridge.LI_CCAP_HAPTIC_PCM;
        }

        // Find the endpoints
        UsbInterface iface = findInterface(device);

        if (iface == null) {
            Log.e("DualSenseController", "Failed to find interface");
            return false;
        }

        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    Log.d("DualSenseController", "Found duplicate OUT endpoint");
                    return false;
                }
                outEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    Log.d("DualSenseController", "Found duplicate IN endpoint");
                    return false;
                }
                inEndpt = endpt;
            }
        }
        Log.d("DualSenseController", "inEndpt: " + inEndpt);
        Log.d("DualSenseController", "outEndpt: " + outEndpt);
        // Make sure the required endpoints were present
        if (inEndpt == null || outEndpt == null) {
            Log.d("DualSenseController", "Missing required endpoin");
            return false;
        }
        // Run the init function
        if (!doInit()) {
            return false;
        }
        // Start listening for controller input
        inputThread = createInputThread();
        inputThread.start();
        return true;
    }

    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;
        cancelActiveTouchpadFingers();
        stopAdvancedAudioHaptics();

        // Cancel any rumble effects
        rumble((short)0, (short)0);

        // Stop the input thread
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }

        if(!ifaces.isEmpty()&&connection!=null){
            for (int i = 0; i < ifaces.size(); i++) {
                UsbInterface iface = ifaces.get(i);
                connection.releaseInterface(iface);
            }
            ifaces.clear();
        }

        // Close the USB connection
        connection.close();

        // Report the device removed
        notifyDeviceRemoved();
    }

    @Override
    public boolean hasAdvancedAudioHapticsSupport() {
        return hapticIface != null && hapticEndpt != null;
    }

    @Override
    public boolean startAdvancedAudioHaptics() {
        if (advancedAudioHapticsRequested && advancedAudioHapticsSender != null) {
            return true;
        }

        if (!hasAdvancedAudioHapticsSupport()) {
            Log.d("DualSenseController", "Advanced audio haptics endpoint not available (hapticIface=" + hapticIface + " hapticEndpt=" + hapticEndpt + ")");
            return false;
        }

        final int fd = connection.getFileDescriptor();
        if (fd < 0) {
            Log.w("DualSenseController", "Invalid USB file descriptor for advanced haptics (fd=" + fd + ")");
            return false;
        }

        Log.i("DualSenseController", "Attempting nativeConnectHaptics fd=" + fd + " ifaceId=" + getAdvancedAudioHapticsInterfaceId() + " alt=" + getAdvancedAudioHapticsAlternateSetting() + " ep=0x" + Integer.toHexString(getAdvancedAudioHapticsEndpointAddress() & 0xFF));
        
        boolean connected = HapticNative.nativeConnectHaptics(fd,
                getAdvancedAudioHapticsInterfaceId(),
                getAdvancedAudioHapticsAlternateSetting(),
                (byte) getAdvancedAudioHapticsEndpointAddress());
        Log.i("DualSenseController", "nativeConnectHaptics -> " + connected);
        if (!connected) {
            Log.w("DualSenseController", "nativeConnectHaptics failed — USB audio endpoint will not work. Check USB permissions and interface conflicts.");
            return false;
        }

        boolean enabled = HapticNative.nativeEnableHaptics();
        Log.i("DualSenseController", "nativeEnableHaptics -> " + enabled);
        if (!enabled) {
            HapticNative.nativeCleanupHaptics();
            Log.w("DualSenseController", "nativeEnableHaptics failed");
            return false;
        }

        advancedAudioHapticsSender = new DualSenseHapticSender();
        advancedAudioHapticsSender.start();
        advancedAudioHapticsRequested = true;
        advancedAudioHapticsPrimed = false;
        Log.i("DualSenseController", "Advanced audio haptics started successfully");
        onAdvancedAudioHapticsStarted();
        return true;
    }

    /** Called once the native haptics endpoint is live. Subclasses may re-apply audio config here. */
    protected void onAdvancedAudioHapticsStarted() {
    }

    @Override
    public void stopAdvancedAudioHaptics() {
        if (advancedAudioHapticsSender != null) {
            advancedAudioHapticsSender.stop();
            advancedAudioHapticsSender = null;
        }
        HapticNative.nativeCleanupHaptics();
        advancedAudioHapticsRequested = false;
        advancedAudioHapticsPrimed = false;
    }

    @Override
    public boolean isAdvancedAudioHapticsActive() {
        return advancedAudioHapticsRequested && hasAdvancedAudioHapticsSupport();
    }

    @Override
    public boolean submitAdvancedAudioHapticsFrame(byte[] frame, float intensityGain) {
        if (!isAdvancedAudioHapticsActive() || frame == null || frame.length == 0) {
            return false;
        }

        if (!advancedAudioHapticsPrimed && !tryPrimeAdvancedAudioHaptics()) {
            return false;
        }

        return advancedAudioHapticsSender != null &&
                advancedAudioHapticsSender.enqueue(frame, clampAdvancedAudioHapticsGain(intensityGain));
    }

    @Override
    public boolean submitNativeAudioHapticsFrame(byte[] frame) {
        if (frame == null || frame.length == 0 || frame.length > 3920 || frame.length % 8 != 0) {
            Log.w("DualSenseController", "submitNativeAudioHapticsFrame: invalid frame");
            return false;
        }
        if (!isAdvancedAudioHapticsActive() && !startAdvancedAudioHaptics()) {
            Log.w("DualSenseController", "submitNativeAudioHapticsFrame: startAdvancedAudioHaptics() failed");
            return false;
        }
        if (!advancedAudioHapticsPrimed && !tryPrimeAdvancedAudioHaptics()) {
            Log.w("DualSenseController", "submitNativeAudioHapticsFrame: tryPrimeAdvancedAudioHaptics() failed");
            return false;
        }
        if (advancedAudioHapticsSender == null) {
            Log.w("DualSenseController", "submitNativeAudioHapticsFrame: advancedAudioHapticsSender is null");
            return false;
        }
        return advancedAudioHapticsSender.enqueueNativePcm(frame);
    }

    public int getAdvancedAudioHapticsInterfaceId() {
        return hapticIface != null ? hapticIface.getId() : -1;
    }

    public int getAdvancedAudioHapticsAlternateSetting() {
        return hapticIface != null ? hapticIface.getAlternateSetting() : -1;
    }

    public int getAdvancedAudioHapticsEndpointAddress() {
        return hapticEndpt != null ? hapticEndpt.getAddress() : -1;
    }

    protected synchronized void invalidateAdvancedAudioHapticsPrime() {
        if (advancedAudioHapticsRequested) {
            advancedAudioHapticsPrimed = false;
        }
    }

    protected synchronized void updateAdvancedAudioHapticsTriggerCache(byte[] report) {
        if (report == null || report.length < REPORT_MIN_TRIGGER_LEN || report[0] != 0x02) {
            return;
        }

        int validFlags = report[1] & 0xFF;
        if ((validFlags & DualSenseOutputReport.ENABLE_RIGHT_TRIGGER) != 0) {
            cachedRightTriggerType = report[REPORT_RIGHT_TRIGGER_TYPE_IDX];
            System.arraycopy(report, REPORT_RIGHT_TRIGGER_DATA_IDX,
                    cachedRightTriggerData, 0, TRIGGER_DATA_LEN);
        }
        if ((validFlags & DualSenseOutputReport.ENABLE_LEFT_TRIGGER) != 0) {
            cachedLeftTriggerType = report[REPORT_LEFT_TRIGGER_TYPE_IDX];
            System.arraycopy(report, REPORT_LEFT_TRIGGER_DATA_IDX,
                    cachedLeftTriggerData, 0, TRIGGER_DATA_LEN);
        }
    }

    private boolean tryPrimeAdvancedAudioHaptics() {
        if (outEndpt == null) {
            Log.w("DualSenseController", "tryPrimeAdvancedAudioHaptics: outEndpt is null");
            return false;
        }

        byte[] initReport = new byte[HAPTIC_INIT_REPORT_SIZE];
        initReport[0] = 0x02;
        initReport[1] = 0x0C;
        initReport[2] = 0x40;

        synchronized (this) {
            initReport[REPORT_RIGHT_TRIGGER_TYPE_IDX] = cachedRightTriggerType;
            System.arraycopy(cachedRightTriggerData, 0,
                    initReport, REPORT_RIGHT_TRIGGER_DATA_IDX, TRIGGER_DATA_LEN);
            initReport[REPORT_LEFT_TRIGGER_TYPE_IDX] = cachedLeftTriggerType;
            System.arraycopy(cachedLeftTriggerData, 0,
                    initReport, REPORT_LEFT_TRIGGER_DATA_IDX, TRIGGER_DATA_LEN);
        }

        int res = connection.bulkTransfer(outEndpt, initReport, initReport.length, HAPTIC_INIT_TIMEOUT_MS);
        if (res == initReport.length) {
            advancedAudioHapticsPrimed = true;
            return true;
        }

        Log.w("DualSenseController", "Advanced haptics prime failed: " + res);
        return false;
    }

    private float clampAdvancedAudioHapticsGain(float gain) {
        if (Float.isNaN(gain) || Float.isInfinite(gain)) {
            return 0.5f;
        }
        if (gain < 0f) {
            return 0f;
        }
        if (gain > 2.75f) {
            return 2.75f;
        }
        return gain;
    }

    protected void updateTouchpadFinger(int fingerIndex, boolean active, int pointerId, float x, float y) {
        if (fingerIndex < 0 || fingerIndex >= TOUCHPAD_FINGER_COUNT) {
            return;
        }

        boolean wasActive = activeTouchpadFingers[fingerIndex];
        int previousPointerId = activeTouchpadFingerIds[fingerIndex];
        float previousX = activeTouchpadFingerX[fingerIndex];
        float previousY = activeTouchpadFingerY[fingerIndex];

        float normalizedX = clampUnitRange(x);
        float normalizedY = clampUnitRange(y);

        if (active) {
            if (!wasActive) {
                reportTouchpadEvent(GkmeBridge.LI_TOUCH_EVENT_DOWN, pointerId, normalizedX, normalizedY, 1.0f);
            }
            else if (previousPointerId != pointerId) {
                reportTouchpadEvent(GkmeBridge.LI_TOUCH_EVENT_UP, previousPointerId, previousX, previousY, 0.0f);
                reportTouchpadEvent(GkmeBridge.LI_TOUCH_EVENT_DOWN, pointerId, normalizedX, normalizedY, 1.0f);
            }
            else if (Float.compare(previousX, normalizedX) != 0 || Float.compare(previousY, normalizedY) != 0) {
                reportTouchpadEvent(GkmeBridge.LI_TOUCH_EVENT_MOVE, pointerId, normalizedX, normalizedY, 1.0f);
            }

            activeTouchpadFingers[fingerIndex] = true;
            activeTouchpadFingerIds[fingerIndex] = pointerId;
            activeTouchpadFingerX[fingerIndex] = normalizedX;
            activeTouchpadFingerY[fingerIndex] = normalizedY;
        }
        else if (wasActive) {
            reportTouchpadEvent(GkmeBridge.LI_TOUCH_EVENT_UP, previousPointerId, previousX, previousY, 0.0f);
            activeTouchpadFingers[fingerIndex] = false;
        }
    }

    protected void cancelActiveTouchpadFingers() {
        boolean hasActiveTouch = false;
        for (boolean activeTouchpadFinger : activeTouchpadFingers) {
            if (activeTouchpadFinger) {
                hasActiveTouch = true;
                break;
            }
        }

        if (hasActiveTouch) {
            reportTouchpadEvent(GkmeBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, 0.0f, 0.0f, 0.0f);
        }

        Arrays.fill(activeTouchpadFingers, false);
    }

    protected float normalizeTouchCoordinate(int rawValue, float range) {
        return clampUnitRange(rawValue / range);
    }

    private float clampUnitRange(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }

    protected abstract boolean handleRead(ByteBuffer buffer);
    protected abstract boolean doInit();
}
