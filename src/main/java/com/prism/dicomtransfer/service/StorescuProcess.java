package com.prism.dicomtransfer.service;

import com.prism.dicomtransfer.model.TransferConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StorescuProcess {

    private static final long PROCESS_TIMEOUT_MINUTES = 30;

    private volatile Process activeProcess;

    public Result execute(
            TransferConfiguration configuration,
            List<Path> files,
            int workerNumber,
            Consumer<String> logConsumer
    ) throws IOException, InterruptedException {

        if (files == null || files.isEmpty()) {
            return new Result(
                    true,
                    0,
                    Duration.ZERO,
                    "No files in batch."
            );
        }

        List<String> command = buildCommand(configuration, files);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Instant startedAt = Instant.now();

        activeProcess = processBuilder.start();

        StringBuilder output = new StringBuilder();

        Thread outputReader = new Thread(
                () -> readProcessOutput(
                        activeProcess,
                        output,
                        workerNumber,
                        logConsumer
                ),
                "storescu-output-" + workerNumber
        );

        outputReader.setDaemon(true);
        outputReader.start();

        boolean completed = activeProcess.waitFor(
                PROCESS_TIMEOUT_MINUTES,
                TimeUnit.MINUTES
        );

        if (!completed) {
            activeProcess.destroy();

            if (!activeProcess.waitFor(3, TimeUnit.SECONDS)) {
                activeProcess.destroyForcibly();
            }

            outputReader.join(2_000);

            return new Result(
                    false,
                    -1,
                    Duration.between(startedAt, Instant.now()),
                    "storescu timed out after "
                            + PROCESS_TIMEOUT_MINUTES
                            + " minutes."
            );
        }

        int exitCode = activeProcess.exitValue();

        outputReader.join(2_000);

        Duration elapsed = Duration.between(
                startedAt,
                Instant.now()
        );

        String finalOutput;

        synchronized (output) {
            finalOutput = output.toString().trim();
        }

        if (finalOutput.isBlank()) {
            finalOutput = exitCode == 0
                    ? "Batch completed."
                    : "storescu returned no diagnostic output.";
        }

        activeProcess = null;

        return new Result(
                exitCode == 0,
                exitCode,
                elapsed,
                finalOutput
        );
    }

    public void stop() {
        Process process = activeProcess;

        if (process == null || !process.isAlive()) {
            return;
        }

        process.destroy();

        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private List<String> buildCommand(
            TransferConfiguration configuration,
            List<Path> files
    ) {
        List<String> command = new ArrayList<>();

        command.add(
                configuration.storescuPath()
                        .toAbsolutePath()
                        .normalize()
                        .toString()
        );

        command.add("-aet");
        command.add(configuration.callingAeTitle());

        command.add("-aec");
        command.add(configuration.calledAeTitle());

        command.add("-to");
        command.add("10");

        command.add("-ta");
        command.add("30");

        command.add("-td");
        command.add("30");

        command.add(configuration.destinationHost());

        command.add(
                String.valueOf(configuration.destinationPort())
        );

        for (Path file : files) {
            command.add(
                    file.toAbsolutePath()
                            .normalize()
                            .toString()
            );
        }

        return command;
    }

    private void readProcessOutput(
            Process process,
            StringBuilder output,
            int workerNumber,
            Consumer<String> logConsumer
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                )
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() > 8_000) {
                        output.delete(0, 4_000);
                    }

                    output.append(line).append(System.lineSeparator());
                }

                String lowerLine = line.toLowerCase(Locale.ROOT);

                if (
                        lowerLine.contains("error")
                                || lowerLine.contains("warning")
                                || lowerLine.contains("failed")
                ) {
                    logConsumer.accept(
                            "Worker " + workerNumber + ": " + line
                    );
                }
            }
        } catch (IOException exception) {
            Process currentProcess = activeProcess;

            if (currentProcess != null && currentProcess.isAlive()) {
                logConsumer.accept(
                        "Worker "
                                + workerNumber
                                + " could not read storescu output: "
                                + exception.getMessage()
                );
            }
        }
    }

    public record Result(
            boolean successful,
            int exitCode,
            Duration elapsed,
            String output
    ) {
    }
}