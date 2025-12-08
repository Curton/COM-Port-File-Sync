# COM Port File Sync

A cross-platform Java application for synchronizing files between two computers over a serial (COM) port connection using the XMODEM protocol.

## Overview

COM Port File Sync enables reliable file transfer between two machines connected via a serial cable (null-modem cable, USB-to-serial adapters, or virtual COM ports). It uses a custom protocol built on top of XMODEM with CRC-16 checksums for error-free transmission, making it ideal for scenarios where network connectivity is unavailable or restricted.

## Features

### Core Functionality
- **Bidirectional File Synchronization** - Sync files in either direction (A -> B or B <- A) between two connected machines
- **XMODEM Protocol** - Reliable file transfer using XMODEM with CRC-16 checksums and automatic retries
- **Adaptive Block Sizes** - Supports 128-byte, 1KB, and 4KB blocks for optimal throughput based on data size
- **File Change Detection** - Compares file manifests using MD5 checksums to identify changed files
- **Automatic Compression** - Smart GZIP compression for text-based files with entropy analysis to skip already-compressed content

### Sync Modes
- **Standard Sync** - Transfers new and modified files from sender to receiver
- **Strict Sync Mode** - Additionally deletes files on the receiver that don't exist on the sender (mirror mode)
- **Fast Mode** - Uses file metadata (size + modification time) instead of MD5 hashes for faster manifest generation

### Additional Features
- **`.gitignore` Support** - Respects `.gitignore` patterns to exclude files from synchronization
- **Empty Directory Sync** - Preserves directory structure including empty directories
- **Shared Text Area** - Real-time text sharing between connected machines (clipboard sync)
- **Auto-Connect** - Automatically connects to the last used port or single available port on startup
- **Role Negotiation** - Automatic sender/receiver role assignment on connection
- **Connection Monitoring** - Heartbeat-based connection health monitoring with auto-recovery

## Requirements

- **Java 17** or higher
- **Maven 3.6+** for building
- A serial connection between two computers (null-modem cable, USB-serial adapters, or virtual COM port software)

## Installation

### Building from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/com-file-sync.git
cd com-file-sync

# Build with Maven
mvn clean package

# The executable JAR will be in target/com-file-sync-x.x.x.jar
```

### Running the Application

**Windows:**
```batch
java -jar target/com-file-sync-1.3.0.jar
```

Or use the included `run.bat` script which automatically finds the latest JAR in the target directory.

**Linux/macOS:**
```bash
java -jar target/com-file-sync-1.3.0.jar
```

## Usage

### Quick Start

1. **Connect two machines** via a serial cable (null-modem or virtual COM port)
2. **Launch the application** on both machines
3. **Select the COM port** from the dropdown on each machine
4. **Click "Connect"** on both sides - they will auto-negotiate roles
5. **Select sync folders** on both machines using the "Browse..." button
6. **Click "Start Sync"** on the sender side

### Connection Settings

Click the **Settings** button to configure COM port parameters:

| Parameter | Default | Options |
|-----------|---------|---------|
| Baud Rate | 115200 | 300 - 921600 |
| Data Bits | 8 | 5, 6, 7, 8 |
| Stop Bits | 1 | 1, 1.5, 2 |
| Parity | None | None, Odd, Even, Mark, Space |

### Sync Options

| Option | Description |
|--------|-------------|
| **A -> B / B <- A** | Toggle sync direction. Click to switch between sender and receiver mode |
| **Respect .gitignore** | When enabled, files matching `.gitignore` patterns are excluded from sync |
| **Strict Sync** | When enabled, files on receiver that don't exist on sender will be deleted |
| **Fast Mode** | Uses file metadata instead of MD5 hashes for faster manifest comparison |

### Shared Text

The Shared Text area allows real-time text sharing between connected machines:
- Type or paste text into the area
- Text is automatically sent to the other machine after a 2-second debounce
- Double-click to copy the entire text to clipboard

## Architecture

```
com.filesync/
|-- Main.java                    # Application entry point
|-- config/
|   |-- SettingsManager.java     # Persists user preferences
|-- protocol/
|   |-- SyncProtocol.java        # High-level sync protocol commands
|-- serial/
|   |-- SerialPortManager.java   # Serial port I/O wrapper
|   |-- XModemTransfer.java      # XMODEM protocol implementation
|-- sync/
|   |-- CompressionUtil.java     # GZIP compression with smart detection
|   |-- FileChangeDetector.java  # Manifest generation and comparison
|   |-- FileSyncManager.java     # Sync orchestration
|   |-- GitignoreParser.java     # .gitignore pattern matching
|-- ui/
    |-- MainFrame.java           # Swing GUI
```

## Protocol Details

### Sync Protocol Commands

| Command | Description |
|---------|-------------|
| `MANIFEST_REQ` | Request file manifest from remote |
| `MANIFEST_DATA` | Manifest data follows (via XMODEM) |
| `FILE_REQ` | Request specific file |
| `FILE_DATA` | File data follows (via XMODEM) |
| `FILE_DELETE` | Delete file on remote (strict sync) |
| `MKDIR` | Create directory on remote |
| `RMDIR` | Remove directory on remote |
| `SYNC_COMPLETE` | Sync operation finished |
| `HEARTBEAT` | Connection keep-alive |
| `SHARED_TEXT` | Shared text payload |

### XMODEM Implementation

- **CRC-16-CCITT** checksums for error detection
- **Automatic block size selection**: 4KB for large files, 1KB for medium, 128 bytes for small
- **Retry mechanism**: Up to 10 retries per block with exponential backoff
- **Padding removal**: CTRL-Z (0x1A) padding is stripped from received data

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| [jSerialComm](https://fazecast.github.io/jSerialComm/) | 2.11.4 | Cross-platform serial port communication |
| [Gson](https://github.com/google/gson) | 2.13.2 | JSON serialization for file manifests |
| [JUnit 5](https://junit.org/junit5/) | 5.11.0 | Unit testing |

## Troubleshooting

### Connection Issues

- **"No COM ports found"**: Ensure serial drivers are installed and the cable is connected
- **"Connection timeout"**: Verify both applications are running and connected to the correct ports
- **"Connection Lost"**: Check cable connection; the heartbeat mechanism will auto-detect disconnections

### Sync Issues

- **Files not syncing**: Ensure the sender has "Start Sync" button enabled (must be in sender mode)
- **Slow transfers**: Consider enabling Fast Mode for large directories
- **Permission errors**: Ensure write permissions on the sync folder

### Virtual COM Port Setup (Windows)

For testing on a single machine, use virtual COM port software:
1. Install [com0com](https://com0com.sourceforge.net/) or similar
2. Create a virtual COM port pair (e.g., COM10 <-> COM11)
3. Connect one application instance to COM10 and another to COM11

## Building

```bash
# Clean and build
mvn clean package

# Run tests
mvn test

# Build without tests
mvn clean package -DskipTests
```

## License

This project is open source. See [LICENSE](LICENSE) for details.

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

