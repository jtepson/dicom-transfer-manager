package com.prism.dicomtransfer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BatchBuilder {

    private static final int SAFE_WINDOWS_COMMAND_LENGTH = 28_000;

    public List<List<Path>> createBatches(
            List<Path> files,
            int filesPerBatch,
            int estimatedBaseCommandLength
    ) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        if (filesPerBatch <= 0) {
            throw new IllegalArgumentException(
                    "Files per batch must be greater than zero."
            );
        }

        List<List<Path>> batches = new ArrayList<>();
        List<Path> currentBatch = new ArrayList<>();

        int currentCommandLength = estimatedBaseCommandLength;

        for (Path file : files) {
            if (file == null) {
                continue;
            }

            String filePath = file.toAbsolutePath()
                    .normalize()
                    .toString();

            // Account for the path itself, surrounding quotes, and a separating space.
            int fileArgumentLength = filePath.length() + 3;

            boolean batchIsFull =
                    currentBatch.size() >= filesPerBatch;

            boolean commandWouldBeTooLong =
                    !currentBatch.isEmpty()
                            && currentCommandLength + fileArgumentLength
                            > SAFE_WINDOWS_COMMAND_LENGTH;

            if (batchIsFull || commandWouldBeTooLong) {
                batches.add(List.copyOf(currentBatch));

                currentBatch.clear();
                currentCommandLength = estimatedBaseCommandLength;
            }

            currentBatch.add(file);
            currentCommandLength += fileArgumentLength;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(List.copyOf(currentBatch));
        }

        return List.copyOf(batches);
    }
}