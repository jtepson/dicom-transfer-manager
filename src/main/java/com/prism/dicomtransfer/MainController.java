package com.prism.dicomtransfer;

import com.prism.dicomtransfer.model.ScanResult;
import com.prism.dicomtransfer.service.DirectoryScannerService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class MainController {

    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DirectoryScannerService scannerService =
            new DirectoryScannerService();

    private final AtomicReference<Process> activeProcess =
            new AtomicReference<>();

    private Task<ScanResult> activeScanTask;
    private ScanResult latestScanResult;
    private Instant scanStartedAt;

    @FXML
    private TextField sourceDirectoryField;

    @FXML
    private TextField workDirectoryField;

    @FXML
    private TextField storescuPathField;

    @FXML
    private TextField callingAeField;

    @FXML
    private TextField calledAeField;

    @FXML
    private TextField hostField;

    @FXML
    private Spinner<Integer> portSpinner;

    @FXML
    private Spinner<Integer> workerSpinner;

    @FXML
    private Spinner<Integer> batchSizeSpinner;

    @FXML
    private CheckBox skipPreviouslySentCheckBox;

    @FXML
    private Label scanStatusLabel;

    @FXML
    private Label transferStatusLabel;

    @FXML
    private Label filesProgressLabel;

    @FXML
    private Label rateLabel;

    @FXML
    private Label elapsedLabel;

    @FXML
    private ProgressBar transferProgressBar;

    @FXML
    private TextArea logTextArea;

    @FXML
    private Button startButton;

    @FXML
    private Button stopButton;

    @FXML
    private Button scanButton;

    @FXML
    private Button testConnectionButton;

    @FXML
    private void initialize() {
        portSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(
                        1,
                        65_535,
                        11_112
                )
        );

        workerSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(
                        1,
                        32,
                        4
                )
        );

        batchSizeSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(
                        1,
                        5_000,
                        250,
                        25
                )
        );

        callingAeField.setText("BMSCACHE");
        calledAeField.setText("BMS_CACHE");
        hostField.setText("172.31.36.63");

        transferProgressBar.setProgress(0);
        transferStatusLabel.setText("Ready");
        filesProgressLabel.setText("0 / 0");
        rateLabel.setText("0.00 files/sec");
        elapsedLabel.setText("00:00:00");

        stopButton.setDisable(true);
        startButton.setDisable(true);

        appendLog("Application started.");
    }

    @FXML
    private void browseSourceDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select DICOM Source Directory");

        setInitialDirectory(chooser, sourceDirectoryField.getText());

        File selected = chooser.showDialog(
                sourceDirectoryField.getScene().getWindow()
        );

        if (selected != null) {
            sourceDirectoryField.setText(selected.getAbsolutePath());
            latestScanResult = null;
            startButton.setDisable(true);

            appendLog(
                    "Source directory selected: "
                            + selected.getAbsolutePath()
            );
        }
    }

    @FXML
    private void browseWorkDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Transfer Working Directory");

        setInitialDirectory(chooser, workDirectoryField.getText());

        File selected = chooser.showDialog(
                workDirectoryField.getScene().getWindow()
        );

        if (selected != null) {
            workDirectoryField.setText(selected.getAbsolutePath());

            appendLog(
                    "Working directory selected: "
                            + selected.getAbsolutePath()
            );
        }
    }

    @FXML
    private void browseStorescu() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select storescu");

        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "DICOM storescu",
                        "storescu.bat",
                        "storescu.exe",
                        "storescu"
                ),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        File selected = chooser.showOpenDialog(
                storescuPathField.getScene().getWindow()
        );

        if (selected != null) {
            storescuPathField.setText(selected.getAbsolutePath());

            appendLog(
                    "storescu selected: "
                            + selected.getAbsolutePath()
            );
        }
    }

    @FXML
    private void scanSource() {
        if (activeScanTask != null && activeScanTask.isRunning()) {
            activeScanTask.cancel();
            appendLog("Cancelling directory scan...");
            return;
        }

        Path sourceDirectory = validateDirectory(
                sourceDirectoryField.getText(),
                "Select a valid source directory."
        );

        if (sourceDirectory == null) {
            return;
        }

        Path workingDirectory = validateOrCreateWorkingDirectory();

        if (workingDirectory == null) {
            return;
        }

        latestScanResult = null;
        startButton.setDisable(true);
        scanStartedAt = Instant.now();

        activeScanTask = scannerService.createScanTask(
                sourceDirectory,
                workingDirectory,
                skipPreviouslySentCheckBox.isSelected(),
                this::appendLog
        );

        scanStatusLabel.textProperty().bind(activeScanTask.messageProperty());

        activeScanTask.setOnRunning(event -> {
            transferStatusLabel.setText("Scanning");
            transferProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            scanButton.setText("Cancel Scan");
            disableConfiguration(true);
            scanButton.setDisable(false);

            appendLog("Directory scan started.");
        });

        activeScanTask.setOnSucceeded(event -> {
            scanStatusLabel.textProperty().unbind();

            latestScanResult = activeScanTask.getValue();

            Duration elapsed = Duration.between(
                    scanStartedAt,
                    Instant.now()
            );

            scanStatusLabel.setText(
                    String.format(
                            Locale.US,
                            "%,d files • %s • %,d remaining",
                            latestScanResult.totalFiles(),
                            formatBytes(latestScanResult.totalBytes()),
                            latestScanResult.remainingFileCount()
                    )
            );

            filesProgressLabel.setText(
                    String.format(
                            Locale.US,
                            "0 / %,d",
                            latestScanResult.remainingFileCount()
                    )
            );

            elapsedLabel.setText(formatDuration(elapsed));
            transferProgressBar.setProgress(0);
            transferStatusLabel.setText("Ready");
            scanButton.setText("Scan Source");

            disableConfiguration(false);

            startButton.setDisable(
                    latestScanResult.remainingFileCount() == 0
            );

            appendLog(
                    String.format(
                            Locale.US,
                            "Scan complete: %,d total files, %s.",
                            latestScanResult.totalFiles(),
                            formatBytes(latestScanResult.totalBytes())
                    )
            );

            appendLog(
                    String.format(
                            Locale.US,
                            "Previously sent: %,d.",
                            latestScanResult.previouslySentFiles()
                    )
            );

            appendLog(
                    String.format(
                            Locale.US,
                            "Remaining to send: %,d.",
                            latestScanResult.remainingFileCount()
                    )
            );

            appendLog(
                    "Remaining file list written to: "
                            + workingDirectory.resolve("all_files.txt")
            );

            activeScanTask = null;
        });

        activeScanTask.setOnCancelled(event -> {
            scanStatusLabel.textProperty().unbind();
            scanStatusLabel.setText("Scan cancelled.");
            transferProgressBar.setProgress(0);
            transferStatusLabel.setText("Ready");
            scanButton.setText("Scan Source");
            disableConfiguration(false);
            startButton.setDisable(true);

            appendLog("Directory scan cancelled.");

            activeScanTask = null;
        });

        activeScanTask.setOnFailed(event -> {
            scanStatusLabel.textProperty().unbind();

            Throwable exception = activeScanTask.getException();

            scanStatusLabel.setText("Scan failed.");
            transferProgressBar.setProgress(0);
            transferStatusLabel.setText("Error");
            scanButton.setText("Scan Source");

            disableConfiguration(false);
            startButton.setDisable(true);

            appendLog(
                    "Scan failed: "
                            + (
                            exception == null
                                    ? "Unknown error"
                                    : exception.getMessage()
                    )
            );

            showError(
                    "Directory scan failed.",
                    exception
            );

            activeScanTask = null;
        });

        Thread scanThread = new Thread(
                activeScanTask,
                "dicom-directory-scanner"
        );

        scanThread.setDaemon(true);
        scanThread.start();
    }

    @FXML
    private void testConnection() {
        if (!validateDestinationSettings()) {
            return;
        }

        Path storescuPath = Path.of(
                storescuPathField.getText().trim()
        );

        if (!Files.isRegularFile(storescuPath)) {
            showWarning("The selected storescu file does not exist.");
            return;
        }

        Path echoscuPath = findEchoscuBeside(storescuPath);

        if (echoscuPath == null) {
            showWarning(
                    "Could not find echoscu beside storescu.\n\n"
                            + "Expected echoscu.bat, echoscu.exe, or echoscu "
                            + "in:\n"
                            + storescuPath.getParent()
            );
            return;
        }

        testConnectionButton.setDisable(true);
        transferStatusLabel.setText("Connecting");
        appendLog("Testing DICOM connection...");

        Task<ConnectionTestResult> connectionTask = new Task<>() {

            @Override
            protected ConnectionTestResult call() throws Exception {
                List<String> command = buildEchoCommand(echoscuPath);

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();
                activeProcess.set(process);

                List<String> outputLines = new ArrayList<>();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                process.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        outputLines.add(line);
                    }
                }

                int exitCode = process.waitFor();

                return new ConnectionTestResult(
                        exitCode,
                        outputLines
                );
            }
        };

        connectionTask.setOnSucceeded(event -> {
            activeProcess.set(null);
            testConnectionButton.setDisable(false);

            ConnectionTestResult result = connectionTask.getValue();

            result.outputLines().forEach(this::appendLog);

            if (result.exitCode() == 0) {
                transferStatusLabel.setText("Connected");
                appendLog("C-ECHO succeeded.");
            } else {
                transferStatusLabel.setText("Connection Failed");
                appendLog(
                        "C-ECHO failed with exit code "
                                + result.exitCode()
                                + "."
                );
            }
        });

        connectionTask.setOnFailed(event -> {
            activeProcess.set(null);
            testConnectionButton.setDisable(false);
            transferStatusLabel.setText("Connection Failed");

            Throwable exception = connectionTask.getException();

            appendLog(
                    "Connection test failed: "
                            + (
                            exception == null
                                    ? "Unknown error"
                                    : exception.getMessage()
                    )
            );

            showError("Connection test failed.", exception);
        });

        Thread thread = new Thread(
                connectionTask,
                "dicom-echo-test"
        );

        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void startTransfer() {
        if (!validateTransferSettings()) {
            return;
        }

        if (latestScanResult == null) {
            showWarning("Scan the source directory first.");
            return;
        }

        if (latestScanResult.remainingFileCount() == 0) {
            showWarning("No files remain to send.");
            return;
        }

        appendLog(
                "Transfer engine is the next implementation step."
        );

        appendLog(
                String.format(
                        Locale.US,
                        "Prepared %,d files using %d workers and %d files per batch.",
                        latestScanResult.remainingFileCount(),
                        workerSpinner.getValue(),
                        batchSizeSpinner.getValue()
                )
        );

        transferStatusLabel.setText("Prepared");
    }

    @FXML
    private void stopTransfer() {
        Process process = activeProcess.getAndSet(null);

        if (process != null && process.isAlive()) {
            process.destroy();
        }

        stopButton.setDisable(true);
        startButton.setDisable(false);
        transferStatusLabel.setText("Stopped");

        appendLog("Stop requested.");
    }

    private List<String> buildEchoCommand(Path echoscuPath) {
        List<String> command = new ArrayList<>();

        boolean isBatchFile = echoscuPath.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT)
                .endsWith(".bat");

        if (isBatchFile) {
            command.add("cmd.exe");
            command.add("/c");
        }

        command.add(echoscuPath.toString());
        command.add("-c");

        command.add(
                calledAeField.getText().trim()
                        + "@"
                        + hostField.getText().trim()
                        + ":"
                        + portSpinner.getValue()
        );

        command.add("-b");
        command.add(callingAeField.getText().trim());

        command.add("--connect-timeout");
        command.add("10000");

        command.add("--accept-timeout");
        command.add("30000");

        return command;
    }

    private Path findEchoscuBeside(Path storescuPath) {
        Path parent = storescuPath.getParent();

        if (parent == null) {
            return null;
        }

        List<String> candidates = List.of(
                "echoscu.bat",
                "echoscu.exe",
                "echoscu"
        );

        for (String candidate : candidates) {
            Path path = parent.resolve(candidate);

            if (Files.isRegularFile(path)) {
                return path;
            }
        }

        return null;
    }

    private boolean validateTransferSettings() {
        if (latestScanResult == null) {
            showWarning("Scan the source directory first.");
            return false;
        }

        if (!validateDestinationSettings()) {
            return false;
        }

        Path workDirectory = validateOrCreateWorkingDirectory();

        return workDirectory != null;
    }

    private boolean validateDestinationSettings() {
        if (storescuPathField.getText().isBlank()) {
            showWarning("Select the storescu executable.");
            return false;
        }

        if (callingAeField.getText().isBlank()) {
            showWarning("Enter a calling AE title.");
            return false;
        }

        if (calledAeField.getText().isBlank()) {
            showWarning("Enter a called AE title.");
            return false;
        }

        if (hostField.getText().isBlank()) {
            showWarning("Enter a destination host.");
            return false;
        }

        return true;
    }

    private Path validateDirectory(
            String value,
            String warningMessage
    ) {
        if (value == null || value.isBlank()) {
            showWarning(warningMessage);
            return null;
        }

        Path directory;

        try {
            directory = Path.of(value.trim())
                    .toAbsolutePath()
                    .normalize();
        } catch (RuntimeException exception) {
            showWarning("The directory path is invalid.");
            return null;
        }

        if (!Files.isDirectory(directory)) {
            showWarning(warningMessage);
            return null;
        }

        return directory;
    }

    private Path validateOrCreateWorkingDirectory() {
        String value = workDirectoryField.getText();

        if (value == null || value.isBlank()) {
            showWarning("Select a working directory.");
            return null;
        }

        try {
            Path directory = Path.of(value.trim())
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(directory);

            if (!Files.isWritable(directory)) {
                showWarning("The working directory is not writable.");
                return null;
            }

            return directory;
        } catch (IOException | RuntimeException exception) {
            showError(
                    "Could not create or access the working directory.",
                    exception
            );

            return null;
        }
    }

    private void disableConfiguration(boolean disabled) {
        sourceDirectoryField.setDisable(disabled);
        workDirectoryField.setDisable(disabled);
        storescuPathField.setDisable(disabled);
        callingAeField.setDisable(disabled);
        calledAeField.setDisable(disabled);
        hostField.setDisable(disabled);
        portSpinner.setDisable(disabled);
        workerSpinner.setDisable(disabled);
        batchSizeSpinner.setDisable(disabled);
        skipPreviouslySentCheckBox.setDisable(disabled);
        testConnectionButton.setDisable(disabled);
    }

    private void appendLog(String message) {
        Platform.runLater(() -> {
            String formatted = "["
                    + LocalTime.now().format(LOG_TIME_FORMAT)
                    + "] "
                    + message;

            logTextArea.appendText(
                    formatted + System.lineSeparator()
            );

            logTextArea.positionCaret(
                    logTextArea.getLength()
            );
        });
    }

    private void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("DICOM Transfer Manager");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(
            String message,
            Throwable exception
    ) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("DICOM Transfer Manager");
        alert.setHeaderText(message);

        String details = exception == null
                ? "Unknown error"
                : exception.getClass().getSimpleName()
                + ": "
                + exception.getMessage();

        alert.setContentText(details);
        alert.showAndWait();
    }

    private void setInitialDirectory(
            DirectoryChooser chooser,
            String currentValue
    ) {
        if (currentValue == null || currentValue.isBlank()) {
            return;
        }

        File current = new File(currentValue);

        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1_024) {
            return bytes + " B";
        }

        double kibibytes = bytes / 1_024.0;

        if (kibibytes < 1_024) {
            return String.format(Locale.US, "%.1f KB", kibibytes);
        }

        double mebibytes = kibibytes / 1_024.0;

        if (mebibytes < 1_024) {
            return String.format(Locale.US, "%.1f MB", mebibytes);
        }

        double gibibytes = mebibytes / 1_024.0;

        if (gibibytes < 1_024) {
            return String.format(Locale.US, "%.2f GB", gibibytes);
        }

        double tebibytes = gibibytes / 1_024.0;

        return String.format(Locale.US, "%.2f TB", tebibytes);
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.toSeconds();

        long hours = totalSeconds / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;

        return String.format(
                Locale.US,
                "%02d:%02d:%02d",
                hours,
                minutes,
                seconds
        );
    }

    private record ConnectionTestResult(
            int exitCode,
            List<String> outputLines
    ) {
    }
}