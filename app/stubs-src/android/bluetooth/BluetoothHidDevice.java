package android.bluetooth;

import java.util.concurrent.Executor;

/** Compile-time stub for the Android system Bluetooth HID Device API. */
public class BluetoothHidDevice implements BluetoothProfile {
    public static final int HID_DEVICE = 19;
    public static final byte SUBCLASS1_KEYBOARD = 0x40;
    public static final byte REPORT_TYPE_INPUT = 1;
    public static final byte REPORT_TYPE_OUTPUT = 2;
    public static final byte REPORT_TYPE_FEATURE = 3;

    public static class Callback {
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
    public boolean connect(BluetoothDevice device) { return false; }
    public boolean disconnect(BluetoothDevice device) { return false; }
    public boolean sendReport(BluetoothDevice device, byte id, byte[] data) { return false; }
    public boolean replyReport(BluetoothDevice device, byte type, byte id, byte[] data) { return false; }

    @Override public java.util.List<BluetoothDevice> getConnectedDevices() { return java.util.Collections.emptyList(); }
    @Override public int getConnectionState(BluetoothDevice device) { return BluetoothProfile.STATE_DISCONNECTED; }
    @Override public java.util.List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) { return java.util.Collections.emptyList(); }
}
