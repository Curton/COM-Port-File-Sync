package com.filesync.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses .gitignore files and checks if paths match the ignore patterns.
 * Supports multiple .gitignore files in subdirectories.
 */
public class GitignoreParser {

    private static final String GITIGNORE_FILENAME = ".gitignore";
    
    private final File baseDirectory;
    // Maps directory path to list of patterns from .gitignore in that directory
    private final Map<String, List<GitignorePattern>> patternsByDir;

    public GitignoreParser(File baseDirectory) {
        this.baseDirectory = baseDirectory;
        this.patternsByDir = new HashMap<>();
    }

    /**
     * Scan for all .gitignore files and load patterns
     */
    public void loadGitignoreFiles() throws IOException {
        patternsByDir.clear();
        scanForGitignoreFiles(baseDirectory, "");
    }

    /**
     * Recursively scan for .gitignore files
     */
    private void scanForGitignoreFiles(File directory, String relativePath) throws IOException {
        File gitignoreFile = new File(directory, GITIGNORE_FILENAME);
        if (gitignoreFile.exists() && gitignoreFile.isFile()) {
            List<GitignorePattern> patterns = parseGitignoreFile(gitignoreFile);
            patternsByDir.put(relativePath, patterns);
        }

        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    String childRelativePath = relativePath.isEmpty() ? 
                            child.getName() : 
                            relativePath + "/" + child.getName();
                    scanForGitignoreFiles(child, childRelativePath);
                }
            }
        }
    }

    /**
     * Parse a .gitignore file and return list of patterns
     */
    private List<GitignorePattern> parseGitignoreFile(File gitignoreFile) throws IOException {
        List<GitignorePattern> patterns = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(gitignoreFile))) {
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

    /**
     * Parse a single gitignore pattern line
     */
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
        
        return new GitignorePattern(Pattern.compile(regex), negation, directoryOnly, anchored);
    }

    /**
     * Convert gitignore glob pattern to regex
     */
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
                case '*':
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
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                case '{':
                case '}':
                case '[':
                case ']':
                case '\\':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
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
                    pathToCheck = relativePath.substring(dirPath.length() + 1);
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
     * Represents a single gitignore pattern
     */
    private static class GitignorePattern {
        private final Pattern regex;
        private final boolean negation;
        private final boolean directoryOnly;
        private final boolean anchored;

        public GitignorePattern(Pattern regex, boolean negation, boolean directoryOnly, boolean anchored) {
            this.regex = regex;
            this.negation = negation;
            this.directoryOnly = directoryOnly;
            this.anchored = anchored;
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

