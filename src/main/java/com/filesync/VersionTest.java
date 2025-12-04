package com.filesync;

/**
 * Simple test class to verify version reading works from JAR
 */
public class VersionTest {
    public static void main(String[] args) {
        // Simulate the version reading logic
        try (java.io.InputStream is = VersionTest.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                String version = props.getProperty("application.version");
                if (version != null && !version.trim().isEmpty()) {
                    System.out.println("Version read from JAR: " + version.trim());
                } else {
                    System.out.println("Version property not found in JAR");
                }
            } else {
                System.out.println("version.properties not found in JAR");
            }
        } catch (Exception e) {
            System.out.println("Error reading version: " + e.getMessage());
        }
    }
}
