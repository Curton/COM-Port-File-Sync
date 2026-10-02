package com.filesync;

import com.filesync.cli.CliMain;
import com.filesync.ui.MainFrame;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import javax.swing.*;

/**
 * Application entry point for COM Port File Sync. Without arguments it initializes the Swing UI
 * with system look and feel; with arguments it runs a one-shot headless CLI session and exits with
 * its exit code (see {@link CliMain#printUsage}).
 */
public class Main {

    public static void main(String[] args) {
        // Force UTF-8 encoding for console output to avoid garbled characters
        try {
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Ignore if failed, continue with default encoding
        }

        if (args.length > 0) {
            System.exit(CliMain.run(args));
            return;
        }

        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to default look and feel
            System.err.println("Could not set system look and feel: " + e.getMessage());
        }

        // Launch UI on Event Dispatch Thread
        SwingUtilities.invokeLater(
                () -> {
                    MainFrame mainFrame = new MainFrame();
                    mainFrame.setVisible(true);
                });
    }
}
