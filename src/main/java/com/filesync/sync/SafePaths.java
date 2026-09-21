package com.filesync.sync;

import java.io.File;
import java.io.IOException;

/**
 * Containment check for remote-supplied relative paths, shared by every protocol entry point that
 * turns a peer-controlled path into a file operation.
 *
 * <p>A substring test for {@code "../"} is not enough. The bare forms {@code ".."}, {@code
 * "sub/.."}, {@code "."} and {@code ""} contain no {@code "../"} substring yet still resolve to the
 * base directory or to its parent — so a remote command carrying one of them would recursively
 * delete the folder that <em>contains</em> the sync folder, or the sync folder itself. The same
 * test is also too strict in the other direction: it rejects perfectly ordinary names such as
 * {@code "notes..txt"}, which aborts a whole batch transfer for a file that is entirely safe.
 *
 * <p>The checks are therefore layered: reject the forms our own manifest walk never produces
 * (empty, absolute, drive-qualified, {@code .}/{@code ..} segments), then verify canonically that
 * the result really is strictly inside the base.
 */
public final class SafePaths {

    private SafePaths() {}

    /**
     * Resolve a remote-supplied relative path against a base directory, rejecting anything that
     * could reach outside it.
     *
     * @return the canonical file, guaranteed to be strictly inside {@code baseDir}
     * @throws IOException if the path is empty, absolute, drive-qualified, contains a {@code .} or
     *     {@code ..} segment, or does not canonically resolve inside {@code baseDir}
     */
    public static File resolveWithin(File baseDir, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new IOException("Path traversal rejected: empty path");
        }
        // Drive-qualified ("C:foo"), NTFS alternate-data-stream ("file.txt:stream") and UNC forms
        // are never produced by the manifest walk and must not be joined onto the base.
        if (relativePath.indexOf(':') >= 0) {
            throw new IOException("Path traversal rejected: " + relativePath);
        }
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IOException("Path traversal rejected: " + relativePath);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("Path traversal rejected: " + relativePath);
            }
        }
        File base = baseDir.getCanonicalFile();
        File resolved = new File(base, normalized).getCanonicalFile();
        // Strictly inside: a path that canonicalizes onto the base itself names the sync root,
        // which no remote-supplied path may address.
        if (resolved.equals(base) || !resolved.toPath().startsWith(base.toPath())) {
            throw new IOException("Path traversal rejected: " + relativePath);
        }
        return resolved;
    }
}
