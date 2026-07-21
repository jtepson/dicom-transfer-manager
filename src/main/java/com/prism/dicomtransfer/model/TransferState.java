package com.prism.dicomtransfer.model;

import java.time.Instant;
import java.util.Objects;

public record TransferState(
        TransferConfiguration configuration,
        long totalFiles,
        long successfulFiles,
        long failedFiles,
        Status status,
        Instant startedAt,
        Instant updatedAt
) {

    public TransferState {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        if (totalFiles < 0) {
            throw new IllegalArgumentException("totalFiles cannot be negative");
        }

        if (successfulFiles < 0) {
            throw new IllegalArgumentException(
                    "successfulFiles cannot be negative"
            );
        }

        if (failedFiles < 0) {
            throw new IllegalArgumentException(
                    "failedFiles cannot be negative"
            );
        }
    }

    public long processedFiles() {
        return successfulFiles + failedFiles;
    }

    public long remainingFiles() {
        return Math.max(0, totalFiles - processedFiles());
    }

    public boolean isInterrupted() {
        return status == Status.RUNNING
                || status == Status.STOPPING;
    }

    public enum Status {
        RUNNING,
        STOPPING,
        STOPPED,
        COMPLETED,
        COMPLETED_WITH_ERRORS,
        FAILED
    }
}