package com.prism.dicomtransfer.model;

import java.nio.file.Path;

public record TransferConfiguration(
        Path sourceDirectory,
        Path workingDirectory,
        Path storescuPath,
        Path echoscuPath,
        String callingAeTitle,
        String calledAeTitle,
        String destinationHost,
        int destinationPort,
        int parallelWorkers,
        int filesPerBatch,
        boolean skipPreviouslySent
) {
}