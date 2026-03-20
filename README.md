# COM Port File Sync

A cross-platform Java application for synchronizing files between two computers over a serial (COM) port connection using the XMODEM-like protocol.

## Overview

COM Port File Sync enables reliable file transfer between two machines connected via a serial cable (null-modem cable, USB-to-serial adapters, or virtual COM ports). It uses a custom protocol built on top of XMODEM with CRC-16, making it ideal for scenarios where network connectivity is unavailable or restricted.

<img width="1378" height="1189" alt="image" src="https://github.com/user-attachments/assets/487a36f7-0789-4c16-97e6-368c04639218" />
<img width="1482" height="1193" alt="image" src="https://github.com/user-attachments/assets/e8fbf775-ccf4-4b4f-9f5c-8e4c70439de4" />

## Features

### Core Functionality
- **Bidirectional File Synchronization** - Sync files in either direction (A -> B or B <- A) between two connected machines
- **XMODEM Protocol** - Reliable file transfer using XMODEM with CRC-16 checksums and automatic retries
- **File Change Detection** - Compares file manifests using MD5 checksums to identify changed files
- **Automatic Compression** - Smart GZIP compression for text-based files with entropy analysis to skip already-compressed content

### Sync Modes
- **Standard Sync** - Transfers new and modified files from sender to receiver
- **Mirror Mode** - Additionally deletes files on the receiver that don't exist on the sender
- **Fast Mode** - Uses file metadata (size + modification time) instead of MD5 hashes for faster manifest generation

### Additional Features

**Sync Control**
- **Direction Toggle** - Switch between sender (A -> B) and receiver (B <- A) modes with a single click
- **Mirror Mode** - Automatically deletes files on receiver that don't exist on sender
- **Fast Mode** - Uses file metadata (size + modification time) instead of MD5 hashes for faster manifest comparison

**File Filtering**
- **`.gitignore` Support** - Respects `.gitignore` patterns to exclude files from synchronization
- **Remembered Folder Mapping** - Remote folder mapping is remembered per port; prompts when mapping changes

**User Experience**
- **Folder Mapping Guard** - Remembers local-to-remote folder mappings per port and prompts when paths change
- **Dry-Run Preview** - Review planned file operations in a preview dialog before applying a sync
- **Single-File Drag-and-Drop Sending** - Drop one file directly onto the main interface for immediate transfer when connected
- **Drag-and-Drop File Send** - Quickly queue one local file for transfer by dragging it onto the main window

**Connection**
- **Role Negotiation** - Automatic sender/receiver role assignment on connection
- **Connection Monitoring** - Heartbeat-based connection health monitoring with auto-recovery
- **Shared Text Area** - Real-time text sharing between connected machines (clipboard sync)

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
java -jar target/com-file-sync-*.jar
```

Or use the included `run.bat` script which automatically finds the latest JAR in the target directory.

**Linux/macOS:**
```bash
java -jar target/com-file-sync-*.jar
```

## Usage

### Quick Start

1. **Connect two machines** via a serial cable (null-modem or virtual COM port)
2. **Launch the application** on both machines
3. **Select the COM port** from the dropdown on each machine
4. **Click "Connect"** on both sides - they will auto-negotiate roles
5. **Select sync folders** on both machines using the folder dropdown for quick-recent picks or the "Browse..." button
6. **Click "Preview Sync"** to inspect the operation plan (recommended for first-time syncs)
7. **Click "Start Sync"** on the sender side when ready to apply changes

The folder dropdown keeps up to the 10 most recently used valid folders for quick switching.

### Connection Settings

Click the **Settings** button to configure COM port parameters:

| Parameter | Default | Options |
|-----------|---------|---------|
| Baud Rate | 115200 | 300 - 921600 |
| Data Bits | 8 | 5, 6, 7, 8 |
| Stop Bits | 1 | 1, 1.5, 2 |
| Parity | None | None, Odd, Even, Mark, Space |

### Shared Text

The Shared Text area allows real-time text sharing between connected machines:
- Type or paste text into the area
- Text is automatically sent to the other machine after a 2-second debounce
- Double-click to copy the entire text to clipboard

## Architecture

```
com.filesync/
|-- Main.java                       # Application entry point and wiring
|-- config/
|   |-- SettingsManager.java        # Persists UI and sync preferences
|-- protocol/
|   |-- SyncProtocol.java           # High-level sync protocol commands
|-- serial/
|   |-- SerialPortManager.java      # Serial port discovery and lifecycle
|   |-- XModemTransfer.java         # XMODEM block transfer with CRC-16
|-- sync/
|   |-- FileSyncManager.java        # Orchestrates manifest and transfer pipeline
|   |-- SyncCoordinator.java        # Maintains sender/receiver state machine
|   |-- RoleNegotiationService.java # Auto role negotiation handshake
|   |-- ConnectionService.java      # Heartbeat and reconnect handling
|   |-- SharedTextService.java      # Shared text channel over protocol
|   |-- FileChangeDetector.java     # Manifest generation and delta detection
|   |-- CompressionUtil.java        # GZIP compression with smart detection
|   |-- GitignoreParser.java        # .gitignore pattern matching
|   |-- FileDropService.java        # Drag-and-drop single file queuing
|   |-- SyncPreviewPlan.java        # Sync operation planning and preview
|   |-- SyncEventBus.java           # Event bus interface
|   |-- SimpleSyncEventBus.java     # Event bus implementation
|   |-- SyncEvent.java              # Event data classes
|   |-- SyncEventType.java          # Event type definitions
|   |-- SyncEventListener.java      # Event listener interface
|-- ui/
    |-- MainFrame.java              # Swing GUI and user interactions
    |-- MainFrameComponents.java    # Reusable UI components
    |-- MainFrameState.java         # UI state management
    |-- ConnectionController.java   # Connection UI logic
    |-- FolderController.java       # Folder selection and mapping
    |-- SyncController.java         # Sync operation UI
    |-- DragDropController.java     # Drag-and-drop handling
    |-- SharedTextController.java   # Shared text area UI
    |-- SettingsDialog.java         # Settings configuration dialog
    |-- SyncPreviewRenderer.java    # Sync preview table rendering
    |-- SyncEventBridge.java        # Bridge between sync events and UI
    |-- LogController.java          # Log display controller
    |-- SyncPreviewRow.java         # Preview table row model
    |-- UiFormatting.java           # UI styling utilities
```


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
