package com.prism.dicomtransfer.service;

import com.prism.dicomtransfer.model.ConnectionTestResult;
import com.prism.dicomtransfer.model.TransferConfiguration;
import javafx.concurrent.Task;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ConnectionTestService {

    private static final long PROCESS_TIMEOUT_SECONDS = 45;

    public Task<ConnectionTestResult> createTestTask(
            TransferConfiguration configuration,
            Consumer<String> logConsumer
    ) {
        return new Task<>() {

            @Override
            protected ConnectionTestResult call() throws Exception {
                List<String> command = buildCommand(configuration);

                logConsumer.accept(
                        "Testing DICOM association with "
                                + configuration.calledAeTitle()
                                + "@"
                                + configuration.destinationHost()
                                + ":"
                                + configuration.destinationPort()
                );

                Instant startedAt = Instant.now();

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                List<String> output = new ArrayList<>();

                Thread outputReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    )) {
                        String line;

                        while ((line = reader.readLine()) != null) {
                            synchronized (output) {
                                output.add(line);
                            }

                            logConsumer.accept(line);
                        }
                    } catch (Exception exception) {
                        logConsumer.accept(
                                "Could not read echoscu output: "
                                        + exception.getMessage()
                        );
                    }
                }, "echoscu-output-reader");

                outputReader.setDaemon(true);
                outputReader.start();

                boolean completed = process.waitFor(
                        PROCESS_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );

                if (!completed) {
                    process.destroyForcibly();

                    throw new IllegalStateException(
                            "C-ECHO timed out after "
                                    + PROCESS_TIMEOUT_SECONDS
                                    + " seconds."
                    );
                }

                outputReader.join(2_000);

                int exitCode = process.exitValue();

                long elapsedMilliseconds = Duration.between(
                        startedAt,
                        Instant.now()
                ).toMillis();

                List<String> outputCopy;

                synchronized (output) {
                    outputCopy = List.copyOf(output);
                }

                return new ConnectionTestResult(
                        exitCode == 0,
                        exitCode,
                        elapsedMilliseconds,
                        outputCopy
                );
            }
        };
    }

    private List<String> buildCommand(
            TransferConfiguration configuration
    ) {
        List<String> command = new ArrayList<>();

        command.add(configuration.echoscuPath().toString());

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
        command.add(String.valueOf(configuration.destinationPort()));

        return command;
    }
}