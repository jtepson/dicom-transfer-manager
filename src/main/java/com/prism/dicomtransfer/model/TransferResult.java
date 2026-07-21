package com.prism.dicomtransfer.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record TransferResult(
        long totalFiles,
        long successfulFiles,
        long failedFiles,
        int successfulBatches,
        int failedBatches,
        Duration elapsed,
        boolean stopped,
        List<Path> failedFilePaths
) {

    public boolean successful() {
        return !stopped && failedFiles == 0;
    }
}