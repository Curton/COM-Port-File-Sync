package com.filesync.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses .gitignore files and checks if paths match the ignore patterns. Supports multiple
 * .gitignore files in subdirectories.
 */
public class GitignoreParser {

    private static final String GITIGNORE_FILENAME = ".gitignore";

    /**
     * The sync app's own per-folder ignore file. Unlike .gitignore it is never synced: each side
     * honors only the copy in its own sync folder root, and the scanner skips it from the manifest
     * the way it skips .gitignore.
     */
    public static final String FILESYNC_IGNORE_FILENAME = ".filesyncignore";

    private final File baseDirectory;
    private final String ignoreFilename;
    // Maps directory path to list of patterns from the ignore file in that directory
    private final Map<String, List<GitignorePattern>> patternsByDir;

    public GitignoreParser(File baseDirectory) {
        this(baseDirectory, GITIGNORE_FILENAME);
    }

    public GitignoreParser(File baseDirectory, String ignoreFilename) {
        this.baseDirectory = baseDirectory;
        this.ignoreFilename = ignoreFilename;
        this.patternsByDir = new HashMap<>();
    }

    /** Scan for all ignore files and load patterns */
    public void loadGitignoreFiles() throws IOException {
        patternsByDir.clear();
        scanForGitignoreFiles(baseDirectory, "");
    }

    /**
     * Load patterns from the base directory's ignore file only, ignoring any copies deeper in the
     * tree. The {@link #FILESYNC_IGNORE_FILENAME} model: one file at the sync folder root.
     */
    public void loadRootFileOnly() throws IOException {
        patternsByDir.clear();
        File rootIgnoreFile = new File(baseDirectory, ignoreFilename);
        if (rootIgnoreFile.exists() && rootIgnoreFile.isFile()) {
            patternsByDir.put("", parseGitignoreFile(rootIgnoreFile));
        }
    }

    /** Recursively scan for ignore files */
    private void scanForGitignoreFiles(File directory, String relativePath) throws IOException {
        File gitignoreFile = new File(directory, ignoreFilename);
        if (gitignoreFile.exists() && gitignoreFile.isFile()) {
            List<GitignorePattern> patterns = parseGitignoreFile(gitignoreFile);
            patternsByDir.put(relativePath, patterns);
        }

        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    String childRelativePath =
                            relativePath.isEmpty()
                                    ? child.getName()
                                    : relativePath + "/" + child.getName();
                    scanForGitignoreFiles(child, childRelativePath);
                }
            }
        }
    }

    /** Parse a .gitignore file and return list of patterns */
    private List<GitignorePattern> parseGitignoreFile(File gitignoreFile) throws IOException {
        List<GitignorePattern> patterns = new ArrayList<>();

        try (BufferedReader reader =
                new BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(gitignoreFile),
                                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                GitignorePattern pattern = parsePattern(line);
                if (pattern != null) {
                    patterns.add(pattern);
                }
            }
        }

        return patterns;
    }

    /** Parse a single gitignore pattern line */
    private GitignorePattern parsePattern(String line) {
        boolean negation = false;
        boolean directoryOnly = false;
        boolean anchored = false;

        // Check for negation
        if (line.startsWith("!")) {
            negation = true;
            line = line.substring(1);
        }

        // Check for directory-only pattern
        if (line.endsWith("/")) {
            directoryOnly = true;
            line = line.substring(0, line.length() - 1);
        }

        // Check if pattern is anchored (contains / except at end)
        if (line.contains("/")) {
            anchored = true;
            // Remove leading slash if present
            if (line.startsWith("/")) {
                line = line.substring(1);
            }
        }

        if (line.isEmpty()) {
            return null;
        }

        // Convert gitignore glob to regex
        String regex = convertGlobToRegex(line, anchored);

        return new GitignorePattern(Pattern.compile(regex), negation, directoryOnly);
    }

    /** Convert gitignore glob pattern to regex */
    private String convertGlobToRegex(String glob, boolean anchored) {
        StringBuilder regex = new StringBuilder();

        if (!anchored) {
            // Non-anchored patterns can match anywhere in path
            regex.append("(^|/)");
        } else {
            regex.append("^");
        }

        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);

            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        // ** matches any number of directories
                        if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                            regex.append("(.*/)?");
                            i += 2;
                        } else {
                            regex.append(".*");
                            i++;
                        }
                    } else {
                        // * matches anything except /
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        regex.append("\\").append(c);
                default -> regex.append(c);
            }
        }

        // Pattern should match end or be followed by /
        regex.append("(/.*)?$");

        return regex.toString();
    }

    /**
     * Check if a relative path should be ignored based on .gitignore rules
     *
     * @param relativePath relative path from base directory (using / as separator)
     * @param isDirectory true if the path is a directory
     * @return true if the path should be ignored
     */
    public boolean isIgnored(String relativePath, boolean isDirectory) {
        // Normalize path separator
        relativePath = relativePath.replace('\\', '/');

        boolean ignored = false;

        // Check patterns from root to the containing directory
        String[] parts = relativePath.split("/");
        StringBuilder currentPath = new StringBuilder();

        for (int i = 0; i <= parts.length; i++) {
            String dirPath = currentPath.toString();

            List<GitignorePattern> patterns = patternsByDir.get(dirPath);
            if (patterns != null) {
                // Get the path relative to this .gitignore location
                String pathToCheck;
                if (dirPath.isEmpty()) {
                    pathToCheck = relativePath;
                } else {
                    int startIndex = dirPath.length() + 1;
                    if (startIndex >= relativePath.length()) {
                        // dirPath equals or is longer than relativePath, skip this check
                        pathToCheck = "";
                    } else {
                        pathToCheck = relativePath.substring(startIndex);
                    }
                }

                for (GitignorePattern pattern : patterns) {
                    if (pattern.matches(pathToCheck, isDirectory)) {
                        ignored = !pattern.isNegation();
                    }
                }
            }

            if (i < parts.length) {
                if (currentPath.length() > 0) {
                    currentPath.append("/");
                }
                currentPath.append(parts[i]);
            }
        }

        return ignored;
    }

    /**
     * Whether a path is ignored when the walk that would have checked its ancestors is not
     * available: the path itself matches, or an ancestor directory does (a walk skips a matched
     * subtree wholesale, so everything under an ignored directory is ignored).
     *
     * <p>This is the deletion-exemption view the sender applies to receiver-only paths: a
     * directory-only pattern like {@code /build/} never matches {@code build/x.txt} directly, yet
     * the file must count as ignored or strict sync would delete it on the receiver.
     */
    public boolean isIgnoredWithAncestors(String relativePath, boolean isDirectory) {
        relativePath = relativePath.replace('\\', '/');
        if (isIgnored(relativePath, isDirectory)) {
            return true;
        }
        int slash = relativePath.lastIndexOf('/');
        while (slash >= 0) {
            if (isIgnored(relativePath.substring(0, slash), true)) {
                return true;
            }
            slash = relativePath.lastIndexOf('/', slash - 1);
        }
        return false;
    }

    /** Represents a single gitignore pattern */
    private static class GitignorePattern {
        private final Pattern regex;
        private final boolean negation;
        private final boolean directoryOnly;

        public GitignorePattern(Pattern regex, boolean negation, boolean directoryOnly) {
            this.regex = regex;
            this.negation = negation;
            this.directoryOnly = directoryOnly;
        }

        public boolean matches(String path, boolean isDirectory) {
            // Directory-only patterns only match directories
            if (directoryOnly && !isDirectory) {
                return false;
            }

            return regex.matcher(path).find();
        }

        public boolean isNegation() {
            return negation;
        }
    }
}
