package com.example.csvdatadog;

import datadog.trace.api.Trace;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeoutException;

public class CsvFileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CsvFileProcessor.class);

    @Trace(operationName = "csv.process_file", resourceName = "csv_file")
    public ProcessingReport processFile(Path inputPath, Path outputPath) throws IOException {
        if (!Files.exists(inputPath)) {
            throw new IOException("Input file not found: " + inputPath.toAbsolutePath());
        }

        Path outputParent = outputPath.toAbsolutePath().getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }

        int validRows = 0;
        int invalidRows = 0;

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader("id", "customer_name", "amount")
            .build();

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat)) {

            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    logger.warn("Skipping blank line {}", lineNumber);
                    continue;
                }

                try {
                    logger.debug("Parsing line {}: {}", lineNumber, line);
                    ParsedRow row = parseLine(line, lineNumber);
                    enrichRow(row, lineNumber);
                    csvPrinter.printRecord(row.id(), row.customerName(), row.amount());
                    validRows++;
                    logger.info("Parsed line {} successfully", lineNumber);
                } catch (InvalidRecordException exception) {
                    invalidRows++;
                    logger.error("Invalid record at line {}: {}", lineNumber, line, exception);
                } catch (NullPointerException exception) {
                    invalidRows++;
                    logger.error("Null pointer while enriching line {}: {}", lineNumber, line, exception);
                } catch (TimeoutException exception) {
                    invalidRows++;
                    logger.error("Timeout while enriching line {}: {}", lineNumber, line, exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Processing interrupted at line " + lineNumber, exception);
                }
            }
        }

        logger.info("Finished processing with {} invalid row(s) and {} valid row(s).", invalidRows, validRows);

        return new ProcessingReport(validRows, invalidRows, outputPath.toAbsolutePath());
    }

    @Trace(operationName = "csv.parse_line", resourceName = "csv_line")
    private ParsedRow parseLine(String line, int lineNumber) {
        String[] columns = line.split("\\|", -1);
        if (columns.length != 3) {
            logger.error("Line {} has {} columns instead of 3. Content: {}", lineNumber, columns.length, line);
            throw new InvalidRecordException("Line " + lineNumber + " must have exactly 3 columns separated by ','");
        }
        
        int id;
        try {
            id = Integer.parseInt(columns[0].trim());
        } catch (NumberFormatException exception) {
            throw new InvalidRecordException("Line " + lineNumber + " has invalid id value: " + columns[0]);
        }

        String customerName = columns[1].trim();
        if (customerName.isBlank()) {
            throw new InvalidRecordException("Line " + lineNumber + " has empty customer name");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(columns[2].trim());
        } catch (NumberFormatException exception) {
            throw new InvalidRecordException("Line " + lineNumber + " has invalid amount: " + columns[2]);
        }

        if (amount.signum() <= 0) {
            throw new InvalidRecordException("Line " + lineNumber + " amount must be positive");
        }

        return new ParsedRow(id, customerName, amount);
    }

    // Simulates external enrichment (e.g. currency lookup). Fix for NPE:
    @Trace(operationName = "csv.enrich_row", resourceName = "csv_enrichment")
    private void enrichRow(ParsedRow row, int lineNumber) throws TimeoutException, InterruptedException {
        String category = getCategory(row.amount());
        if (category == null) {
            logger.error("Category is null for line {}: {}", lineNumber, row);
            throw new NullPointerException("Category cannot be null");
        }
        logger.debug("Row {} category: {}", lineNumber, category.toUpperCase());
    }

    // Method still needs to be checked for categories
    // Intentionally returns null for negative/zero amounts to trigger NPE downstream
    private String getCategory(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.valueOf(100)) > 0) return "HIGH";
        if (amount.compareTo(BigDecimal.valueOf(50)) > 0) return "MEDIUM";
        return null;
    }

    private record ParsedRow(int id, String customerName, BigDecimal amount) {
    }
}