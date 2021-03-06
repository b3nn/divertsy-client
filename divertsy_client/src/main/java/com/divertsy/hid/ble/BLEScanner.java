package com.divertsy.hid.ble;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

/**
 *
 * This code will look for Eddystone URL beacons in order to pull in location data.
 * The URL in the beacon must use the hostname defined below, otherwise it will be ignored.
 * There is no server side lookup of beacon information, nor will we try to fetch the URL
 * sent from the beacon. Instead, we parse the URL to pull back the "floor" and "location"
 * data and save it along with the next weight that is collected.
 *
 * The Beacon URL should follow the following structure (and is limited to 64 bytes total)
 * http://HAX/F1/KITCHEN
 * where "HAX" is the hostname we have defined below to look for, "F1" is the floor information
 * to record, and "KITCHEN" is the location data to record.
 *
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BLEScanner {

    // This is the HOST name we'll look for in Beacons
    // If it is found, we'll try to parse out location data from the URL
    private static final String BEACON_HOST_NAME = "HAX";

    // Assume the last Beacon location until we've lost the signal
    // fro the amount of milli-seconds defined here
    private static final int ON_LOST_TIMEOUT_MS = 5000;

    private static final String TAG = "BLEScanner";
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 2;


    public interface OnClosestChangedListener {
        void onClosestChanged(@Nullable Beacon closest);
    }

    // An aggressive scan for nearby devices that reports immediately.
    private static final ScanSettings SCAN_SETTINGS =
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0)
                    .build();

    private static final Handler handler = new Handler(Looper.getMainLooper());

    // UUID for finding Wit Traveller scales. This is hacky since it's a very broad UUID and will
    // match non-scale devices. Scan results will contain false positive results.
    private static final ParcelUuid WIT_SCALE_UUID =
            ParcelUuid.fromString("00001802-0000-1000-8000-00805f9b34fb");
    // The Eddystone Service UUID, 0xFEAA.
    private static final ParcelUuid EDDYSTONE_SERVICE_UUID =
            ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");
    private final int mRequestEnableBluetooth;
    private final OnClosestChangedListener mOnClosestChangedListener;

    private BluetoothLeScanner scanner;

    private List<ScanFilter> scanFilters;
    private ScanCallback scanCallback;

    private Map<String, Beacon> deviceToBeaconMap = new HashMap<>();

    private Beacon mClosest;

    public BLEScanner(final Activity activity, int requestEnableBluetooth, @NonNull OnClosestChangedListener onClosestChangedListener) {
        mRequestEnableBluetooth = requestEnableBluetooth;
        mOnClosestChangedListener = onClosestChangedListener;
        init(activity);

        // This will limit the BLE results we look at to only ones matching these UUID filters
        scanFilters = new ArrayList<>();
        scanFilters.add(new ScanFilter.Builder().setServiceUuid(EDDYSTONE_SERVICE_UUID).build());
        scanFilters.add(new ScanFilter.Builder().setServiceUuid(WIT_SCALE_UUID).build());

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                ScanRecord scanRecord = result.getScanRecord();
                if (scanRecord == null) {
                    return;
                }

                String deviceAddress = result.getDevice().getAddress();
                String deviceName = result.getDevice().getName();

                if (deviceName == null){
                    deviceName = "";
                }
                // We should only have Eddystone or Scale results now, but need to check which one
                if (! decodeScanRecordWeight(deviceName, scanRecord.getBytes())){

                    // This now should be Eddystone data
                    Beacon beacon;
                    if (!deviceToBeaconMap.containsKey(deviceAddress)) {
                        beacon = new Beacon(deviceAddress, result.getRssi());
                        deviceToBeaconMap.put(deviceAddress, beacon);
                    } else {
                        deviceToBeaconMap.get(deviceAddress).lastSeenTimestamp = System.currentTimeMillis();
                        deviceToBeaconMap.get(deviceAddress).rssi = result.getRssi();
                    }

                    byte[] serviceData = scanRecord.getServiceData(EDDYSTONE_SERVICE_UUID);
                    validateServiceData(deviceAddress, serviceData);

                    findClosest();

                }

            }

            @Override
            public void onScanFailed(int errorCode) {
                switch (errorCode) {
                    case SCAN_FAILED_ALREADY_STARTED:
                        logErrorAndShowToast(activity, "SCAN_FAILED_ALREADY_STARTED");
                        break;
                    case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                        logErrorAndShowToast(activity, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED");
                        break;
                    case SCAN_FAILED_FEATURE_UNSUPPORTED:
                        logErrorAndShowToast(activity, "SCAN_FAILED_FEATURE_UNSUPPORTED");
                        break;
                    case SCAN_FAILED_INTERNAL_ERROR:
                        logErrorAndShowToast(activity, "SCAN_FAILED_INTERNAL_ERROR");
                        break;
                    default:
                        logErrorAndShowToast(activity, "Scan failed, unknown error code");
                        break;
                }
            }

            /**
             * Check if the BLE data contains scale information (currently tested with WIT Traveller
             * devices.
             *
             * @param   deviceName BLE mac address
             * @param   scanRecord  BLE scan beacon bytes
             * @return  True if weight data was found in the array
             */
            private boolean decodeScanRecordWeight(String deviceName, byte[] scanRecord) {
                // scanRecords appear to be 62 bytes, but useful data is located before that.
                // set the min record length to the max array value we'll be pulling
                int MIN_RECORD_LENGTH = 25;

                // Check for proper packet magic flags
                if ((scanRecord != null) && (scanRecord.length > MIN_RECORD_LENGTH))
                    if ((scanRecord[3] == 0x07) && (scanRecord[19] == 0x09) && (scanRecord[20] == 0x00)) {
                        byte key = scanRecord[22];

                        // 6 digits of weight data stored in 3 bytes (2 nibbles each)
                        int numThousandHundreds = scanRecord[22] ^ key;
                        int numTensOne = scanRecord[23] ^ key;
                        int numDecimalPlace = scanRecord[24] ^ key;

                        // Mask out each high/low nibble and multiply to put in correct digit position
                        double weight = (double) (((numThousandHundreds >> 4) & 15) * 1000);
                        weight += (double) ((numThousandHundreds & 15) * 100);

                        weight += (double) (((numTensOne >> 4) & 15) * 10);
                        weight += (double) (numTensOne & 15);

                        weight += ((double) ((numDecimalPlace >> 4) & 15)) * 0.1d;
                        weight += ((double) (numDecimalPlace & 15)) * 0.01d;


                        int weightType = scanRecord[21] ^ key;

                        // Check for negative Flags, scale appears to be inconsistent about flagging this though
                        if ((weightType & 0x60) == 0x60) {
                            weight *= -1.0d;
                        }

                        // Status is different prior to "hold" of weight, so we'll filter out some bits
                        weightType = (weightType >> 1) & 0x0B;

                        String weightString;
                        String unitString;
                        switch (weightType) {
                            case 0x00:
                                weightString = String.format(Locale.ENGLISH, "%3.2f", weight);
                                unitString = "KG";
                                break;
                            case 0x01:
                                weightString = String.format(Locale.ENGLISH, "%3.2f", weight);
                                unitString = "LBS";
                                break;
                            case 0x03:
                                weightString = String.format(Locale.ENGLISH, "%5.0f", (weight * 100.0d));
                                unitString = "G";
                                break;
                            case 0x08:
                                weightString = String.format(Locale.ENGLISH, "%3.1f", (weight * 10.0d));
                                unitString = "OZ";
                                break;
                            default:
                                weightString = "0.0";
                                unitString = "unknown";
                        }

                        Log.d(TAG, "Final Weight: " + weightString + " Unit: " + unitString);

                        try{
                            float weightFloat = Float.parseFloat(weightString);

                            Intent sendIntent = new Intent();
                            sendIntent.setAction("com.divertsy.REMOTE_SCALE_WEIGHT");
                            sendIntent.putExtra("floatScaleWeight", weightFloat);
                            sendIntent.putExtra("stringScaleUnit", unitString);
                            sendIntent.putExtra("stringScaleName", deviceName);
                            APPCONTEXT.sendBroadcast(sendIntent);

                        } catch (NumberFormatException e){
                            Log.e(TAG, "Failed to parse weight float");
                        }

                        return true;
                    }
                return false;
            }


        };
    }

    public void onPause() {
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
    }

    public void onResume() {
        handler.removeCallbacksAndMessages(null);

        setOnLostRunnable();

        if (scanner != null) {
            scanner.startScan(scanFilters, SCAN_SETTINGS, scanCallback);
        }
    }

    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "PERMISSION_REQUEST_COARSE_LOCATION granted");
                }
            }

        }
    }

    @Nullable
    public String getClosestLocation() {
        return mClosest == null ? null : mClosest.urlStatus.toString();
    }

    private void setOnLostRunnable() {
        Runnable removeLostDevices = new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                Iterator<Map.Entry<String, Beacon>> itr = deviceToBeaconMap.entrySet().iterator();
                boolean findClosest = false;
                while (itr.hasNext()) {
                    Beacon beacon = itr.next().getValue();
                    if ((time - beacon.lastSeenTimestamp) > ON_LOST_TIMEOUT_MS) {
                        itr.remove();
                    }
                    if (beacon == mClosest) {
                        findClosest = true;
                    }
                }

                if (findClosest) {
                    findClosest();
                }

                handler.postDelayed(this, ON_LOST_TIMEOUT_MS);
            }
        };
        handler.postDelayed(removeLostDevices, ON_LOST_TIMEOUT_MS);
    }

    private void findClosest() {
        Beacon oldClosest = mClosest;
        mClosest = null;
        for (Beacon other : deviceToBeaconMap.values()) {
            if (other.urlStatus != null) {
                Uri url = other.urlStatus.getUrl();
                if (url != null && BEACON_HOST_NAME.equalsIgnoreCase(url.getHost()) && (mClosest == null || mClosest.rssi < other.rssi)) {
                    mClosest = other;
                }
            }
        }
        if ((mClosest == null && oldClosest != null) || (mClosest != null && !mClosest.equals(oldClosest))) {
            mOnClosestChangedListener.onClosestChanged(mClosest);
        }
    }

    /**
     * Attempts to create the scanner.
     *
     * @param context
     * @return true if successful
     */
    private Context APPCONTEXT;

    public boolean init(final Activity context) {
        APPCONTEXT = context.getApplicationContext();

        // New Android M+ permission check requirement.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("This app needs coarse location access");
                builder.setMessage("Please grant coarse location access so this app can scan for beacons");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        ActivityCompat.requestPermissions(context, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
        }
        BluetoothManager manager = (BluetoothManager) context.getApplicationContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = manager.getAdapter();
        if (btAdapter == null) {
            return false;
        } else if (!btAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            context.startActivityForResult(enableBtIntent, mRequestEnableBluetooth);
            return false;
        } else {
            scanner = btAdapter.getBluetoothLeScanner();
        }
        return true;
    }


    // Checks the frame type and hands off the service data to the validation module.
    private void validateServiceData(String deviceAddress, byte[] serviceData) {
        Beacon beacon = deviceToBeaconMap.get(deviceAddress);
        if (serviceData == null) {
            String err = "Null Eddystone service data";
            beacon.frameStatus.nullServiceData = err;
            logDeviceError(deviceAddress, err);
            return;
        }
        switch (serviceData[0]) {
            case Constants.UID_FRAME_TYPE:
                UidValidator.validate(deviceAddress, serviceData, beacon);
                break;
            case Constants.TLM_FRAME_TYPE:
                TlmValidator.validate(deviceAddress, serviceData, beacon);
                break;
            case Constants.URL_FRAME_TYPE:
                UrlValidator.validate(deviceAddress, serviceData, beacon);
                break;
            default:
                String err = String.format("Invalid frame type byte %02X", serviceData[0]);
                beacon.frameStatus.invalidFrameType = err;
                logDeviceError(deviceAddress, err);
                break;
        }
    }

    private void logErrorAndShowToast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        Log.e(TAG, message);
    }

    private void logDeviceError(String deviceAddress, String err) {
        Log.e(TAG, deviceAddress + ": " + err);
    }
}
