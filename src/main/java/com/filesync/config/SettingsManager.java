package com.filesync.config;

import com.fazecast.jSerialComm.SerialPort;

import java.util.prefs.Preferences;

/**
 * Manages application settings persistence using Java Preferences API.
 * Stores COM port configuration including baud rate, data bits, stop bits, and parity.
 */
public class SettingsManager {

    private static final String PREF_BAUD_RATE = "baudRate";
    private static final String PREF_DATA_BITS = "dataBits";
    private static final String PREF_STOP_BITS = "stopBits";
    private static final String PREF_PARITY = "parity";
    private static final String PREF_LAST_PORT = "lastPort";
    private static final String PREF_LAST_FOLDER = "lastFolder";
    private static final String PREF_STRICT_SYNC = "strictSync";

    // Default values
    public static final int DEFAULT_BAUD_RATE = 115200;
    public static final int DEFAULT_DATA_BITS = 8;
    public static final int DEFAULT_STOP_BITS = SerialPort.ONE_STOP_BIT;
    public static final int DEFAULT_PARITY = SerialPort.NO_PARITY;

    // Common baud rates
    public static final int[] BAUD_RATES = {
            300, 1200, 2400, 4800, 9600, 14400, 19200, 38400,
            57600, 115200, 230400, 460800, 921600
    };

    // Data bits options
    public static final int[] DATA_BITS_OPTIONS = {5, 6, 7, 8};

    // Stop bits options (display names and values)
    public static final String[] STOP_BITS_NAMES = {"1", "1.5", "2"};
    public static final int[] STOP_BITS_VALUES = {
            SerialPort.ONE_STOP_BIT,
            SerialPort.ONE_POINT_FIVE_STOP_BITS,
            SerialPort.TWO_STOP_BITS
    };

    // Parity options (display names and values)
    public static final String[] PARITY_NAMES = {"None", "Odd", "Even", "Mark", "Space"};
    public static final int[] PARITY_VALUES = {
            SerialPort.NO_PARITY,
            SerialPort.ODD_PARITY,
            SerialPort.EVEN_PARITY,
            SerialPort.MARK_PARITY,
            SerialPort.SPACE_PARITY
    };

    private final Preferences prefs;

    private int baudRate;
    private int dataBits;
    private int stopBits;
    private int parity;
    private String lastPort;
    private String lastFolder;
    private boolean strictSync;

    public SettingsManager() {
        prefs = Preferences.userNodeForPackage(SettingsManager.class);
        load();
    }

    /**
     * Load settings from preferences storage
     */
    public void load() {
        baudRate = prefs.getInt(PREF_BAUD_RATE, DEFAULT_BAUD_RATE);
        dataBits = prefs.getInt(PREF_DATA_BITS, DEFAULT_DATA_BITS);
        stopBits = prefs.getInt(PREF_STOP_BITS, DEFAULT_STOP_BITS);
        parity = prefs.getInt(PREF_PARITY, DEFAULT_PARITY);
        lastPort = prefs.get(PREF_LAST_PORT, "");
        lastFolder = prefs.get(PREF_LAST_FOLDER, "");
        strictSync = prefs.getBoolean(PREF_STRICT_SYNC, false);
    }

    /**
     * Save current settings to preferences storage
     */
    public void save() {
        prefs.putInt(PREF_BAUD_RATE, baudRate);
        prefs.putInt(PREF_DATA_BITS, dataBits);
        prefs.putInt(PREF_STOP_BITS, stopBits);
        prefs.putInt(PREF_PARITY, parity);
        prefs.put(PREF_LAST_PORT, lastPort != null ? lastPort : "");
        prefs.put(PREF_LAST_FOLDER, lastFolder != null ? lastFolder : "");
        prefs.putBoolean(PREF_STRICT_SYNC, strictSync);
    }

    // Getters and Setters

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public int getDataBits() {
        return dataBits;
    }

    public void setDataBits(int dataBits) {
        this.dataBits = dataBits;
    }

    public int getStopBits() {
        return stopBits;
    }

    public void setStopBits(int stopBits) {
        this.stopBits = stopBits;
    }

    public int getParity() {
        return parity;
    }

    public void setParity(int parity) {
        this.parity = parity;
    }

    public String getLastPort() {
        return lastPort;
    }

    public void setLastPort(String lastPort) {
        this.lastPort = lastPort;
    }

    public String getLastFolder() {
        return lastFolder;
    }

    public void setLastFolder(String lastFolder) {
        this.lastFolder = lastFolder;
    }

    public boolean isStrictSync() {
        return strictSync;
    }

    public void setStrictSync(boolean strictSync) {
        this.strictSync = strictSync;
    }

    /**
     * Get the index of a stop bits value in STOP_BITS_VALUES array
     */
    public static int getStopBitsIndex(int value) {
        for (int i = 0; i < STOP_BITS_VALUES.length; i++) {
            if (STOP_BITS_VALUES[i] == value) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Get the index of a parity value in PARITY_VALUES array
     */
    public static int getParityIndex(int value) {
        for (int i = 0; i < PARITY_VALUES.length; i++) {
            if (PARITY_VALUES[i] == value) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Get the index of a baud rate in BAUD_RATES array
     */
    public static int getBaudRateIndex(int value) {
        for (int i = 0; i < BAUD_RATES.length; i++) {
            if (BAUD_RATES[i] == value) {
                return i;
            }
        }
        return 9; // Default to 115200 index
    }

    /**
     * Get the index of data bits in DATA_BITS_OPTIONS array
     */
    public static int getDataBitsIndex(int value) {
        for (int i = 0; i < DATA_BITS_OPTIONS.length; i++) {
            if (DATA_BITS_OPTIONS[i] == value) {
                return i;
            }
        }
        return 3; // Default to 8 bits index
    }
}

