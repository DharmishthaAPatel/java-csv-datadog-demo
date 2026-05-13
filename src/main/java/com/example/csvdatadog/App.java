package com.example.csvdatadog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        Path inputPath = args.length > 0 ? Path.of(args[0]) : Path.of("data/sample-input.txt");
        Path outputPath = args.length > 1 ? Path.of(args[1]) : Path.of("data/parsed-output.csv");

        CsvFileProcessor processor = new CsvFileProcessor();
        try {
            logger.info("Starting CSV conversion. inputPath={} outputPath={}",
                inputPath.toAbsolutePath(), outputPath.toAbsolutePath());
            ProcessingReport report = processor.processFile(inputPath, outputPath);
            logger.info("CSV conversion completed successfully. {}", report);
        } catch (Exception exception) {
            logger.error("CSV conversion failed.", exception);
            System.exit(1);
        }
    }
}
