package com.filesync.config;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.List;
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
    private static final String PREF_FOLDER_HISTORY_SIZE = "folderHistorySize";
    private static final String PREF_FOLDER_HISTORY_PREFIX = "folderHistory.";
    private static final String PREF_STRICT_SYNC = "strictSync";
    private static final String PREF_RESPECT_GITIGNORE = "respectGitignore";
    private static final String PREF_FAST_MODE = "fastMode";
    private static final String PREF_FOLDER_MAPPING_PREFIX = "folderMapping.";
    private static final String PREF_FOLDER_MAPPING_COUNT = "count";
    private static final String PREF_FOLDER_MAPPING_SENDER = "sender";
    private static final String PREF_FOLDER_MAPPING_RECEIVER = "receiver";

    public static final int MAX_RECENT_FOLDERS = 10;
    public static final int MAX_REMEMBERED_FOLDER_MAPPINGS = MAX_RECENT_FOLDERS;
    
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
    private List<String> recentFolders;
    private boolean strictSync;
    private boolean respectGitignore;
    private boolean fastMode;
    
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
        loadRecentFolders();
        strictSync = prefs.getBoolean(PREF_STRICT_SYNC, false);
        respectGitignore = prefs.getBoolean(PREF_RESPECT_GITIGNORE, false);
        fastMode = prefs.getBoolean(PREF_FAST_MODE, true);
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
        saveRecentFolders();
        prefs.putBoolean(PREF_STRICT_SYNC, strictSync);
        prefs.putBoolean(PREF_RESPECT_GITIGNORE, respectGitignore);
        prefs.putBoolean(PREF_FAST_MODE, fastMode);
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

    public List<String> getRecentFolders() {
        return new ArrayList<>(recentFolders);
    }

    public void addRecentFolder(String folderPath) {
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return;
        }

        recentFolders.remove(folderPath);
        recentFolders.add(0, folderPath);
        while (recentFolders.size() > MAX_RECENT_FOLDERS) {
            recentFolders.remove(recentFolders.size() - 1);
        }
        setLastFolder(folderPath);
        save();
    }
    
    public boolean isStrictSync() {
        return strictSync;
    }
    
    public void setStrictSync(boolean strictSync) {
        this.strictSync = strictSync;
    }
    
    public boolean isRespectGitignore() {
        return respectGitignore;
    }
    
    public void setRespectGitignore(boolean respectGitignore) {
        this.respectGitignore = respectGitignore;
    }
    
    public boolean isFastMode() {
        return fastMode;
    }
    
    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    /**
     * Normalize a folder path for consistent comparison and storage.
     * Trims whitespace and normalizes path separators to forward slash.
     */
    public static String normalizeFolderPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.trim().replace('\\', '/');
    }

    /**
     * Get remembered folder mapping for the given port context.
     * Returns the most recent two-element array [senderPath, receiverPath], or null if no mapping stored.
     */
    public String[] getRememberedFolderMapping(String port) {
        List<String[]> mappings = getRememberedFolderMappings(port);
        if (mappings.isEmpty()) {
            return null;
        }
        return mappings.get(0);
    }

    /**
     * Get remembered folder mappings for the given port context.
     * Entry 0 is the most recent mapping.
     */
    public List<String[]> getRememberedFolderMappings(String port) {
        return new ArrayList<>(loadRememberedFolderMappings(port));
    }

    /**
     * Set remembered folder mapping for the given port context.
     * Keeps up to {@link #MAX_REMEMBERED_FOLDER_MAPPINGS} entries in MRU order.
     * Call after successful sync completion.
     */
    public void setRememberedFolderMapping(String port, String senderPath, String receiverPath) {
        String normalizedSender = normalizeFolderPath(senderPath);
        String normalizedReceiver = normalizeFolderPath(receiverPath);
        if (normalizedSender.isEmpty() || normalizedReceiver.isEmpty()) {
            return;
        }

        List<String[]> mappings = loadRememberedFolderMappings(port);
        mappings.removeIf(mapping ->
                normalizedSender.equals(mapping[0]) && normalizedReceiver.equals(mapping[1]));
        mappings.add(0, new String[]{normalizedSender, normalizedReceiver});
        saveRememberedFolderMappings(port, mappings);
    }

    private static String keyForPort(String port) {
        return PREF_FOLDER_MAPPING_PREFIX + (port != null && !port.isEmpty() ? port + "." : "default.");
    }

    private List<String[]> loadRememberedFolderMappings(String port) {
        String key = keyForPort(port);
        int storedSize = Math.max(0, prefs.getInt(key + PREF_FOLDER_MAPPING_COUNT, 0));
        int size = Math.min(storedSize, MAX_REMEMBERED_FOLDER_MAPPINGS);

        List<String[]> mappings = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String sender = normalizeFolderPath(prefs.get(key + i + "." + PREF_FOLDER_MAPPING_SENDER, ""));
            String receiver = normalizeFolderPath(prefs.get(key + i + "." + PREF_FOLDER_MAPPING_RECEIVER, ""));
            if (!sender.isEmpty() && !receiver.isEmpty()) {
                mappings.add(new String[]{sender, receiver});
            }
        }

        String legacySender = normalizeFolderPath(prefs.get(key + PREF_FOLDER_MAPPING_SENDER, ""));
        String legacyReceiver = normalizeFolderPath(prefs.get(key + PREF_FOLDER_MAPPING_RECEIVER, ""));
        boolean hasLegacyMapping = !legacySender.isEmpty() && !legacyReceiver.isEmpty();
        boolean hasStoredMappings = !mappings.isEmpty();

        if (hasLegacyMapping) {
            mappings.removeIf(mapping ->
                    legacySender.equals(mapping[0]) && legacyReceiver.equals(mapping[1]));
            mappings.add(0, new String[]{legacySender, legacyReceiver});
            prefs.remove(key + PREF_FOLDER_MAPPING_SENDER);
            prefs.remove(key + PREF_FOLDER_MAPPING_RECEIVER);
        }

        List<String[]> normalizedMappings = dedupeAndCapRememberedFolderMappings(mappings);
        boolean mappingsChanged = storedSize != normalizedMappings.size();
        if (!mappingsChanged && hasStoredMappings) {
            for (int i = 0; i < mappings.size() && i < normalizedMappings.size(); i++) {
                String[] existing = mappings.get(i);
                String[] normalized = normalizedMappings.get(i);
                if (!existing[0].equals(normalized[0]) || !existing[1].equals(normalized[1])) {
                    mappingsChanged = true;
                    break;
                }
            }
            if (!mappingsChanged && mappings.size() != normalizedMappings.size()) {
                mappingsChanged = true;
            }
        } else if (!hasStoredMappings && hasLegacyMapping) {
            mappingsChanged = true;
        }

        if (mappingsChanged || hasLegacyMapping) {
            saveRememberedFolderMappings(port, normalizedMappings);
        }
        return normalizedMappings;
    }

    private void saveRememberedFolderMappings(String port, List<String[]> mappings) {
        List<String[]> normalizedMappings = dedupeAndCapRememberedFolderMappings(mappings);
        String key = keyForPort(port);
        prefs.putInt(key + PREF_FOLDER_MAPPING_COUNT, normalizedMappings.size());
        for (int i = 0; i < normalizedMappings.size(); i++) {
            prefs.put(key + i + "." + PREF_FOLDER_MAPPING_SENDER, normalizedMappings.get(i)[0]);
            prefs.put(key + i + "." + PREF_FOLDER_MAPPING_RECEIVER, normalizedMappings.get(i)[1]);
        }

        for (int i = normalizedMappings.size(); i < MAX_REMEMBERED_FOLDER_MAPPINGS; i++) {
            prefs.remove(key + i + "." + PREF_FOLDER_MAPPING_SENDER);
            prefs.remove(key + i + "." + PREF_FOLDER_MAPPING_RECEIVER);
        }

        prefs.remove(key + PREF_FOLDER_MAPPING_SENDER);
        prefs.remove(key + PREF_FOLDER_MAPPING_RECEIVER);
        try {
            prefs.flush();
        } catch (Exception ignored) {
        }
    }

    private List<String[]> dedupeAndCapRememberedFolderMappings(List<String[]> mappings) {
        List<String[]> unique = new ArrayList<>();
        if (mappings == null) {
            return unique;
        }

        for (String[] mapping : mappings) {
            if (mapping == null || mapping.length < 2) {
                continue;
            }

            String sender = normalizeFolderPath(mapping[0]);
            String receiver = normalizeFolderPath(mapping[1]);
            if (sender.isEmpty() || receiver.isEmpty()) {
                continue;
            }

            boolean duplicate = false;
            for (String[] existing : unique) {
                if (sender.equals(existing[0]) && receiver.equals(existing[1])) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(new String[]{sender, receiver});
            }
        }

        while (unique.size() > MAX_REMEMBERED_FOLDER_MAPPINGS) {
            unique.remove(unique.size() - 1);
        }
        return unique;
    }

    /**
     * Check if current mapping matches remembered mapping.
     * Both paths should be normalized before calling.
     */
    public static boolean isMappingMatch(String localPath, String remotePath,
                                        String rememberedSender, String rememberedReceiver) {
        String nLocal = normalizeFolderPath(localPath);
        String nRemote = normalizeFolderPath(remotePath);
        String nSender = normalizeFolderPath(rememberedSender);
        String nReceiver = normalizeFolderPath(rememberedReceiver);
        if (nSender.isEmpty() && nReceiver.isEmpty()) {
            return true;
        }
        return nLocal.equals(nSender) && nRemote.equals(nReceiver);
    }

    /**
     * Returns true when both local and remote folders are changed from remembered mapping.
     * Useful to treat a full mapping switch as an intentional change.
     */
    public static boolean isBothSidesChangedFromRemembered(String localPath, String remotePath,
                                                           String rememberedSender, String rememberedReceiver) {
        String nLocal = normalizeFolderPath(localPath);
        String nRemote = normalizeFolderPath(remotePath);
        String nSender = normalizeFolderPath(rememberedSender);
        String nReceiver = normalizeFolderPath(rememberedReceiver);

        if (nLocal.isEmpty() || nRemote.isEmpty() || nSender.isEmpty() || nReceiver.isEmpty()) {
            return false;
        }

        return !nLocal.equals(nSender) && !nRemote.equals(nReceiver);
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

    private void loadRecentFolders() {
        recentFolders = new ArrayList<>();
        int size = Math.min(MAX_RECENT_FOLDERS, Math.max(0, prefs.getInt(PREF_FOLDER_HISTORY_SIZE, 0)));
        for (int i = 0; i < size; i++) {
            String folderPath = prefs.get(PREF_FOLDER_HISTORY_PREFIX + i, "");
            if (folderPath != null && !folderPath.trim().isEmpty()) {
                recentFolders.add(folderPath.trim());
            }
        }

        if (recentFolders.isEmpty() && lastFolder != null && !lastFolder.trim().isEmpty()) {
            recentFolders.add(lastFolder.trim());
        }

        dedupeAndCapRecentFolders();
    }

    private void saveRecentFolders() {
        dedupeAndCapRecentFolders();
        prefs.putInt(PREF_FOLDER_HISTORY_SIZE, recentFolders.size());
        for (int i = 0; i < recentFolders.size(); i++) {
            prefs.put(PREF_FOLDER_HISTORY_PREFIX + i, recentFolders.get(i));
        }
        for (int i = recentFolders.size(); i < MAX_RECENT_FOLDERS; i++) {
            prefs.remove(PREF_FOLDER_HISTORY_PREFIX + i);
        }
    }

    private void dedupeAndCapRecentFolders() {
        List<String> unique = new ArrayList<>();
        for (String folderPath : recentFolders) {
            if (folderPath == null || folderPath.trim().isEmpty()) {
                continue;
            }

            String normalized = folderPath.trim();
            unique.remove(normalized);
            unique.add(normalized);
        }

        while (unique.size() > MAX_RECENT_FOLDERS) {
            unique.remove(unique.size() - 1);
        }
        recentFolders = unique;
    }

    /**
     * Find the receiver folder path that corresponds to the given sender folder
     * for the specified COM port. Looks up the most recent mapping for that port.
     *
     * @param senderPath the sender's sync folder path (will be normalized)
     * @param port the COM port identifier (e.g., "COM3") or empty string for default
     * @return the corresponding receiver folder path, or null if no mapping found
     */
    public String findReceiverFolderForSender(String senderPath, String port) {
        if (senderPath == null || senderPath.isEmpty()) {
            return null;
        }
        List<String[]> mappings = loadRememberedFolderMappings(port);
        String normalizedSender = normalizeFolderPath(senderPath);
        for (String[] mapping : mappings) {
            if (mapping.length >= 2 && normalizedSender.equals(normalizeFolderPath(mapping[0]))) {
                return mapping[1];
            }
        }
        return null;
    }
}
