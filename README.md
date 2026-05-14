# java-csv-datadog-demo

A Java application that reads a pipe-delimited CSV file, validates and enriches each row, writes a clean output CSV, and ships structured logs and traces to Datadog via the dd-java-agent.

## Flow

```mermaid
flowchart TD
    A([Start]) --> B[Read sample-input.txt\nline by line]
    B --> C{Line blank?}
    C -- Yes --> D[WARN: skip blank line]
    C -- No --> E[parseLine\npipe-split into id, customer_name, amount]
    E --> F{3 columns\nid valid\namount positive?}
    F -- No --> G[InvalidRecordException\nERROR log → invalidRows++]
    F -- Yes --> H[enrichRow\nsimulated external lookup]
    H --> I{lineNumber % 3}
    I -- mod 0 --> J[getCategory\nreturns null for amount ≤ 100]
    J --> K{null?}
    K -- Yes --> L[NullPointerException\nERROR log → invalidRows++]
    K -- No --> M[category.toUpperCase]
    I -- mod 1 --> N[Thread.sleep 250 ms\nexceeds 100 ms SLA]
    N --> O[TimeoutException\nERROR log → invalidRows++]
    I -- mod 2 --> P[No enrichment]
    M --> Q[csvPrinter.printRecord\nvalidRows++]
    P --> Q
    Q --> R[Write parsed-output.csv]
    D --> B
    G --> B
    L --> B
    O --> B
    R --> S([ProcessingReport\nvalid / invalid counts])

    style G fill:#f66,color:#fff
    style L fill:#f66,color:#fff
    style O fill:#f66,color:#fff
```

## Architecture

```mermaid
flowchart LR
    subgraph Docker Compose
        App["app container\njava-csv-datadog-demo\n(dd-java-agent attached)"]
        DD["datadog-agent container\n:8126 APM  :8125 StatsD"]
    end
    App -- "traces (APM)" --> DD
    App -- "structured JSON logs\n(stdout → container log)" --> DD
    DD -- "logs + traces" --> Cloud[(Datadog Cloud\nus5.datadoghq.com)]
```

## Intentional Bugs

| Error | Trigger | Root Cause |
|-------|---------|------------|
| `NullPointerException` | `lineNumber % 3 == 0`, amount ≤ 100 | `getCategory()` returns `null`; caller calls `.toUpperCase()` without null check |
| `TimeoutException` | `lineNumber % 3 == 1` | `enrichRow` sleeps 250 ms but SLA threshold is 100 ms |
| `InvalidRecordException` | Malformed lines (bad amount, missing columns) | `parseLine` validates format strictly |

## Prerequisites

- Docker + Docker Compose
- Datadog account — API key with `logs_write` scope
- `.env` file (or shell env vars):

```
DD_API_KEY=<your-api-key>
DD_SITE=us5.datadoghq.com   # or datadoghq.com
```

## Running

```bash
# Build and run (produces errors intentionally)
docker-compose up --build

# Or run locally with Gradle (requires dd-java-agent built first)
./gradlew run
```

Input: `data/sample-input.txt` — Output: `data/parsed-output.csv`

## Project Structure

```
src/main/java/com/example/csvdatadog/
├── App.java                  # Entry point
├── CsvFileProcessor.java     # Core parsing, enrichment, error handling
├── InvalidRecordException.java
└── ProcessingReport.java
data/
├── sample-input.txt          # Pipe-delimited input with intentional bad rows
└── parsed-output.csv         # Clean output (valid rows only)
```
