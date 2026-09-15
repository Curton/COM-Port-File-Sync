# COM Port File Sync

A cross-platform Java application for synchronizing files between two computers over a serial (COM) port connection.

## Overview

COM Port File Sync enables reliable file transfer between two machines connected via a serial cable (null-modem cable, USB-to-serial adapters, or virtual COM ports). It is built on XMODEM, with error detection and automatic retries, making it ideal for scenarios where network connectivity is unavailable or restricted.

<img width="1378" height="1189" alt="image" src="https://github.com/user-attachments/assets/a1fb3436-00c6-4aae-a1a4-6257eac21ee6" />
<img width="1482" height="1193" alt="image" src="https://github.com/user-attachments/assets/c68810dc-635b-46bf-bb8f-5c8d464f7936" />

## Features

### Core Functionality
- **Bidirectional File Synchronization** - Sync files in either direction (A -> B or B <- A) between two connected machines
- **Reliable Transfer** - Damaged or lost data is detected and retransmitted automatically, and many small files travel together instead of one at a time
- **File Change Detection** - Compares both sides to find new and modified files. A text file that differs only in line endings is not treated as modified
- **Automatic Compression** - Text files are compressed before transfer; content that is already compressed is sent as-is
- **Partial File Updates** - When only part of a large file changed, only the changed parts are sent - and only when that is genuinely faster than resending the whole file
- **Resumable Appends** - A file that only grew on the sender (a log, for instance) transfers just the new tail, and an interrupted transfer keeps what already arrived instead of starting over

### Sync Modes
- **Standard Sync** - Transfers new and modified files from sender to receiver
- **Mirror Mode** - Additionally deletes files on the receiver that don't exist on the sender
- **Fast Mode** - Skips the full content comparison for faster preparation, at the cost of possibly missing a change

### Conflict Resolution
When the same file has been modified on both sides, the sync stops and asks instead of silently overwriting:

- **Unified Conflict Dialog** - One window walks every conflict with Previous / Next navigation and a progress counter; cancelling aborts the whole resolution
- **Text Conflicts** - Side-by-side local and remote panes with jump-to-next-change navigation and an editable merged version you can hand-tune
- **Binary Conflicts** - Size and modified time of both sides, so the choice is informed
- **Resolutions** - Keep local, keep remote, skip, or merge (text only)
- **Apply Target** - Apply the chosen version to the remote only, or to both local and remote
- **Trivial Differences Are Still Shown** - A file changed only in whitespace is surfaced rather than silently resolved
- **Partial Copies Are Completed** - A receiver copy that merely stops short of the sender's version is finished off instead of being flagged as a conflict

### Sync Preview
Review the plan before anything is written:

- **Review Before You Apply** - Every planned operation is listed with what will happen, its size and its path; nothing is written until you confirm
- **Plain-Language Operations** - Each row says whether the file will be created, updated, appended to, deleted or needs a conflict decision, and new folders are highlighted
- **Per-File Comparison** - A button on each row opens a side-by-side comparison of the local and remote versions, with navigation between change regions
- **Sortable Columns** - Click a column header to sort; files selected for transfer move to the top by default, and rows never jump while you tick individual boxes
- **Search Bar** - Type an extension (`java`, `txt`) or any part of a file name to re-order the list: rows whose **extension** matches move to the top, then rows whose **file name** contains the text, then everything else. Matches are highlighted in yellow. The search is live and case-insensitive
- **Select All / Deselect All** - Bulk-toggle the transfer set
- **Select Changes (git)** - Selects exactly the files git reports as changed in the sync folder. Nothing is selected when the preview opens; this button is the only thing that consults git. If git is unavailable, or the folder is not a repository, the reason is shown in the dialog and written to the log

### File Filtering
- **`.gitignore` Support** - Respects `.gitignore` patterns to exclude files from synchronization
- **Remembered Folder Mapping** - Remote folder mapping is remembered per port; prompts when mapping changes

### User Experience
- **Folder Mapping Guard** - Remembers local-to-remote folder mappings per port and prompts when paths change
- **Direction Toggle** - Switch between sender (A -> B) and receiver (B <- A) modes with a single click
- **Drag-and-Drop Sending** - Drop a file directly onto the main window for immediate transfer when connected
- **Recent Folder Dropdown** - Remembers recently used valid folders for quick switching
- **Progress Reporting** - Progress bar for transfers, plus progress while the file list is being prepared
- **Unreadable Files Don't Stop the Sync** - A file locked by another program is reported but does not abort the rest of the sync
- **Save Combined Log** - Right-click the log area to fetch the other machine's log and save a merged, [LOCAL]/[REMOTE]-tagged copy (combined_log_yyyyMMdd_HHmmss.txt) into the selected sync folder, with the two machines' timestamps lined up
- **Debug Mode** - Optional detailed logging from the settings dialog

### Shared Text
- **Manual Send** - Click "Send Text" to push the contents of the shared text area to the other machine
- **Clipboard Actions** - "Overwrite from Clipboard", "Append from Clipboard", "Copy to Clipboard", plus double-click to copy the entire text
- **Word-Style Undo/Redo** - Ctrl+Z undoes the last change (typing runs, deletions, clipboard overwrites and even incoming remote overwrites each count as one step), Ctrl+Y or Ctrl+Shift+Z redoes it
- **No Waiting Behind Transfers** - Text sent while a file transfer is running is slipped in between transfer blocks instead of waiting for the transfer to finish, so the transfer itself is unaffected. Long texts are delivered once the current transfer ends

### Connection
- **Role Negotiation** - Automatic sender/receiver role assignment on connection
- **Connection Monitoring** - The link is watched continuously; a dropped connection is reported and cleaned up rather than left half-open
- **Clean Cancellation** - Cancelling a sync leaves the connection and the roles intact and tells the other side, so it does not keep waiting

## Requirements

- **Java 17** or higher
- **Maven 3.6+** for building
- A serial connection between two computers (null-modem cable, USB-serial adapters, or virtual COM port software)
- **Both machines must run the same version.** This project does not maintain compatibility between versions, so upgrade both ends together and expect a mismatched pair to fail rather than degrade gracefully.

## Installation

### Building from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/com-file-sync.git
cd com-file-sync

# Build with Maven
mvn package

# The executable JAR will be in target/com-file-sync-x.x.x.jar
```

On Windows, `build.bat` builds the project and `pack.bat` does the same.

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
6. **Click "Sync Preview"** to inspect the operation plan (recommended for first-time syncs)
7. **Resolve any conflicts** reported in the preview, if prompted
8. **Click "Start Sync"** on the sender side when ready to apply changes

### Connection Settings

Click the **Settings** button to configure COM port parameters:

| Parameter | Default | Options |
|-----------|---------|---------|
| Baud Rate | 115200 | 300 - 921600 |
| Data Bits | 8 | 5, 6, 7, 8 |
| Stop Bits | 1 | 1, 1.5, 2 |
| Parity | None | None, Odd, Even, Mark, Space |

**Debug Mode** in the same dialog turns on detailed logging in the log pane.

### Working with the Sync Preview

The preview is a dry run: nothing is written until you confirm it.

- **Choose what to send** - Everything starts unchecked. Tick rows individually, use **Select All** / **Deselect All**, or press **Select Changes (git)** to pick up exactly what git reports as changed in the sync folder.
- **Inspect before sending** - Click the preview button on a row to compare the local and remote copies side by side.
- **Find a file fast** - Type in the search bar to re-rank the list by extension match, then by file-name match, with hits highlighted.
- **Sort** - Click any column header; folders keep their contents grouped together.

### Resolving Conflicts

When both sides changed the same file, a **Resolve Conflicts** dialog appears:

1. The header shows your position in the queue (for example, conflict 2 of 5) and the file path.
2. Text files open with local and remote panes, navigation between change regions, and an editable merged version. Binary files show size and modified time for both sides.
3. Choose **Keep Local**, **Keep Remote**, **Skip**, or **Merge** (text only), and whether the result applies to the **remote only** or to **both** sides.
4. Use **Previous** / **Next** to walk the queue; the last page's button becomes **Done**. **Cancel** aborts the entire resolution and restores the sync controls.

### Shared Text

The Shared Text area allows text sharing between connected machines:

- Type or paste text into the area
- Click "Send Text" to send the current text to the other machine
- Use "Overwrite from Clipboard" / "Append from Clipboard" / "Copy to Clipboard" to move text in and out, or double-click to copy the entire text to the clipboard
- Word-style undo/redo: Ctrl+Z undoes the last change, Ctrl+Y or Ctrl+Shift+Z redoes it

Text sent while a transfer is running is delivered without waiting for the transfer to finish.

## Troubleshooting

### Connection Issues

- **"No COM ports found"**: Ensure serial drivers are installed and the cable is connected, then press **Refresh**
- **"Connection timeout"**: Verify both applications are running and connected to the correct ports
- **"Connection Lost"**: Check the cable; the app detects the drop, cleans up the session and refreshes the port list - reconnect when the cable is back

### Sync Issues

- **Files not syncing**: Ensure the sender has the "Start Sync" button enabled (must be in sender mode)
- **Slow transfers**: Consider enabling Fast Mode for large folders
- **Permission errors**: Ensure write permissions on the sync folder
- **A file is skipped as unreadable**: another program holds it open; close it and sync again
- **Unexpected conflicts**: both sides changed the file after the last sync. Resolve them, or re-sync one side first
- **"Select Changes (git)" selects nothing**: the sync folder may not be inside a git repository, the changed paths may be gitignored, or git may be unavailable. The reason is shown next to the button and written to the log

### Virtual COM Port Setup (Windows)

For testing on a single machine, use virtual COM port software:
1. Install [com0com](https://com0com.sourceforge.net/) or similar
2. Create a virtual COM port pair (e.g., COM10 <-> COM11)
3. Connect one application instance to COM10 and another to COM11

## Building

```bash
# Build
mvn package

# Run tests
mvn test

# Build without tests
mvn package -DskipTests
```
