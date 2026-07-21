package com.prism.dicomtransfer.model;

import java.time.Duration;

public record TransferProgress(
        long totalFiles,
        long completedFiles,
        long failedFiles,
        int completedBatches,
        int totalBatches,
        double filesPerSecond,
        Duration elapsed
) {

    public long processedFiles() {
        return completedFiles + failedFiles;
    }

    public long remainingFiles() {
        return Math.max(0, totalFiles - processedFiles());
    }

    public double progressFraction() {
        if (totalFiles <= 0) {
            return 0.0;
        }

        return Math.min(
                1.0,
                (double) processedFiles() / totalFiles
        );
    }
}