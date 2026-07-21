package com.prism.dicomtransfer;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

public class MainController {

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
    private void initialize() {
        portSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(
                        1,
                        65535,
                        11112
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
                        5000,
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

        appendLog("Application started.");
    }

    @FXML
    private void browseSourceDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select DICOM Source Directory");

        setInitialDirectory(chooser, sourceDirectoryField.getText());

        File selected = chooser.showDialog(sourceDirectoryField.getScene().getWindow());

        if (selected != null) {
            sourceDirectoryField.setText(selected.getAbsolutePath());
            appendLog("Source directory selected: " + selected.getAbsolutePath());
        }
    }

    @FXML
    private void browseWorkDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Transfer Working Directory");

        setInitialDirectory(chooser, workDirectoryField.getText());

        File selected = chooser.showDialog(workDirectoryField.getScene().getWindow());

        if (selected != null) {
            workDirectoryField.setText(selected.getAbsolutePath());
            appendLog("Working directory selected: " + selected.getAbsolutePath());
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

        File selected = chooser.showOpenDialog(storescuPathField.getScene().getWindow());

        if (selected != null) {
            storescuPathField.setText(selected.getAbsolutePath());
            appendLog("storescu selected: " + selected.getAbsolutePath());
        }
    }

    @FXML
    private void scanSource() {
        String sourceText = sourceDirectoryField.getText().trim();

        if (sourceText.isBlank()) {
            showWarning("Select a source directory first.");
            return;
        }

        Path source = Path.of(sourceText);

        if (!source.toFile().isDirectory()) {
            showWarning("The selected source directory does not exist.");
            return;
        }

        scanStatusLabel.setText("Source is ready to scan.");
        appendLog("Source validation succeeded: " + source);
        appendLog("File scanning will be implemented next.");
    }

    @FXML
    private void testConnection() {
        appendLog(
                "Connection test requested for "
                        + calledAeField.getText().trim()
                        + "@"
                        + hostField.getText().trim()
                        + ":"
                        + portSpinner.getValue()
        );

        appendLog("C-ECHO implementation will be added next.");
    }

    @FXML
    private void startTransfer() {
        if (!validateTransferSettings()) {
            return;
        }

        startButton.setDisable(true);
        stopButton.setDisable(false);
        transferStatusLabel.setText("Preparing");
        appendLog("Transfer preparation started.");
        appendLog("Transfer engine will be implemented after directory scanning.");
    }

    @FXML
    private void stopTransfer() {
        stopButton.setDisable(true);
        startButton.setDisable(false);
        transferStatusLabel.setText("Stopped");
        appendLog("Stop requested.");
    }

    private boolean validateTransferSettings() {
        if (sourceDirectoryField.getText().isBlank()) {
            showWarning("Select a source directory.");
            return false;
        }

        if (workDirectoryField.getText().isBlank()) {
            showWarning("Select a working directory.");
            return false;
        }

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

    private void appendLog(String message) {
        logTextArea.appendText(message + System.lineSeparator());
    }

    private void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("DICOM Transfer Manager");
        alert.setHeaderText(null);
        alert.setContentText(message);
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
}