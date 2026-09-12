package com.zyz4.gkme.input.usb;

import com.zyz4.gkme.input.usb.GkmeBridge;

public abstract class AbstractController {

    private final int deviceId;
    private final int vendorId;
    private final int productId;

    private UsbDriverListener listener;

    protected int buttonFlags, supportedButtonFlags;
    protected float leftTrigger, rightTrigger;
    protected float rightStickX, rightStickY;
    protected float leftStickX, leftStickY;
    protected short capabilities;
    protected byte type;
    protected float gyroX, gyroY, gyroZ;
    protected float accelX, accelY, accelZ;
    public int getControllerId() {
        return deviceId;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public int getSupportedButtonFlags() {
        return supportedButtonFlags;
    }

    public short getCapabilities() {
        return capabilities;
    }

    public byte getType() {
        return type;
    }

    protected void setButtonFlag(int buttonFlag, int data) {
        if (data != 0) {
            buttonFlags |= buttonFlag;
        }
        else {
            buttonFlags &= ~buttonFlag;
        }
    }

    protected void reportInput() {
        listener.reportControllerState(deviceId, buttonFlags, leftStickX, leftStickY,
                rightStickX, rightStickY, leftTrigger, rightTrigger);
    }

    public abstract boolean start();
    public abstract void stop();

    // New method to report motion events
    protected void reportMotion() {
        listener.reportControllerMotion(deviceId, GkmeBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ);
        listener.reportControllerMotion(deviceId, GkmeBridge.LI_MOTION_TYPE_ACCEL, accelX, accelY, accelZ);
    }

    protected void reportTouchpadEvent(byte eventType, int pointerId, float x, float y, float pressure) {
        listener.reportControllerTouchpadEvent(deviceId, eventType, pointerId, x, y, pressure);
    }

    public AbstractController(int deviceId, UsbDriverListener listener, int vendorId, int productId) {
        this.deviceId = deviceId;
        this.listener = listener;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    public abstract void rumble(short lowFreqMotor, short highFreqMotor);

    public abstract void rumbleTriggers(short leftTrigger, short rightTrigger);

    /**
     * Applies native adaptive-trigger effects when supported by this controller.
     *
     * @param eventFlags Bitmask identifying the triggers to update.
     * @param typeLeft Left trigger effect type.
     * @param typeRight Right trigger effect type.
     * @param left Left trigger effect payload.
     * @param right Right trigger effect payload.
     */
    public void setAdaptiveTriggerEffects(byte eventFlags, byte typeLeft, byte typeRight,
                                          byte[] left, byte[] right) {
    }

    /**
     * Sets the controller RGB LED when supported.
     *
     * @param red Red component.
     * @param green Green component.
     * @param blue Blue component.
     */
    public void setControllerLED(byte red, byte green, byte blue) {
    }

    /**
     * Sets the controller player indicator LEDs when supported.
     *
     * @param playerIndicator Native controller LED bitmask.
     */
    public void setPlayerIndicator(byte playerIndicator) {
    }

    public boolean hasAdvancedAudioHapticsSupport() {
        return false;
    }

    public boolean startAdvancedAudioHaptics() {
        return false;
    }

    public void stopAdvancedAudioHaptics() {
    }

    public boolean isAdvancedAudioHapticsActive() {
        return false;
    }

    public boolean submitAdvancedAudioHapticsFrame(byte[] frame, float intensityGain) {
        return false;
    }

    /**
     * Submits authored controller PCM without resampling or channel remapping.
     *
     * @param frame Interleaved four-channel, 48 kHz, signed 16-bit little-endian PCM.
     * @return true when queued for the controller USB audio endpoint.
     */
    public boolean submitNativeAudioHapticsFrame(byte[] frame) {
        return false;
    }

    protected void notifyDeviceRemoved() {
        listener.deviceRemoved(this);
    }

    protected void notifyDeviceAdded() {
        listener.deviceAdded(this);
    }

    public void sendCommand(byte[] data){};
}
