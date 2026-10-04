package com.zyz4.gkme.input.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * 8BitDo HID controller USB driver, ported from the upstream axi_usb_gamepad 1.0.6
 * driver (cn.axi.gamepad.usb.driver.Hid8BitdoController).
 */
public class Hid8BitdoController extends AbstractDualSenseController {
    private static final String TAG = "Hid8BitdoController";

    private static final int[] SUPPORTED_VENDORS = {
            0x2DC8, // 8BitDo
    };
    private static final int[] SUPPORTED_PRODUCTS = {
            0x301F,
    };

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

    public Hid8BitdoController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() == 10) {
            return handleStandardPacket(buffer);
        }
        return true;
    }

    @Override
    protected boolean doInit() {
        return true;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        // Rumble is not implemented for this HID mode.
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // No trigger rumble.
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

    private boolean handleStandardPacket(ByteBuffer buffer) {
        int btnField = buffer.get(1) & 0xFF;
        setButtonFlag(ControllerPacket.A_FLAG, (btnField & 0x01) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.B_FLAG, (btnField & 0x02) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.X_FLAG, (btnField & 0x08) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.Y_FLAG, (btnField & 0x10) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.LB_FLAG, (btnField & 0x40) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.RB_FLAG, (btnField & 0x80) != 0 ? 1 : 0);

        int btnField1 = buffer.get(2) & 0xFF;
        setButtonFlag(ControllerPacket.BACK_FLAG, (btnField1 & 0x04) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.PLAY_FLAG, (btnField1 & 0x08) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (btnField1 & 0x10) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, (btnField1 & 0x20) != 0 ? 1 : 0);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, (btnField1 & 0x40) != 0 ? 1 : 0);

        int dpad = buffer.get(3) & 0x0F;
        setButtonFlag(ControllerPacket.UP_FLAG, (dpad == 0 || dpad == 1 || dpad == 7) ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, (dpad < 1 || dpad > 3) ? 0 : 1);
        setButtonFlag(ControllerPacket.DOWN_FLAG, (dpad < 3 || dpad > 5) ? 0 : 1);
        setButtonFlag(ControllerPacket.LEFT_FLAG, (dpad < 5 || dpad > 7) ? 0 : 1);
        setButtonFlag(ControllerPacket.TOUCHPAD_FLAG, buffer.get(3) == 0x1F ? 1 : 0);

        leftStickX = ((buffer.get(4) & 0xFF) - 128) / 128.0f;
        leftStickY = ((buffer.get(5) & 0xFF) - 128) / 128.0f;
        rightStickX = ((buffer.get(6) & 0xFF) - 128) / 128.0f;
        rightStickY = ((buffer.get(7) & 0xFF) - 128) / 128.0f;
        leftTrigger = (buffer.get(9) & 0xFF) / 255.0f;
        rightTrigger = (buffer.get(8) & 0xFF) / 255.0f;
        return true;
    }
}
