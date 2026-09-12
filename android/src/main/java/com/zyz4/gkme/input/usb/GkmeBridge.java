package com.zyz4.gkme.input.usb;

/** Controller type/capability/motion constants used by the Axixi2233 USB driver. */
public class GkmeBridge {
    public static final byte LI_TOUCH_EVENT_HOVER = 0x00;
    public static final byte LI_TOUCH_EVENT_DOWN = 0x01;
    public static final byte LI_TOUCH_EVENT_UP = 0x02;
    public static final byte LI_TOUCH_EVENT_MOVE = 0x03;
    public static final byte LI_TOUCH_EVENT_CANCEL = 0x04;
    public static final byte LI_TOUCH_EVENT_BUTTON_ONLY = 0x05;
    public static final byte LI_TOUCH_EVENT_HOVER_LEAVE = 0x06;
    public static final byte LI_TOUCH_EVENT_CANCEL_ALL = 0x07;

    public static final byte LI_CTYPE_UNKNOWN = 0x00;
    public static final byte LI_CTYPE_XBOX = 0x01;
    public static final byte LI_CTYPE_PS = 0x02;
    public static final byte LI_CTYPE_NINTENDO = 0x03;

    public static final short LI_CCAP_ANALOG_TRIGGERS = 0x01;
    public static final short LI_CCAP_RUMBLE = 0x02;
    public static final short LI_CCAP_TRIGGER_RUMBLE = 0x04;
    public static final short LI_CCAP_TOUCHPAD = 0x08;
    public static final short LI_CCAP_ACCEL = 0x10;
    public static final short LI_CCAP_GYRO = 0x20;
    public static final short LI_CCAP_BATTERY_STATE = 0x40;
    public static final short LI_CCAP_RGB_LED = 0x80;
    public static final short LI_CCAP_DUAL_TOUCHPAD = 0x100;
    public static final short LI_CCAP_HAPTIC_PCM = 0x200;

    public static final byte LI_MOTION_TYPE_ACCEL = 0x01;
    public static final byte LI_MOTION_TYPE_GYRO = 0x02;
}
