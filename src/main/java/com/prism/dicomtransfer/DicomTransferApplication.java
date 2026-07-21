package com.prism.dicomtransfer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class DicomTransferApplication extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                DicomTransferApplication.class.getResource(
                        "/com/prism/dicomtransfer/main-view.fxml"
                )
        );

        Scene scene = new Scene(loader.load(), 920, 720);

        scene.getStylesheets().add(
                Objects.requireNonNull(
                        DicomTransferApplication.class.getResource(
                                "/com/prism/dicomtransfer/application.css"
                        )
                ).toExternalForm()
        );

        stage.setTitle("DICOM Transfer Manager");
        stage.setMinWidth(820);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}