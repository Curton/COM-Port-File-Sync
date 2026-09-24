# COM Port File Sync

A cross-platform Java application for synchronizing files between two computers over a serial (COM) port connection.

## Overview

COM Port File Sync enables reliable file transfer between two machines connected via a serial cable (null-modem cable, USB-to-serial adapters, or virtual COM ports). It is built on XMODEM, with error detection and automatic retries, making it ideal for scenarios where network connectivity is unavailable or restricted.

<img width="1378" height="1189" alt="image" src="https://github.com/user-attachments/assets/a1fb3436-00c6-4aae-a1a4-6257eac21ee6" />
<img width="1482" height="1193" alt="image" src="https://github.com/user-attachments/assets/c68810dc-635b-46bf-bb8f-5c8d464f7936" />

## Features

### Core Functionality
- **Bidirectional File Synchronization** - Each machine can act as sender or receiver, and the role can be switched at any time when the link is idle
- **Reliable Transfer** - Damaged or lost data is detected and retransmitted automatically, and many small files travel together in one batched transfer instead of one at a time
- **File Change Detection** - Compares both sides to find new and modified files. A text file that differs only in line endings is not treated as modified
- **Automatic Compression** - Content is GZIP-compressed when that actually reduces what goes over the wire. Text is a good candidate, already-compressed extensions are skipped, and unknown types are judged by entropy and a trial compression
- **Partial File Updates** - When only part of a large file changed, only the changed parts are sent. This is used for files of at least 8 KiB, and only when the compressed delta is at least 2 KiB smaller than the compressed whole file - otherwise the file is sent in full
- **Append-Only Transfers** - A file that only grew on the sender (a log, for instance) transfers just the new tail
- **Interrupted-Transfer Recovery** - A large new file that was cut off mid-transfer keeps what already arrived and resumes, but only above 4 MiB and only when the destination did not already exist. Smaller transfers restart from the beginning

### Sync Modes
- **Standard Sync** - Transfers new and modified files from sender to receiver
- **Mirror Mode** - Additionally deletes files on the receiver that don't exist on the sender, and removes empty directories. Toggling it on disables the `.gitignore` filter, since ignoring files while deleting everything else is rarely what you want
- **Fast Mode** - Skips full content comparison when building the manifest, which speeds up preparation on large folders, at the cost of possibly missing a change. It is **enabled by default**. It does not change transfer speed in any way - see [Troubleshooting](#troubleshooting)

### Conflict Resolution
When the same file has been modified on both sides, the sync stops and asks instead of silently overwriting:

- **Unified Conflict Dialog** - One window walks every conflict with Previous / Next navigation and a `Conflict 2/5` progress counter; cancelling aborts the whole resolution
- **Text Conflicts** - Local and remote panes side by side with Previous/Next Change navigation between change regions, and an editable merged version you can hand-tune. The panes show one change region at a time, not the whole file
- **Binary Conflicts** - Size, modified time, and the first 8 characters of the MD5 for both sides (or `N/A (fast mode)`), so the choice is informed
- **Resolutions** - Text files offer **Keep Local**, **Keep Remote**, or **Merge**. Binary files offer **Local version**, **Remote version**, or **Skip (Do Not Transfer)**. Merge is text-only and Skip is binary-only; they are never offered together
- **The Chosen Version Decides What Gets Written** - There is no separate apply-target control. Keeping the local version overwrites the receiver and leaves your own file alone; keeping the remote version overwrites your local file too; merging writes the merged text to both. A binary Skip leaves both sides untouched
- **Merge Can Be Unavailable** - If the local file is larger than 1 MiB or cannot be read, its content is never loaded, and the Merge option is disabled with an explanation. Only Keep Local and Keep Remote remain, so a merge can never silently discard the local version
- **Whitespace-Only Differences Are Not Surfaced** - A text conflict whose differences are purely whitespace or blank lines is resolved automatically in favour of the sender's version without opening the dialog. Only conflicts with meaningful differences reach the queue
- **Partial Copies Are Completed** - A receiver copy that merely stops short of the sender's version is finished off instead of being flagged as a conflict. This requires a receiver-side MD5 to verify the prefix, so it does not apply in Fast Mode, which leaves binaries unhashed

### Sync Preview
Review the plan before anything is written:

- **Review Before You Apply** - Every planned operation is listed with what will happen, its size and its path; no sync operation is performed until you confirm
- **Plain-Language Operations** - Each row says whether the file will be created, updated, appended to, deleted or needs a conflict decision, and new folders are highlighted
- **Per-File Comparison** - A button on a text row opens a side-by-side comparison of the local and remote versions, with navigation between change regions. Binary rows show metadata only, and directory rows do not open a comparison. Text previews are limited to 512 KiB and 4,000 rendered lines; beyond that the preview is marked as truncated
- **Sortable Columns** - Click a column header to sort. When sorted by the default Sync column, files selected for transfer are listed first. Rows never jump while you tick individual boxes; only bulk operations re-sort. Folders keep their contents grouped together when you sort by Path, but sorting by Size or Type interleaves them
- **Search Bar** - Type an extension (`java`, `txt`) or any part of a file name to re-order the list: rows whose **extension** matches move to the top, then rows whose **file name** contains the text, then everything else. Matches are highlighted in yellow. The search is live and case-insensitive
- **Select All / Deselect All** - Bulk-toggle the transfer set
- **Select Changes (git)** - Selects exactly the files git reports as changed in the sync folder. Nothing is selected when the preview opens; this button is the only thing that consults git. If git cannot be run - missing executable, or the folder is not a repository - the reason is shown in the dialog and written to the log. A git run that succeeds but matches no preview row reports only a count

### File Filtering
- **`.gitignore` Support** - Respects `.gitignore` patterns, including nested files, negation, directory-only and anchored patterns, to exclude files from synchronization. Cannot be combined with Mirror Mode
- **Remembered Folder Mapping** - Remote folder mapping is remembered per port and offered for confirmation when it changes

### User Experience
- **Automatic Connect on Startup** - If exactly one COM port is available, or the port you used last time is still present, the application connects on its own. The Connect button then reads Disconnect, so do not click it just to follow a checklist
- **Direction Toggle** - Switch between sender and receiver roles with a single click; blocked while a transfer is running
- **Drag-and-Drop Sending** - Drop a single file directly onto the main window for immediate transfer when connected. The receiver saves it into its Downloads folder, sanitizing the name and adding ` (1)`, ` (2)` and so on to avoid overwriting an existing file. Dropping several files or a folder is rejected
- **Recent Folder Dropdown** - Remembers up to ten recently used folders for quick switching
- **Progress Reporting** - Progress bar for transfers, plus progress while the file list is being prepared
- **Locked Files Are Handled, Not Fatal** - A destination file held open by another program is queued with retry / skip / skip-all controls, and the rest of the sync continues. The reverse case differs: if a file on the *sender* cannot be read, the transfer of that file can fail and end the session rather than skipping quietly
- **Save Combined Log** - Right-click the log area to fetch the other machine's log and save a merged, [LOCAL]/[REMOTE]-tagged copy (combined_log_yyyyMMdd_HHmmss.txt) into the selected sync folder. Only the sender can do this, and only while connected and not transferring. Timestamps are aligned using time-sync markers; if no markers are available the merger falls back to plain time-of-day ordering
- **Debug Mode** - Optional detailed logging from the settings dialog

### Shared Text
- **Manual Send** - Click "Send Text" to push the contents of the shared text area to the other machine
- **Clipboard Actions** - "Overwrite from Clipboard", "Append from Clipboard", "Copy to Clipboard", plus double-click to copy the entire text
- **Word-Style Undo/Redo** - Ctrl+Z undoes the last change (typing runs, deletions, clipboard overwrites and even incoming remote overwrites each count as one step), Ctrl+Y or Ctrl+Shift+Z redoes it
- **No Waiting Behind Transfers** - Text sent while a file transfer is running is slipped in between transfer blocks instead of waiting for the transfer to finish, so the transfer itself is unaffected. Long texts are held back and delivered once the current transfer ends

### Connection
- **Role Negotiation** - Automatic sender/receiver role assignment on connection
- **Connection Monitoring** - The link is watched continuously with a 5-second heartbeat and a 15-second receive timeout; a dropped connection is reported and cleaned up rather than left half-open
- **Clean Cancellation** - Cancelling a sync leaves the connection and the roles intact and tells the other side, so it does not keep waiting

### Reliability Details
- **XMODEM with CRC** - Supports 128-byte, 1 KiB and 4 KiB blocks, with up to 10 retries per block and a 10-second block timeout
- **Batch Transfers** - Regular files are grouped into envelopes of roughly 32 KiB and at most 256 files, cutting per-file handshakes
- **Persistent Caches** - Manifests and delta signatures are cached per folder, so an unchanged receiver does not have to be re-hashed every session
- **Path Containment** - Incoming paths are resolved canonically and rejected if they point outside the sync root
- **Case Handling** - The filesystem's case sensitivity is detected and recorded, so a rename that only changes letter case is not mistaken for a delete plus a create

## Requirements

- **Java 17** or higher
- **Maven** for building (3.6 or newer is recommended; the build does not enforce a minimum)
- A serial connection between two computers (null-modem cable, USB-serial adapters, or virtual COM port software)
- **Both machines must run the same version.** This project does not maintain compatibility between versions. Version numbers are shown in the UI but are not exchanged or checked during role negotiation, so a mismatched pair is not guaranteed to fail cleanly - upgrade both ends together and expect a version skew to show up as a transfer error rather than a clear version message

## Installation

### Building from Source

```bash
# Clone the repository
git clone https://github.com/Curton/COM-Port-File-Sync.git
cd COM-Port-File-Sync

# Build with Maven
mvn package

# The executable JAR will be in target/com-file-sync-x.x.x.jar
```

On Windows, `build.bat` builds the project and `pack.bat` does the same. Neither deletes older JARs: `target/` keeps the full build history on purpose, so there is always something to roll back to.

### Running the Application

**Linux/macOS:**
```bash
java -jar target/com-file-sync-*.jar
```

**Windows:**
```bat
run.bat
```

`run.bat` looks for `com-file-sync-*.jar` in `target/`. If exactly one is present it runs it; if several are present it lists them newest-first and asks you to pick. Note that `java -jar target/com-file-sync-*.jar` does **not** work in `cmd` - it does not expand the wildcard and passes it through literally.

## Usage

### Quick Start

1. **Connect two machines** via a serial cable (null-modem or virtual COM port)
2. **Launch the application** on both machines
3. **Select the COM port** from the dropdown on each machine
4. **Connect** - click "Connect" on both sides and they will auto-negotiate roles. If the application already connected on its own, the button reads "Disconnect" and you can skip this
5. **Select sync folders** on both machines using the folder dropdown for quick-recent picks or the "Browse..." button
6. **Click "Sync Preview"** to inspect the operation plan (recommended for first-time syncs)
7. **Resolve any conflicts** reported in the preview, if prompted
8. **Click "Start Sync"** on the sender side when ready to apply changes. Strict-mode deletions and unresolved conflicts are confirmed at this point

### Connection Settings

Click the **Settings** button to configure COM port parameters:

| Parameter | Default | Options |
|-----------|---------|---------|
| Baud Rate | 115200 | 300, 1200, 2400, 4800, 9600, 14400, 19200, 38400, 57600, 115200, 230400, 460800, 921600 |
| Data Bits | 8 | 5, 6, 7, 8 |
| Stop Bits | 1 | 1, 1.5, 2 |
| Parity | None | None, Odd, Even, Mark, Space |

**Debug Mode** in the same dialog turns on detailed logging in the log pane.

### Working with the Sync Preview

The preview is a dry run with respect to your files: no planned sync operation is performed until you confirm it. Building the manifest does write a cache file, and probing the filesystem's case sensitivity briefly creates and removes a probe file.

- **Choose what to send** - Everything starts unchecked. Tick rows individually, use **Select All** / **Deselect All**, or press **Select Changes (git)** to pick up exactly what git reports as changed in the sync folder.
- **Inspect before sending** - Click the preview button on a text row to compare the local and remote copies side by side. Binary rows show metadata, directories have no preview.
- **Find a file fast** - Type in the search bar to re-rank the list by extension match, then by file-name match, with hits highlighted.
- **Sort** - Click any column header. Sorting by Path keeps directories and their contents grouped; sorting by Size or Type does not.

### Resolving Conflicts

When both sides changed the same file, a **Resolve Conflicts** dialog appears:

1. The header shows your position in the queue (for example, `Conflict 2/5`) and the file path.
2. Text files open with local and remote panes showing one change region at a time, Previous/Next Change navigation, and an editable merged version. Binary files show size, modified time and an MD5 prefix for both sides.
3. Choose a resolution: **Keep Local / Keep Remote / Merge** for text, **Local version / Remote version / Skip** for binary. Merge is disabled when the local file could not be read.
4. Use **Previous** / **Next** to walk the queue; the last page's button becomes **Done**. **Cancel** aborts the entire resolution and restores the sync controls.

Conflicts that differ only in whitespace or blank lines never reach this dialog - they are resolved in favour of the sender's version automatically.

## Known Limitations

- **Hidden files and directories are excluded** from the manifest, so they are never synchronized
- **Fast Mode is on by default** and can miss a change to a binary or unknown-type file whose size and timestamp are unchanged
- **Only one dropped file at a time** is accepted, and only one file transfer can be in flight
- **A merge cannot be offered for local files above 1 MiB** or files that cannot be read
- **Text previews are capped** at 512 KiB and 4,000 lines
- **Maven's minimum version is not enforced** by the build

## Troubleshooting

### Connection Issues

- **"No COM ports found"**: Ensure serial drivers are installed and the cable is connected, then press **Refresh**
- **"Connection timeout"**: Verify both applications are running and connected to the correct ports. The initial handshake waits up to 60 seconds
- **"Connection Lost"**: Check the cable; the app detects the drop after 15 seconds without a heartbeat, cleans up the session and refreshes the port list - reconnect when the cable is back

### Sync Issues

- **Files not syncing**: Ensure the sender has the "Start Sync" button enabled (requires a live connection, a selected sync folder, and sender role)
- **Slow transfers**: Fast Mode will not help - it only speeds up building the manifest. To transfer faster, raise the baud rate in Settings if both ends and the cable support it
- **Permission errors**: Ensure write permissions on the sync folder
- **A destination file is locked**: another program holds it open. It is queued with retry / skip / skip-all controls; close the file and retry, or skip it
- **A transfer ended with a read error on the sender**: the source file could not be read during transfer. This can end the session rather than skipping the one file
- **Unexpected conflicts**: both sides changed the file after the last sync, and the receiver's copy is the newer one. If only the sender changed, that is an ordinary transfer and no conflict is raised
- **"Select Changes (git)" selects nothing**: either git could not be run - the reason is then shown next to the button and logged - or it ran successfully but none of the changed paths appear in the preview, which happens when they are filtered out of the sync folder or lie outside it

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

Never run `mvn clean` in this project: `target/` intentionally keeps every previously built JAR as a build history.
