package com.prism.dicomtransfer.service;

import com.prism.dicomtransfer.model.TransferConfiguration;
import com.prism.dicomtransfer.model.TransferProgress;
import com.prism.dicomtransfer.model.TransferResult;
import com.prism.dicomtransfer.model.TransferState;
import javafx.concurrent.Task;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TransferEngineService {

    private static final long PROCESS_TIMEOUT_MINUTES = 30;

    // Windows allows a command line of roughly 32,767 characters. Stay below that limit to leave room for Java and ProcessBuilder quoting/escaping.
    private static final int SAFE_WINDOWS_COMMAND_LENGTH = 28_000;

    private final Set<Process> activeProcesses =
            ConcurrentHashMap.newKeySet();

    private final TransferStateService transferStateService =
            new TransferStateService();

    private final Object transferStateLock = new Object();
    
    private volatile boolean stopRequested;

    private volatile TransferConfiguration activeConfiguration;
    private volatile Instant activeStartedAt;
    private final AtomicLong activeSuccessfulFileCount = new AtomicLong();
    private final AtomicLong activeFailedFileCount = new AtomicLong();
    private volatile long activeTotalFiles;
    private volatile TransferListener activeListener;

    public Task<TransferResult> createTransferTask(
            TransferConfiguration configuration,
            List<Path> files,
            TransferListener listener
    ) {
        return new Task<>() {

            @Override
            protected TransferResult call() throws Exception {
                stopRequested = false;
                activeProcesses.clear();

                Instant startedAt = Instant.now();

                saveTransferState(
                        configuration,
                        files.size(),
                        0,
                        0,
                        TransferState.Status.RUNNING,
                        startedAt,
                        listener
                );

                List<List<Path>> batches = createSafeBatches(
                        configuration,
                        files,
                        listener
                );

                int totalBatches = batches.size();

                ConcurrentLinkedQueue<List<Path>> queue =
                        new ConcurrentLinkedQueue<>(batches);

                ConcurrentLinkedQueue<Path> failedFiles =
                        new ConcurrentLinkedQueue<>();

                AtomicLong successfulFileCount = new AtomicLong();
                AtomicLong failedFileCount = new AtomicLong();

                AtomicInteger successfulBatchCount = new AtomicInteger();
                AtomicInteger failedBatchCount = new AtomicInteger();
                AtomicInteger completedBatchCount = new AtomicInteger();

                Path sentFilesPath =
                        configuration.workingDirectory()
                                .resolve("sent_files.txt");

                Files.createDirectories(configuration.workingDirectory());

                if (!Files.exists(sentFilesPath)) {
                    Files.createFile(sentFilesPath);
                }

                listener.onLog(
                        String.format(
                                Locale.US,
                                "Starting transfer of %,d files in %,d batches with %d workers.",
                                files.size(),
                                totalBatches,
                                configuration.parallelWorkers()
                        )
                );

                ExecutorService workerPool = Executors.newFixedThreadPool(
                        configuration.parallelWorkers(),
                        runnable -> {
                            Thread thread = new Thread(runnable);
                            thread.setDaemon(true);
                            thread.setName("storescu-worker");
                            return thread;
                        }
                );

                List<Future<?>> workerFutures = new ArrayList<>();

                for (
                        int workerNumber = 1;
                        workerNumber <= configuration.parallelWorkers();
                        workerNumber++
                ) {
                    int currentWorker = workerNumber;

                    workerFutures.add(
                            workerPool.submit(() -> {
                                runWorker(
                                        currentWorker,
                                        configuration,
                                        queue,
                                        sentFilesPath,
                                        successfulFileCount,
                                        failedFileCount,
                                        successfulBatchCount,
                                        failedBatchCount,
                                        completedBatchCount,
                                        totalBatches,
                                        files.size(),
                                        failedFiles,
                                        startedAt,
                                        listener
                                );
                            })
                    );
                }

                workerPool.shutdown();

                try {
                    while (!workerPool.awaitTermination(1, TimeUnit.SECONDS)) {
                        if (isCancelled() || stopRequested) {
                            stopRequested = true;
                            destroyActiveProcesses();
                            workerPool.shutdownNow();
                        }

                        publishProgress(
                                successfulFileCount.get(),
                                failedFileCount.get(),
                                completedBatchCount.get(),
                                totalBatches,
                                files.size(),
                                startedAt,
                                listener
                        );
                    }
                } catch (InterruptedException exception) {
                    stopRequested = true;
                    destroyActiveProcesses();
                    workerPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }

                for (Future<?> future : workerFutures) {
                    try {
                        future.get();
                    } catch (Exception exception) {
                        listener.onLog(
                                "Worker terminated with an error: "
                                        + rootMessage(exception)
                        );
                    }
                }

                publishProgress(
                        successfulFileCount.get(),
                        failedFileCount.get(),
                        completedBatchCount.get(),
                        totalBatches,
                        files.size(),
                        startedAt,
                        listener
                );

                Duration elapsed = Duration.between(
                        startedAt,
                        Instant.now()
                );

                listener.onLog(
                        String.format(
                                Locale.US,
                                "Transfer finished: %,d successful, %,d failed.",
                                successfulFileCount.get(),
                                failedFileCount.get()
                        )
                );

                boolean stopped = stopRequested || isCancelled();

                TransferState.Status finalStatus;

                if (stopped) {
                    finalStatus = TransferState.Status.STOPPED;
                } else if (failedFileCount.get() > 0) {
                    finalStatus = TransferState.Status.COMPLETED_WITH_ERRORS;
                } else {
                    finalStatus = TransferState.Status.COMPLETED;
                }

                saveTransferState(
                        configuration,
                        files.size(),
                        successfulFileCount.get(),
                        failedFileCount.get(),
                        finalStatus,
                        startedAt,
                        listener
                );

                return new TransferResult(
                        files.size(),
                        successfulFileCount.get(),
                        failedFileCount.get(),
                        successfulBatchCount.get(),
                        failedBatchCount.get(),
                        elapsed,
                        stopped,
                        List.copyOf(failedFiles)
                );
            }
        };
    }

    public void requestStop() {
        stopRequested = true;
        destroyActiveProcesses();
    }

    private void runWorker(
            int workerNumber,
            TransferConfiguration configuration,
            ConcurrentLinkedQueue<List<Path>> queue,
            Path sentFilesPath,
            AtomicLong successfulFileCount,
            AtomicLong failedFileCount,
            AtomicInteger successfulBatchCount,
            AtomicInteger failedBatchCount,
            AtomicInteger completedBatchCount,
            int totalBatches,
            long totalFiles,
            ConcurrentLinkedQueue<Path> failedFiles,
            Instant startedAt,
            TransferListener listener
    ) {
        while (!stopRequested) {
            List<Path> batch = queue.poll();

            if (batch == null) {
                return;
            }

            int batchNumber = completedBatchCount.get() + 1;

            listener.onLog(
                    String.format(
                            Locale.US,
                            "Worker %d starting batch %d/%d with %,d files.",
                            workerNumber,
                            batchNumber,
                            totalBatches,
                            batch.size()
                    )
            );

            BatchExecutionResult executionResult;

            try {
                executionResult = executeBatch(
                        workerNumber,
                        configuration,
                        batch,
                        listener
                );
            } catch (Exception exception) {
                executionResult = new BatchExecutionResult(
                        false,
                        -1,
                        rootMessage(exception)
                );
            }

            if (stopRequested) {
                // Do not mark an interrupted batch as sent or permanently failed. Its paths remain absent from sent_files.txt and will therefore be available on the next scan/resume.
                listener.onLog(
                        "Worker "
                                + workerNumber
                                + " stopped during its current batch."
                );

                return;
            }

            if (executionResult.successful()) {
                try {
                    appendSentFiles(sentFilesPath, batch);

                    successfulFileCount.addAndGet(batch.size());
                    successfulBatchCount.incrementAndGet();

                    listener.onLog(
                            String.format(
                                    Locale.US,
                                    "Worker %d completed batch successfully.",
                                    workerNumber
                            )
                    );
                } catch (IOException exception) {
                    failedFileCount.addAndGet(batch.size());
                    failedFiles.addAll(batch);
                    failedBatchCount.incrementAndGet();

                    listener.onLog(
                            "Batch was transmitted but could not be recorded in "
                                    + "sent_files.txt: "
                                    + exception.getMessage()
                    );
                }
            } else {
                failedFileCount.addAndGet(batch.size());
                failedFiles.addAll(batch);
                failedBatchCount.incrementAndGet();

                listener.onLog(
                        String.format(
                                Locale.US,
                                "Worker %d batch failed with exit code %d: %s",
                                workerNumber,
                                executionResult.exitCode(),
                                executionResult.message()
                        )
                );
            }

            int completed = completedBatchCount.incrementAndGet();

            publishProgress(
                    successfulFileCount.get(),
                    failedFileCount.get(),
                    completed,
                    totalBatches,
                    totalFiles,
                    startedAt,
                    listener
            );

            saveTransferState(
                    configuration,
                    totalFiles,
                    successfulFileCount.get(),
                    failedFileCount.get(),
                    TransferState.Status.RUNNING,
                    startedAt,
                    listener
            );
        }
    }

    private BatchExecutionResult executeBatch(
            int workerNumber,
            TransferConfiguration configuration,
            List<Path> batch,
            TransferListener listener
    ) throws Exception {
        List<String> command = buildStorescuCommand(
                configuration,
                batch
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        activeProcesses.add(process);

        StringBuilder finalOutput = new StringBuilder();

        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    synchronized (finalOutput) {
                        if (finalOutput.length() > 4_000) {
                            finalOutput.delete(0, 2_000);
                        }

                        finalOutput.append(line).append(' ');
                    }

                    // DCMTK can be verbose. Log warnings and errors while suppressing routine per-instance output.
                    String lowercase = line.toLowerCase(Locale.ROOT);

                    if (
                            lowercase.contains("error")
                                    || lowercase.contains("warning")
                                    || lowercase.contains("failed")
                    ) {
                        listener.onLog(
                                "Worker " + workerNumber + ": " + line
                        );
                    }
                }
            } catch (IOException exception) {
                if (!stopRequested) {
                    listener.onLog(
                            "Worker "
                                    + workerNumber
                                    + " output error: "
                                    + exception.getMessage()
                    );
                }
            }
        }, "storescu-output-" + workerNumber);

        outputReader.setDaemon(true);
        outputReader.start();

        boolean completed = process.waitFor(
                PROCESS_TIMEOUT_MINUTES,
                TimeUnit.MINUTES
        );

        if (!completed) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);

            return new BatchExecutionResult(
                    false,
                    -1,
                    "storescu timed out after "
                            + PROCESS_TIMEOUT_MINUTES
                            + " minutes"
            );
        }

        outputReader.join(2_000);

        int exitCode = process.exitValue();

        activeProcesses.remove(process);

        String message;

        synchronized (finalOutput) {
            message = finalOutput.toString().trim();
        }

        if (message.isBlank()) {
            message = exitCode == 0
                    ? "Completed"
                    : "No diagnostic output returned";
        }

        return new BatchExecutionResult(
                exitCode == 0,
                exitCode,
                message
        );
    }

    private List<String> buildStorescuCommand(
            TransferConfiguration configuration,
            List<Path> batch
    ) {
        List<String> command = new ArrayList<>();

        command.add(configuration.storescuPath().toString());

        command.add("-aet");
        command.add(configuration.callingAeTitle());

        command.add("-aec");
        command.add(configuration.calledAeTitle());

        //this allows us to send jpeg lossless without converting via dcmtk
        command.add("-xs");

        command.add("-to");
        command.add("10");

        command.add("-ta");
        command.add("30");

        command.add("-td");
        command.add("30");

        command.add(configuration.destinationHost());
        command.add(String.valueOf(configuration.destinationPort()));

        for (Path file : batch) {
            command.add(file.toAbsolutePath().normalize().toString());
        }

        return command;
    }

    private List<List<Path>> createSafeBatches(
            TransferConfiguration configuration,
            List<Path> files,
            TransferListener listener
    ) {
        if (files.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Path>> batches = new ArrayList<>();
        List<Path> currentBatch = new ArrayList<>();

        int currentCommandLength = estimateBaseCommandLength(configuration);

        for (Path file : files) {
            String fileArgument =
                    file.toAbsolutePath().normalize().toString();

            int addedLength = fileArgument.length() + 3;

            boolean reachedRequestedSize =
                    currentBatch.size() >= configuration.filesPerBatch();

            boolean reachedSafeCommandLength =
                    !currentBatch.isEmpty()
                            && currentCommandLength + addedLength
                            > SAFE_WINDOWS_COMMAND_LENGTH;

            if (reachedRequestedSize || reachedSafeCommandLength) {
                batches.add(List.copyOf(currentBatch));
                currentBatch.clear();
                currentCommandLength =
                        estimateBaseCommandLength(configuration);
            }

            currentBatch.add(file);
            currentCommandLength += addedLength;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(List.copyOf(currentBatch));
        }

        boolean reducedByCommandLength = batches.stream()
                .anyMatch(batch ->
                        batch.size() < configuration.filesPerBatch()
                                && batch.size() != files.size()
                );

        if (reducedByCommandLength) {
            listener.onLog(
                    "Some batches were reduced to remain within the "
                            + "Windows command-length limit."
            );
        }

        return List.copyOf(batches);
    }

    private int estimateBaseCommandLength(
            TransferConfiguration configuration
    ) {
        return configuration.storescuPath().toString().length()
                + configuration.callingAeTitle().length()
                + configuration.calledAeTitle().length()
                + configuration.destinationHost().length()
                + 200;
    }

    private void appendSentFiles(
            Path sentFilesPath,
            List<Path> files
    ) throws IOException {
        synchronized (this) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    sentFilesPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                for (Path file : files) {
                    writer.write(normalizePath(file));
                    writer.newLine();
                }

                writer.flush();
            }
        }
    }

    private void publishProgress(
            long successfulFiles,
            long failedFiles,
            int completedBatches,
            int totalBatches,
            long totalFiles,
            Instant startedAt,
            TransferListener listener
    ) {
        Duration elapsed = Duration.between(
                startedAt,
                Instant.now()
        );

        double seconds = Math.max(
                0.001,
                elapsed.toMillis() / 1_000.0
        );

        double rate = successfulFiles / seconds;

        listener.onProgress(
                new TransferProgress(
                        totalFiles,
                        successfulFiles,
                        failedFiles,
                        completedBatches,
                        totalBatches,
                        rate,
                        elapsed
                )
        );
    }

    private void destroyActiveProcesses() {
        for (Process process : activeProcesses) {
            try {
                process.destroy();

                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
                process.destroyForcibly();
            }
        }

        activeProcesses.clear();
    }

    private String normalizePath(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/');
    }

    private void saveTransferState(
            TransferConfiguration configuration,
            long totalFiles,
            long successfulFiles,
            long failedFiles,
            TransferState.Status status,
            Instant startedAt,
            TransferListener listener
    ) {
        TransferState state = new TransferState(
                configuration,
                totalFiles,
                successfulFiles,
                failedFiles,
                status,
                startedAt,
                Instant.now()
        );

        try {
            synchronized (transferStateLock) {
                transferStateService.save(state);
            }
        } catch (IOException exception) {
            listener.onLog(
                    "Could not save transfer state: "
                            + exception.getMessage()
            );
        }
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;

        while (
                current.getCause() != null
                        && current.getCause() != current
        ) {
            current = current.getCause();
        }

        String message = current.getMessage();

        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private record BatchExecutionResult(
            boolean successful,
            int exitCode,
            String message
    ) {
    }
}