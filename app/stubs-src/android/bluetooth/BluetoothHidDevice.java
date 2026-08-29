package android.bluetooth;

import java.util.List;
import java.util.concurrent.Executor;

/** Compile-time stub for the Android system Bluetooth HID Device API. */
public abstract class BluetoothHidDevice implements BluetoothProfile {
    public static final byte SUBCLASS1_NONE = (byte) 0x00;
    public static final byte SUBCLASS1_KEYBOARD = (byte) 0x40;
    public static final byte SUBCLASS1_MOUSE = (byte) 0x80;
    public static final byte SUBCLASS1_COMBO = (byte) 0xC0;

    public static final byte REPORT_TYPE_INPUT = (byte) 1;
    public static final byte REPORT_TYPE_OUTPUT = (byte) 2;
    public static final byte REPORT_TYPE_FEATURE = (byte) 3;

    public abstract static class Callback {
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {}
        public void onConnectionStateChanged(BluetoothDevice device, int state) {}
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {}
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {}
        public void onSetProtocol(BluetoothDevice device, byte protocol) {}
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {}
        public void onVirtualCableUnplug(BluetoothDevice device) {}
    }

    public boolean registerApp(
            BluetoothHidDeviceAppSdpSettings sdp,
            BluetoothHidDeviceAppQosSettings inQos,
            BluetoothHidDeviceAppQosSettings outQos,
            Executor executor,
            Callback callback) { return false; }

    public boolean unregisterApp() { return false; }

    /** AOSP signature: report ID is an int, not a byte. */
    public boolean sendReport(BluetoothDevice device, int id, byte[] data) { return false; }

    public boolean replyReport(BluetoothDevice device, byte type, byte id, byte[] data) { return false; }
    public boolean reportError(BluetoothDevice device, byte error) { return false; }
    public boolean connect(BluetoothDevice device) { return false; }
    public boolean disconnect(BluetoothDevice device) { return false; }

    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) { return null; }
    @Override public List<BluetoothDevice> getConnectedDevices() { return null; }
    @Override public int getConnectionState(BluetoothDevice device) { return 0; }
}
