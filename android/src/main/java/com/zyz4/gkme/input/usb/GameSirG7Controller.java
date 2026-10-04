package com.zyz4.gkme.input.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GameSir G7 / G7 SE USB driver, ported from the upstream axi_usb_gamepad 1.0.6
 * driver (cn.axi.gamepad.usb.driver.GameSirG7Controller).
 */
public class GameSirG7Controller extends AbstractDualSenseController {
    private static final String TAG = "GameSirG7Controller";

    private static final int[] SUPPORTED_VENDORS = {
            0x3537, // GameSir
    };
    private static final int[] SUPPORTED_PRODUCTS = {
            0x1022,
            0x10B8,
    };

    private static final byte PACKET_HEADER = (byte) 0xA1;
    private static final byte PACKET_HEADER_TAIL = (byte) 0xC8;
    private static final byte CMD_MODE_SWITCH = (byte) 0xA2;
    private static final byte CMD_RUMBLE = (byte) 0xA2;

    public static boolean canClaimDevice(UsbDevice device) {
        for (int supportedVid : SUPPORTED_VENDORS) {
            for (int supportedPid : SUPPORTED_PRODUCTS) {
                if (device.getVendorId() == supportedVid &&
                        device.getProductId() == supportedPid &&
                        device.getInterfaceCount() >= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    public GameSirG7Controller(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
    }

    @Override
    public boolean hasPaddleSupport() {
        return true;
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        int len = buffer.remaining();
        if (len == 9) {
            return handleStandardPacket(buffer);
        }
        if (buffer.remaining() < 37) {
            return false;
        }

        byte first = buffer.get();
        if (first == 0x43) {
            byte h0 = buffer.get();
            byte h1 = buffer.get();
            if (h0 != PACKET_HEADER || h1 != PACKET_HEADER_TAIL) {
                return false;
            }
        } else if (first == PACKET_HEADER) {
            if (buffer.get() != PACKET_HEADER_TAIL) {
                return false;
            }
        } else {
            return false;
        }

        parseStatePacket(buffer);
        return true;
    }

    private void parseStatePacket(ByteBuffer data) {
        data.order(ByteOrder.BIG_ENDIAN);

        int b1 = data.get() & 0xFF;
        setButtonFlag(ControllerPacket.A_FLAG, b1 & 0x01);
        setButtonFlag(ControllerPacket.B_FLAG, b1 & 0x02);
        setButtonFlag(ControllerPacket.X_FLAG, b1 & 0x08);
        setButtonFlag(ControllerPacket.Y_FLAG, b1 & 0x10);
        setButtonFlag(ControllerPacket.LB_FLAG, b1 & 0x40);
        setButtonFlag(ControllerPacket.RB_FLAG, b1 & 0x80);

        int b2 = data.get() & 0xFF;
        setButtonFlag(ControllerPacket.BACK_FLAG, b2 & 0x04);
        setButtonFlag(ControllerPacket.PLAY_FLAG, b2 & 0x08);
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b2 & 0x10);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b2 & 0x20);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b2 & 0x40);
        setButtonFlag(ControllerPacket.TOUCHPAD_FLAG, b2 & 0x80);

        int b3 = data.get() & 0xFF;
        int hatValue = b3 & 0x0F;
        setButtonFlag(ControllerPacket.UP_FLAG, (hatValue == 1 || hatValue == 8 || hatValue == 2) ? 1 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG, (hatValue == 5 || hatValue == 6 || hatValue == 4) ? 1 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG, (hatValue == 7 || hatValue == 8 || hatValue == 6) ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, (hatValue == 3 || hatValue == 2 || hatValue == 4) ? 1 : 0);
        setButtonFlag(ControllerPacket.PADDLE1_FLAG, b3 & 0x40);
        setButtonFlag(ControllerPacket.PADDLE2_FLAG, b3 & 0x80);
        setButtonFlag(ControllerPacket.MISC_FLAG, b3 & 0x20);
        setButtonFlag(ControllerPacket.M_BUTTON_FLAG, b3 & 0x10);

        int b4 = data.get() & 0xFF;
        setButtonFlag(ControllerPacket.PADDLE3_FLAG, b4 & 0x01);
        setButtonFlag(ControllerPacket.PADDLE4_FLAG, b4 & 0x02);

        leftStickX = data.getShort() * 3.051851E-5f;
        leftStickY = (-data.getShort()) * 3.051851E-5f;
        rightStickX = data.getShort() * 3.051851E-5f;
        rightStickY = (-data.getShort()) * 3.051851E-5f;

        leftTrigger = (data.get() & 0xFF) / 255.0f;
        rightTrigger = (data.get() & 0xFF) / 255.0f;

        short axRaw = (short) (((data.get() & 0xFF) << 8) | (data.get() & 0xFF));
        short ayRaw = (short) (((data.get() & 0xFF) << 8) | (data.get() & 0xFF));
        short azRaw = (short) (((data.get() & 0xFF) << 8) | (data.get() & 0xFF));
        accelX = axRaw * 0.0011971008f;
        accelY = ayRaw * 0.0011971008f;
        accelZ = azRaw * 0.0011971008f;

        gyroX = ((short) (((data.get() & 0xFF) << 8) | (data.get() & 0xFF))) * 0.061035156f;
        gyroY = ((short) (((data.get() & 0xFF) << 8) | (data.get() & 0xFF))) * 0.061035156f;
        gyroZ = ((short) (((data.get() & 0xFF) << 8) | (data.get() & 0xFF))) * 0.061035156f;
    }

    @Override
    protected boolean doInit() {
        Log.d(TAG, "G7 doInit");
        byte[] buf = new byte[64];
        buf[0] = CMD_MODE_SWITCH;
        buf[1] = 0x01;
        for (int i = 0; i < 3; i++) {
            sendCommand(buf);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return true;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] report = new byte[64];
        report[0] = CMD_RUMBLE;
        report[1] = 0x03;
        report[2] = (byte) (lowFreqMotor >> 8);
        report[3] = (byte) (highFreqMotor >> 8);
        sendCommand(report);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // GameSir G7 has no trigger rumble.
    }

    @Override
    public void sendCommand(byte[] data) {
        if (connection == null || outEndpt == null) {
            return;
        }
        int res = connection.bulkTransfer(outEndpt, data, data.length, 500);
        if (res != data.length) {
            Log.w(TAG, "Command transfer mismatch: " + res);
        }
    }

    /** Toggles the mute indicator LEDs on the G7. */
    public void setMuteLight(boolean muted) {
        byte[] report = new byte[64];
        report[0] = CMD_RUMBLE;
        report[1] = 0x04;
        report[2] = 0x01;
        report[3] = 0x01;
        if (muted) {
            report[4] = (byte) 0xFF;
        } else {
            report[5] = (byte) 0xFF;
        }
        sendCommand(report);
    }

    private boolean handleStandardPacket(ByteBuffer buffer) {
        int btnField = buffer.get(0) & 0xFF;
        setButtonFlag(ControllerPacket.A_FLAG, (btnField & 0x01) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.B_FLAG, (btnField & 0x02) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.X_FLAG, (btnField & 0x08) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.Y_FLAG, (btnField & 0x10) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.LB_FLAG, (btnField & 0x40) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.RB_FLAG, (btnField & 0x80) != 0 ? 1 : 0);

        int btnField1 = buffer.get(1) & 0xFF;
        setButtonFlag(ControllerPacket.BACK_FLAG, (btnField1 & 0x04) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.PLAY_FLAG, (btnField1 & 0x08) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (btnField1 & 0x10) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, (btnField1 & 0x20) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, (btnField1 & 0x40) != 0 ? 1 : 0);

        int dpad = buffer.get(2) & 0x0F;
        setButtonFlag(ControllerPacket.UP_FLAG, (dpad == 0 || dpad == 1 || dpad == 7) ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, (dpad < 1 || dpad > 3) ? 0 : 1);
        setButtonFlag(ControllerPacket.DOWN_FLAG, (dpad < 3 || dpad > 5) ? 0 : 1);
        setButtonFlag(ControllerPacket.LEFT_FLAG, (dpad < 5 || dpad > 7) ? 0 : 1);

        leftStickX = ((buffer.get(3) & 0xFF) - 128) / 128.0f;
        leftStickY = ((buffer.get(4) & 0xFF) - 128) / 128.0f;
        rightStickX = ((buffer.get(5) & 0xFF) - 128) / 128.0f;
        rightStickY = ((buffer.get(6) & 0xFF) - 128) / 128.0f;
        leftTrigger = (buffer.get(7) & 0xFF) / 255.0f;
        rightTrigger = (buffer.get(8) & 0xFF) / 255.0f;
        return true;
    }
}
