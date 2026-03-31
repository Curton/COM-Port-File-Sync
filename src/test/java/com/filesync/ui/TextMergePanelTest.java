package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class TextMergePanelTest {

    /**
     * Test that a single-line conflict produces conflict markers only around that line, not the
     * entire file.
     */
    @Test
    void buildFullFileGitStyleMergeContent_singleLineConflict_showsOnlyChangedLine()
            throws Exception {
        // Create local and remote content that differ by only one line
        String localContent =
                "<?xml version=\"1.0\"?>\n"
                        + "<project>\n"
                        + "    <version>1.6.13</version>\n"
                        + "    <name>Test</name>\n"
                        + "</project>\n";
        String remoteContent =
                "<?xml version=\"1.0\"?>\n"
                        + "<project>\n"
                        + "    <version>1.6.14</version>\n"
                        + "    <name>Test</name>\n"
                        + "</project>\n";

        ConflictInfo conflict = createConflictInfo(localContent, remoteContent);
        TextMergePanel panel = new TextMergePanel(conflict);

        String result = invokeBuildFullFileGitStyleMergeContent(panel, conflict);

        // Verify conflict markers are present
        assertTrue(result.contains("<<<<<<< LOCAL"), "Should contain LOCAL marker");
        assertTrue(result.contains("======="), "Should contain separator");
        assertTrue(result.contains(">>>>>>> REMOTE"), "Should contain REMOTE marker");

        // Verify only the changed line is in the conflict block
        String conflictBlock =
                result.substring(
                        result.indexOf("<<<<<<< LOCAL"), result.indexOf(">>>>>>> REMOTE") + 15);
        assertTrue(conflictBlock.contains("1.6.13"), "Should contain local version");
        assertTrue(conflictBlock.contains("1.6.14"), "Should contain remote version");

        // Verify unchanged lines are NOT inside conflict markers
        assertFalse(
                conflictBlock.contains("<?xml"),
                "XML declaration should not be inside conflict block");
        assertFalse(
                conflictBlock.contains("<name>"),
                "Name element should not be inside conflict block");
        assertFalse(
                conflictBlock.contains("</project>"),
                "Closing project tag should not be inside conflict block");

        // Verify unchanged lines appear outside conflict markers
        String beforeConflict = result.substring(0, result.indexOf("<<<<<<< LOCAL"));
        String afterConflict = result.substring(result.indexOf(">>>>>>> REMOTE") + 15);
        assertTrue(
                beforeConflict.contains("<?xml"), "XML declaration should appear before conflict");
        assertTrue(
                afterConflict.contains("</project>"),
                "Closing project tag should appear after conflict");
    }

    /** Test that identical files produce no conflict markers. */
    @Test
    void buildFullFileGitStyleMergeContent_identicalFiles_noConflictMarkers() throws Exception {
        String content =
                "<?xml version=\"1.0\"?>\n"
                        + "<project>\n"
                        + "    <version>1.0.0</version>\n"
                        + "</project>\n";

        ConflictInfo conflict = createConflictInfo(content, content);
        TextMergePanel panel = new TextMergePanel(conflict);

        String result = invokeBuildFullFileGitStyleMergeContent(panel, conflict);

        assertFalse(result.contains("<<<<<<< LOCAL"), "Should not contain LOCAL marker");
        assertFalse(result.contains("======="), "Should not contain separator");
        assertFalse(result.contains(">>>>>>> REMOTE"), "Should not contain REMOTE marker");
        assertEquals(content, result, "Should return original content unchanged");
    }

    /** Test that multiple consecutive differing lines produce a single conflict block. */
    @Test
    void buildFullFileGitStyleMergeContent_multipleConflicts_singleConflictBlock()
            throws Exception {
        String localContent =
                "<root>\n"
                        + "    <version>1.0.0</version>\n"
                        + "    <name>Local</name>\n"
                        + "    <port>8080</port>\n"
                        + "</root>\n";
        String remoteContent =
                "<root>\n"
                        + "    <version>2.0.0</version>\n"
                        + "    <name>Remote</name>\n"
                        + "    <port>9090</port>\n"
                        + "</root>\n";

        ConflictInfo conflict = createConflictInfo(localContent, remoteContent);
        TextMergePanel panel = new TextMergePanel(conflict);

        String result = invokeBuildFullFileGitStyleMergeContent(panel, conflict);

        // Count conflict markers
        int localMarkerCount = countOccurrences(result, "<<<<<<< LOCAL");
        int remoteMarkerCount = countOccurrences(result, ">>>>>>> REMOTE");

        // All consecutive lines differ, so there should be one conflict block
        assertEquals(1, localMarkerCount, "Should have one LOCAL marker");
        assertEquals(1, remoteMarkerCount, "Should have one REMOTE marker");

        // Verify all local values are in the conflict
        assertTrue(result.contains("1.0.0"), "Should contain local version");
        assertTrue(result.contains("Local"), "Should contain local name");
        assertTrue(result.contains("8080"), "Should contain local port");

        // Verify all remote values are in the conflict
        assertTrue(result.contains("2.0.0"), "Should contain remote version");
        assertTrue(result.contains("Remote"), "Should contain remote name");
        assertTrue(result.contains("9090"), "Should contain remote port");
    }

    /** Test that conflicts with context lines between them are properly separated. */
    @Test
    void buildFullFileGitStyleMergeContent_conflictsWithContextLines_separateBlocks()
            throws Exception {
        String localContent =
                "<root>\n"
                        + "    <version>1.0.0</version>\n"
                        + "    <unchanged>same</unchanged>\n"
                        + "    <port>8080</port>\n"
                        + "</root>\n";
        String remoteContent =
                "<root>\n"
                        + "    <version>2.0.0</version>\n"
                        + "    <unchanged>same</unchanged>\n"
                        + "    <port>9090</port>\n"
                        + "</root>\n";

        ConflictInfo conflict = createConflictInfo(localContent, remoteContent);
        TextMergePanel panel = new TextMergePanel(conflict);

        String result = invokeBuildFullFileGitStyleMergeContent(panel, conflict);

        // Verify the unchanged line appears outside conflict markers
        assertTrue(
                result.contains("    <unchanged>same</unchanged>"),
                "Unchanged line should be present in output");

        // Check that the unchanged line is NOT between LOCAL and REMOTE markers of the same block
        // by verifying it appears outside any conflict block
        String[] parts = result.split("<<<<<<< LOCAL");
        for (int i = 1; i < parts.length; i++) {
            String conflictPart = parts[i];
            int separatorIdx = conflictPart.indexOf("=======");
            int remoteIdx = conflictPart.indexOf(">>>>>>> REMOTE");
            if (separatorIdx >= 0 && remoteIdx >= 0) {
                String conflictContent = conflictPart.substring(0, remoteIdx + 15);
                assertFalse(
                        conflictContent.contains("<unchanged>same</unchanged>"),
                        "Unchanged line should not be inside conflict block #" + i);
            }
        }
    }

    /** Test with longer files to ensure the LCS-based diff handles large files correctly. */
    @Test
    void buildFullFileGitStyleMergeContent_longFileWithSingleChange_onlyChangedLineInConflict()
            throws Exception {
        // Build a 100-line file with only line 50 differing
        StringBuilder localBuilder = new StringBuilder();
        StringBuilder remoteBuilder = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            if (i == 50) {
                localBuilder.append("line50-local\n");
                remoteBuilder.append("line50-remote\n");
            } else {
                localBuilder.append("line").append(i).append("\n");
                remoteBuilder.append("line").append(i).append("\n");
            }
        }
        String localContent = localBuilder.toString();
        String remoteContent = remoteBuilder.toString();

        ConflictInfo conflict = createConflictInfo(localContent, remoteContent);
        TextMergePanel panel = new TextMergePanel(conflict);

        String result = invokeBuildFullFileGitStyleMergeContent(panel, conflict);

        // Verify conflict block only contains the changed line
        String conflictBlock =
                result.substring(
                        result.indexOf("<<<<<<< LOCAL"), result.indexOf(">>>>>>> REMOTE") + 15);
        assertTrue(conflictBlock.contains("line50-local"), "Should contain local line 50");
        assertTrue(conflictBlock.contains("line50-remote"), "Should contain remote line 50");
        assertFalse(conflictBlock.contains("line1"), "Line 1 should not be in conflict block");
        assertFalse(conflictBlock.contains("line100"), "Line 100 should not be in conflict block");

        // Verify lines before and after are outside conflict
        String beforeConflict = result.substring(0, result.indexOf("<<<<<<< LOCAL"));
        String afterConflict = result.substring(result.indexOf(">>>>>>> REMOTE") + 15);
        assertTrue(beforeConflict.contains("line1"), "Line 1 should appear before conflict");
        assertTrue(beforeConflict.contains("line49"), "Line 49 should appear before conflict");
        assertTrue(afterConflict.contains("line51"), "Line 51 should appear after conflict");
        assertTrue(afterConflict.contains("line100"), "Line 100 should appear after conflict");
    }

    // Helper methods

    private ConflictInfo createConflictInfo(String localContent, String remoteContent) {
        ConflictInfo conflict =
                new ConflictInfo(
                        "test.xml",
                        new FileChangeDetector.FileInfo("test.xml", 0, 0, null),
                        new FileChangeDetector.FileInfo("test.xml", 0, 0, null),
                        false,
                        localContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        conflict.setRemoteContent(remoteContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return conflict;
    }

    private String invokeBuildFullFileGitStyleMergeContent(
            TextMergePanel panel, ConflictInfo conflict) throws Exception {
        Method method =
                TextMergePanel.class.getDeclaredMethod(
                        "buildFullFileGitStyleMergeContent", ConflictInfo.class);
        method.setAccessible(true);
        return (String) method.invoke(panel, conflict);
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
