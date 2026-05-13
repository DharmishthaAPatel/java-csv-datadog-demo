package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.cdimascio.dotenv.Dotenv;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j agent that:
 *  1. Fetches Datadog logs and embeds them into an in-memory vector store (RAG).
 *  2. Uses GPT-4o via LangChain4j AiServices + @Tool methods to diagnose,
 *     fix Java source files, push a git branch, and create a GitHub PR.
 *
 * Required env vars:
 *   OPENAI_API_KEY, DD_API_KEY, DD_APP_KEY,
 *   DD_SITE (default datadoghq.com), GITHUB_TOKEN, GITHUB_REPO (owner/repo)
 */
public class LogFixAgent {

    // ── config ────────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON     = new ObjectMapper();
    private static final Path         REPO_ROOT = Paths.get(System.getProperty("user.dir"));
    private static final Path         SRC_ROOT  =
            REPO_ROOT.resolve("src/main/java/com/example/csvdatadog");

    private static final Dotenv DOTENV = Dotenv.configure()
            .filename(".env")
            .ignoreIfMissing()   // still works if only real env vars are set
            .load();

    private static final String OPENAI_API_KEY = cfg("OPENAI_API_KEY", null);
    private static final String DD_SITE  = cfg("DD_SITE",  "datadoghq.com");
    private static final String DD_API   = cfg("DD_API_KEY",  null);
    private static final String DD_APP   = cfg("DD_APP_KEY",  null);
    private static final String GH_TOKEN = cfg("GITHUB_TOKEN", null);
    private static final String GH_REPO  = cfg("GITHUB_REPO",  null);

    private static final OkHttpClient HTTP = new OkHttpClient();

    // ── LangChain4j AiService interface ──────────────────────────────────────

    @SystemMessage("""
            You are a senior software engineer and SRE agent for the java-csv-datadog-demo project.

            Relevant Datadog logs have already been retrieved and injected into your context via RAG.
            Use them to understand what went wrong.

            Workflow:
            1. Study the retrieved log context to identify errors or anomalies.
            2. Call listSourceFiles, then readFile on any file that looks relevant.
            3. Fix the bug by calling writeFile with the corrected content.
            4. Run git commands via runShell: checkout branch → add → commit → push.
            5. Call createPullRequest with a clear title and root-cause description.

            If no errors are found in the logs, say so and do not create a PR.
            Preserve existing code style. Only change what the logs indicate is broken.
            """)
    interface LogFixAssistant {
        String analyze(String task);
    }

    // ── @Tool methods (LangChain4j discovers these automatically) ─────────────

    static class RepoTools {

        @Tool("List all Java source files in the project. Call this before reading files.")
        public String listSourceFiles() throws IOException {
            List<String> files = new ArrayList<>();
            Files.walk(SRC_ROOT)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> files.add(REPO_ROOT.relativize(p).toString()));
            return String.join("\n", files);
        }

        @Tool("Read the full content of a Java source file.")
        public String readFile(@P("Relative path from repo root, e.g. src/main/java/com/example/csvdatadog/App.java") String path)
                throws IOException {
            Path full = REPO_ROOT.resolve(path);
            if (!Files.exists(full)) return "ERROR: not found: " + path;
            return Files.readString(full);
        }

        @Tool("Overwrite a Java source file with fixed content. Use only when you have identified a real bug.")
        public String writeFile(
                @P("Relative path from repo root") String path,
                @P("Complete new file content with the bug fixed") String content)
                throws IOException {
            Path full = REPO_ROOT.resolve(path);
            Files.createDirectories(full.getParent());
            Files.writeString(full, content);
            return "Written " + content.length() + " chars to " + path;
        }

        @Tool("Run a shell command in the repository root. Use for git operations.")
        public String runShell(@P("Shell command, e.g. 'git checkout -b fix/null-pointer'") String command)
                throws IOException, InterruptedException {
            Process p = new ProcessBuilder("sh", "-c", command)
                    .directory(REPO_ROOT.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.isBlank() ? "(no output)" : out.trim();
        }

        @Tool("Open a GitHub pull request from a feature branch to main.")
        public String createPullRequest(
                @P("Git branch name, e.g. fix/null-pointer-in-csv-processor") String branch,
                @P("Short PR title") String title,
                @P("Markdown PR body: root cause, fix summary, test notes") String body)
                throws IOException {
            String payload = JSON.writeValueAsString(
                    java.util.Map.of("title", title, "body", body, "head", branch, "base", "main"));
            Request req = new Request.Builder()
                    .url("https://api.github.com/repos/" + GH_REPO + "/pulls")
                    .addHeader("Authorization", "Bearer " + GH_TOKEN)
                    .addHeader("Accept",        "application/vnd.github+json")
                    .post(RequestBody.create(payload, MediaType.get("application/json")))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful())
                    return "GitHub error " + resp.code() + ": " + resp.body().string();
                JsonNode data = JSON.readTree(resp.body().string());
                return "PR created: " + data.path("html_url").asText();
            }
        }
    }

    // ── RAG: fetch + embed Datadog logs ───────────────────────────────────────

    private static List<Document> fetchAndBuildDocuments(int minutes, String query)
            throws IOException {
        Instant now  = Instant.now();
        Instant from = now.minus(minutes, ChronoUnit.MINUTES);

        String body = JSON.writeValueAsString(java.util.Map.of(
                "filter", java.util.Map.of(
                        "query", query,
                        "from",  from.toString(),
                        "to",    now.toString()),
                "sort", "-timestamp",
                "page", java.util.Map.of("limit", 500)));

        Request req = new Request.Builder()
                .url("https://api." + DD_SITE + "/api/v2/logs/events/search")
                .addHeader("DD-API-KEY",         DD_API)
                .addHeader("DD-APPLICATION-KEY", DD_APP)
                .addHeader("Content-Type",       "application/json")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();

        List<Document> docs = new ArrayList<>();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("Datadog error: " + resp.code());
                return docs;
            }
            ArrayNode events = (ArrayNode) JSON.readTree(resp.body().string()).path("data");
            System.out.println("Fetched " + events.size() + " log entries from Datadog.");
            events.forEach(e -> {
                JsonNode a = e.path("attributes");
                String text = String.format("[%s] %s %s",
                        a.path("timestamp").asText(),
                        a.path("status").asText().toUpperCase(),
                        a.path("message").asText());
                Metadata meta = Metadata.from("level",    a.path("status").asText())
                        .put("trace_id", a.path("dd").path("trace_id").asText(""));
                docs.add(Document.from(text, meta));
            });
        }
        return docs;
    }

    // ── wire everything together ──────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        validateEnv();

        // 1. RAG: fetch Datadog logs and embed them
        System.out.println("Fetching Datadog logs…");
        List<Document> logDocs = fetchAndBuildDocuments(
                60, "service:java-csv-datadog-demo");

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(OPENAI_API_KEY)
                .modelName("text-embedding-3-large")
                .build();
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        // Chunk → embed → store in one pipeline.
        // 500 tokens per chunk, 50 token overlap so context is not lost at boundaries.
        EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(500, 50))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build()
                .ingest(logDocs);
        System.out.println("Logs chunked and embedded into vector store.");

        EmbeddingStoreContentRetriever contentRetriever =
                EmbeddingStoreContentRetriever.builder()
                        .embeddingStore(embeddingStore)
                        .embeddingModel(embeddingModel)
                        .maxResults(30)
                        .minScore(0.0)
                        .build();

        // 2. OpenAI chat model via LangChain4j
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(OPENAI_API_KEY)
                .modelName("gpt-4o")
                .build();

        // 3. Assemble AiService with tools + RAG retriever
        RepoTools tools = new RepoTools();
        LogFixAssistant assistant = AiServices.builder(LogFixAssistant.class)
                .chatLanguageModel(chatModel)
                .tools(tools)
                .contentRetriever(contentRetriever)
                .build();

        // 4. Run the agent
        System.out.println("Running agent…\n");
        String result = assistant.analyze(
                "Analyse the Datadog logs for errors, fix any bugs in the Java source, "
              + "and open a GitHub PR describing the root cause.");

        System.out.println("\n── Agent result ──────────────────────────────");
        System.out.println(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void validateEnv() {
        for (String v : List.of("OPENAI_API_KEY", "DD_API_KEY", "DD_APP_KEY",
                                "GITHUB_TOKEN", "GITHUB_REPO")) {
            if (DOTENV.get(v) == null) {
                System.err.println("Missing required config: " + v + " (set in .env or as env var)");
                System.exit(1);
            }
        }
    }

    // dotenv checks .env first, then falls back to real environment variables
    private static String cfg(String key, String fallback) {
        String v = DOTENV.get(key);
        return v != null ? v : fallback;
    }
}
