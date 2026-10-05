package com.zyz4.gkme.input.usb;

public interface UsbDriverListener {
    void reportControllerState(int controllerId, int buttonFlags,
                               float leftStickX, float leftStickY,
                               float rightStickX, float rightStickY,
                               float leftTrigger, float rightTrigger);
    void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ);
    void reportControllerTouchpadEvent(int controllerId, byte eventType, int pointerId,
                                       float x, float y, float pressure);
    void deviceRemoved(AbstractController controller);
    void deviceAdded(AbstractController controller);

    /** Reports a battery state/percentage update from a transport-neutral controller. */
    default void reportControllerBattery(int controllerId, byte batteryState, byte batteryPercentage) {
    }

    /** True when arrival metadata has been accepted and the driver may emit stateful transitions. */
    default boolean isControllerReady(int controllerId) {
        return true;
    }
}
