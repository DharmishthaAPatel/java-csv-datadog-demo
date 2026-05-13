package com.example.csvdatadog;

import java.nio.file.Path;

public record ProcessingReport(int validRows, int invalidRows, Path outputPath) {
    @Override
    public String toString() {
        return "ProcessingReport{validRows=" + validRows + ", invalidRows=" + invalidRows
            + ", outputPath=" + outputPath + "}";
    }
}
