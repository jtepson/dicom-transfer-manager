package com.prism.dicomtransfer.service;

import com.prism.dicomtransfer.model.TransferConfiguration;
import com.prism.dicomtransfer.model.TransferState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

public class TransferStateService {

    private static final String STATE_FILE_NAME =
            "transfer_state.properties";

    private static final String TEMP_FILE_NAME =
            "transfer_state.properties.tmp";

    public Path getStateFile(Path workingDirectory) {
        return workingDirectory.resolve(STATE_FILE_NAME);
    }

    public boolean stateExists(Path workingDirectory) {
        return Files.isRegularFile(getStateFile(workingDirectory));
    }

    public void save(TransferState state) throws IOException {
        TransferConfiguration configuration = state.configuration();
        Path workingDirectory = configuration.workingDirectory();

        Files.createDirectories(workingDirectory);

        Path stateFile = getStateFile(workingDirectory);
        Path temporaryFile = workingDirectory.resolve(TEMP_FILE_NAME);

        Properties properties = new Properties();

        properties.setProperty(
                "sourceDirectory",
                configuration.sourceDirectory().toString()
        );

        properties.setProperty(
                "workingDirectory",
                configuration.workingDirectory().toString()
        );

        properties.setProperty(
                "storescuPath",
                configuration.storescuPath().toString()
        );

        properties.setProperty(
                "echoscuPath",
                configuration.echoscuPath().toString()
        );

        properties.setProperty(
                "callingAeTitle",
                configuration.callingAeTitle()
        );

        properties.setProperty(
                "calledAeTitle",
                configuration.calledAeTitle()
        );

        properties.setProperty(
                "host",
                configuration.destinationHost()
        );

        properties.setProperty(
                "port",
                Integer.toString(configuration.destinationPort())
        );

        properties.setProperty(
                "workerCount",
                Integer.toString(configuration.parallelWorkers())
        );

        properties.setProperty(
                "batchSize",
                Integer.toString(configuration.filesPerBatch())
        );

        properties.setProperty(
                "skipPreviouslySent",
                Boolean.toString(configuration.skipPreviouslySent())
        );

        properties.setProperty(
                "totalFiles",
                Long.toString(state.totalFiles())
        );

        properties.setProperty(
                "successfulFiles",
                Long.toString(state.successfulFiles())
        );

        properties.setProperty(
                "failedFiles",
                Long.toString(state.failedFiles())
        );

        properties.setProperty(
                "status",
                state.status().name()
        );

        properties.setProperty(
                "startedAt",
                state.startedAt().toString()
        );

        properties.setProperty(
                "updatedAt",
                state.updatedAt().toString()
        );

        try (OutputStream outputStream =
                     Files.newOutputStream(temporaryFile)) {

            properties.store(
                    outputStream,
                    "DICOM Transfer Manager state"
            );
        }

        try {
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicMoveException) {
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    public Optional<TransferState> load(Path workingDirectory)
            throws IOException {

        Path stateFile = getStateFile(workingDirectory);

        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }

        Properties properties = new Properties();

        try (InputStream inputStream =
                     Files.newInputStream(stateFile)) {

            properties.load(inputStream);
        }

        TransferConfiguration configuration =
                new TransferConfiguration(
                        Path.of(required(
                                properties,
                                "sourceDirectory"
                        )),
                        Path.of(required(
                                properties,
                                "workingDirectory"
                        )),
                        Path.of(required(
                                properties,
                                "storescuPath"
                        )),
                        Path.of(required(
                                properties,
                                "echoscuPath"
                        )),
                        required(properties, "callingAeTitle"),
                        required(properties, "calledAeTitle"),
                        required(properties, "host"),
                        parseInteger(properties, "port"),
                        parseInteger(properties, "workerCount"),
                        parseInteger(properties, "batchSize"),
                        parseBoolean(
                                properties,
                                "skipPreviouslySent"
                        )
                );

        TransferState state = new TransferState(
                configuration,
                parseLong(properties, "totalFiles"),
                parseLong(properties, "successfulFiles"),
                parseLong(properties, "failedFiles"),
                TransferState.Status.valueOf(
                        required(properties, "status")
                ),
                Instant.parse(required(properties, "startedAt")),
                Instant.parse(required(properties, "updatedAt"))
        );

        return Optional.of(state);
    }

    public void delete(Path workingDirectory) throws IOException {
        Files.deleteIfExists(getStateFile(workingDirectory));
        Files.deleteIfExists(
                workingDirectory.resolve(TEMP_FILE_NAME)
        );
    }

    private String required(
            Properties properties,
            String key
    ) throws IOException {

        String value = properties.getProperty(key);

        if (value == null || value.isBlank()) {
            throw new IOException(
                    "Transfer state is missing property: " + key
            );
        }

        return value.trim();
    }

    private int parseInteger(
            Properties properties,
            String key
    ) throws IOException {

        String value = required(properties, key);

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Invalid integer in transfer state for "
                            + key
                            + ": "
                            + value,
                    exception
            );
        }
    }

    private long parseLong(
            Properties properties,
            String key
    ) throws IOException {

        String value = required(properties, key);

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Invalid long value in transfer state for "
                            + key
                            + ": "
                            + value,
                    exception
            );
        }
    }

    private boolean parseBoolean(
            Properties properties,
            String key
    ) throws IOException {

        String value = required(properties, key);

        if ("true".equalsIgnoreCase(value)) {
            return true;
        }

        if ("false".equalsIgnoreCase(value)) {
            return false;
        }

        throw new IOException(
                "Invalid boolean in transfer state for "
                        + key
                        + ": "
                        + value
        );
    }
}