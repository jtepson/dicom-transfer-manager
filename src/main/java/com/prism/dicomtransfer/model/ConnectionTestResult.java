package com.prism.dicomtransfer.model;

import java.util.List;

public record ConnectionTestResult(
        boolean successful,
        int exitCode,
        long elapsedMilliseconds,
        List<String> output
) {
}