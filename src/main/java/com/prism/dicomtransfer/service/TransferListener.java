package com.prism.dicomtransfer.service;

import com.prism.dicomtransfer.model.TransferProgress;

public interface TransferListener {

    void onProgress(TransferProgress progress);

    void onLog(String message);
}