package com.filesync.protocol;

import java.io.IOException;

/**
 * A transfer's decoded content did not reproduce the manifest md5 the sender announced in its
 * header. The bytes are provably wrong (corrupted in transit or misframed), so unlike a {@link
 * FileWriteException} they must never be written or queued for a retry: the receiver reports the
 * path as a write failure ({@code CMD_WRITE_FAILURES}) so the sender's optimistic confirmation is
 * withdrawn, and the file is retransferred in full on the next sync.
 */
public class ManifestMismatchException extends IOException {

    public ManifestMismatchException(String message) {
        super(message);
    }
}
