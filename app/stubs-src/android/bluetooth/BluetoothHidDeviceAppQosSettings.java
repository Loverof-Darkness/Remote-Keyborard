package android.bluetooth;

/** Compile-time stub for the Android system Bluetooth HID QoS settings API. */
public class BluetoothHidDeviceAppQosSettings {
    public static final int SERVICE_NO_TRAFFIC = 0;
    public static final int SERVICE_BEST_EFFORT = 1;
    public static final int SERVICE_GUARANTEED = 2;
    public static final int MAX = -1;

    public BluetoothHidDeviceAppQosSettings(
            int serviceType,
            int tokenRate,
            int tokenBucketSize,
            int peakBandwidth,
            int latency,
            int delayVariation) {}
}
