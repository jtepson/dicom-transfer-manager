package com.prism.dicomtransfer.model;

import java.nio.file.Path;
import java.util.List;

public record ScanResult(
        long totalFiles,
        long totalBytes,
        long previouslySentFiles,
        List<Path> remainingFiles
) {

    public long remainingFileCount() {
        return remainingFiles.size();
    }
}