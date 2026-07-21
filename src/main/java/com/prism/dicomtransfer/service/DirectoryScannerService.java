package com.prism.dicomtransfer.service;

import com.prism.dicomtransfer.model.ScanResult;
import javafx.concurrent.Task;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class DirectoryScannerService {

    public Task<ScanResult> createScanTask(
            Path sourceDirectory,
            Path workingDirectory,
            boolean skipPreviouslySent,
            Consumer<String> logConsumer
    ) {
        return new Task<>() {

            @Override
            protected ScanResult call() throws Exception {
                updateMessage("Preparing scan...");

                Files.createDirectories(workingDirectory);

                Path sentFilesPath = workingDirectory.resolve("sent_files.txt");
                Set<String> sentFiles = skipPreviouslySent
                        ? loadSentFiles(sentFilesPath, logConsumer)
                        : Set.of();

                List<Path> remainingFiles = new ArrayList<>();

                long[] totalFiles = {0};
                long[] totalBytes = {0};
                long[] previouslySent = {0};

                logConsumer.accept("Scanning: " + sourceDirectory);

                Files.walkFileTree(
                        sourceDirectory,
                        new SimpleFileVisitor<>() {

                            @Override
                            public FileVisitResult visitFile(
                                    Path file,
                                    BasicFileAttributes attributes
                            ) throws IOException {
                                if (isCancelled()) {
                                    return FileVisitResult.TERMINATE;
                                }

                                if (!attributes.isRegularFile()) {
                                    return FileVisitResult.CONTINUE;
                                }

                                totalFiles[0]++;
                                totalBytes[0] += attributes.size();

                                String normalizedPath = normalizePath(file);

                                if (skipPreviouslySent && sentFiles.contains(normalizedPath)) {
                                    previouslySent[0]++;
                                } else {
                                    remainingFiles.add(file.toAbsolutePath().normalize());
                                }

                                if (totalFiles[0] % 1_000 == 0) {
                                    updateMessage(
                                            "Scanning... "
                                                    + String.format("%,d", totalFiles[0])
                                                    + " files"
                                    );
                                }

                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(
                                    Path file,
                                    IOException exception
                            ) {
                                logConsumer.accept(
                                        "Unable to read file: "
                                                + file
                                                + " — "
                                                + exception.getMessage()
                                );

                                return FileVisitResult.CONTINUE;
                            }
                        }
                );

                if (isCancelled()) {
                    throw new InterruptedException("Scan cancelled.");
                }

                remainingFiles.sort(Path::compareTo);

                writeAllFilesList(
                        workingDirectory.resolve("all_files.txt"),
                        remainingFiles
                );

                updateMessage("Scan complete.");

                return new ScanResult(
                        totalFiles[0],
                        totalBytes[0],
                        previouslySent[0],
                        List.copyOf(remainingFiles)
                );
            }
        };
    }

    private Set<String> loadSentFiles(
            Path sentFilesPath,
            Consumer<String> logConsumer
    ) throws IOException {
        if (!Files.exists(sentFilesPath)) {
            Files.createFile(sentFilesPath);
            return new HashSet<>();
        }

        Set<String> sentFiles = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(
                sentFilesPath,
                StandardCharsets.UTF_8
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (!trimmed.isBlank()) {
                    sentFiles.add(normalizePath(Path.of(trimmed)));
                }
            }
        }

        logConsumer.accept(
                "Loaded "
                        + String.format("%,d", sentFiles.size())
                        + " previously sent file paths."
        );

        return sentFiles;
    }

    private void writeAllFilesList(
            Path outputFile,
            List<Path> files
    ) throws IOException {
        List<String> paths = files.stream()
                .map(this::normalizePath)
                .toList();

        Files.write(
                outputFile,
                paths,
                StandardCharsets.UTF_8
        );
    }

    private String normalizePath(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/');
    }
}