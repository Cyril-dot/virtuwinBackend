package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.PickPrediction;
import com.example.subscription.model.ScanPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Provider-neutral AI service that walks an ordered chain of vision-capable
 * providers and models until one succeeds.
 *
 * ═══════════════════════════════════════════════════════════════════
 * PROVIDER CHAIN  (default order; override with ai.providers)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  1. openrouter   https://openrouter.ai/api/v1
 *       Free, no credit card required.  Models that accept image input:
 *       - openrouter/free            Auto-router that picks any free VLM (Feb 2026)
 *       - google/gemma-4-31b-it:free 262K ctx, strong vision, 140+ languages
 *       - nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free  text+image+video+audio
 *       - google/gemma-4-26b-a4b-it:free  MoE variant, image + short video
 *       - qwen/qwen2.5-vl-72b-instruct:free  Best free OCR model on OpenRouter
 *       - moonshotai/kimi-vl-a3b-thinking:free  Thinking VLM
 *       - meta-llama/llama-3.2-11b-vision-instruct:free  Stable, long-running
 *       - minimax/minimax-m3:free    1M ctx, text+image+video
 *
 *  2. groq         https://api.groq.com/openai/v1
 *       Free developer tier; very fast inference (LPU hardware).
 *       Vision models as of Aug 2026:
 *       - qwen/qwen3.6-27b           27B, thinking+non-thinking, OCR, fast
 *       - qwen/qwen3.8-27b           Latest Qwen VL on Groq
 *       - meta-llama/llama-4-scout-17b-16e-instruct  128K ctx, 5 images
 *       Get a key at https://console.groq.com/keys
 *
 *  3. huggingface  https://router.huggingface.co/v1
 *       Free ~1,000 req/day on the serverless tier; credits reset monthly.
 *       Only models that support the Inference Providers router AND accept
 *       vision input are listed here. As of Aug 2026 the safest ones are:
 *       - meta-llama/Llama-3.2-11B-Vision-Instruct  (widely supported)
 *       - Qwen/Qwen2.5-VL-72B-Instruct              (best quality VLM on HF)
 *       - Qwen/Qwen2.5-VL-7B-Instruct               (lighter, but may be gated)
 *       NOTE: HF free credits are shared and deplete fast. If you hit 402,
 *       either top up at huggingface.co/settings/billing or remove this
 *       provider from ai.providers.
 *
 *  4. nvidia       https://integrate.api.nvidia.com/v1
 *       Free tier available via NGC. Vision models:
 *       - meta/llama-3.2-90b-vision-instruct  (best quality, slow)
 *       - meta/llama-3.2-11b-vision-instruct
 *       - nvidia/nemotron-nano-12b-v2-vl
 *       - minimaxai/minimax-m3
 *       - nvidia/nemotron-3-nano-omni-30b-a3b-reasoning
 *
 *  5. gemini       https://generativelanguage.googleapis.com/v1beta/openai
 *       Free tier on AI Studio. Only Flash-class models are free.
 *       - gemini-2.5-flash           Shuts down 2026-10-16 — remove after that
 *       - gemini-3-flash             Successor; verify availability
 *       - gemini-3.1-flash-lite      Lightest free option
 *       Set ai.gemini.api-keys for multiple keys to avoid rate limits.
 *
 *  6. cerebras     https://api.cerebras.ai/v1
 *       Free tier; only gemma-4-31b supports image input on Cerebras.
 *
 * ═══════════════════════════════════════════════════════════════════
 * MULTI-KEY SUPPORT
 * ═══════════════════════════════════════════════════════════════════
 * Any provider supports N keys via ai.<id>.api-keys=key1,key2,key3
 * Each key is expanded into its own Provider slot in the chain so a
 * 429 or 402 on key1 automatically falls through to key2 before
 * moving to the next provider entirely.
 *
 * ═══════════════════════════════════════════════════════════════════
 * RESPONSE FIELDS
 * ═══════════════════════════════════════════════════════════════════
 * Each pick now returns:
 *   teamName        — "Home Team vs Away Team" as read from the slip
 *   prediction      — 1 (home win), X (draw), 2 (away win)
 *   accuracyPercent — integer 0-100 model confidence
 *   reason          — one line ~10 words explaining the call
 */
@Service
public class NvidiaAiService {

    private static final Logger log = LoggerFactory.getLogger(NvidiaAiService.class);
    private static final Logger wire = LoggerFactory.getLogger(NvidiaAiService.class.getName() + ".wire");
    private static final String MDC_SCAN_ID = "scanId";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder;
    private final Environment env;

    // ── provider chain ────────────────────────────────────────────────
    @Value("${ai.providers:openrouter,groq,huggingface,nvidia,gemini,cerebras}")
    private String providersRaw;

    @Value("${ai.attempt-timeout-seconds:60}")
    private long attemptTimeoutSeconds;

    // ── image prep ────────────────────────────────────────────────────
    @Value("${ai.image.max-edge-px:1024}")
    private int maxEdgePx;

    @Value("${ai.image.max-base64-bytes:180000}")
    private int maxBase64Bytes;

    @Value("${ai.image.jpeg-quality:0.75}")
    private float jpegQuality;

    // ── logging ───────────────────────────────────────────────────────
    @Value("${ai.log.full-response:false}")
    private boolean logFullResponse;

    @Value("${ai.log.request-body:false}")
    private boolean logRequestBody;

    @Value("${ai.log.preview-chars:400}")
    private int previewChars;

    public NvidiaAiService(WebClient.Builder webClientBuilder, Environment env) {
        this.webClientBuilder = webClientBuilder;
        this.env = env;
    }

    // ──────────────────────────────────────────────────────────────────
    // Types
    // ──────────────────────────────────────────────────────────────────

    public record Provider(String id, String baseUrl, String apiKey, List<String> models) {}

    private record Attempt(Provider provider, String model) {
        String label() { return provider.id() + "/" + model; }
    }

    private static final class AttemptResult {
        String label;
        boolean success;
        long millis;
        String detail;
        Integer promptTokens;
        Integer completionTokens;
        String finishReason;
        int picks;
    }

    public static class ScanAnalysis {
        public int totalPicksDetected;
        public List<PickPrediction> predictions = new ArrayList<>();
        public String rawModelOutput;
        public String modelUsed;
        public String providerUsed;
        public String scanId;
    }

    // ──────────────────────────────────────────────────────────────────
    // Public entry point
    // ──────────────────────────────────────────────────────────────────

    public ScanAnalysis analyzeSlip(String imageBase64, String imageMediaType, ScanPlan plan) {
        String scanId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_SCAN_ID, scanId);
        long scanStarted = System.currentTimeMillis();

        try {
            log.info("=== SCAN START id={} maxPicks={} fullCoverage={} ===",
                    scanId,
                    plan.isFullCoverage() ? "unlimited" : plan.getMaxPicks(),
                    plan.isFullCoverage());

            List<Attempt> attempts = buildAttemptChain();

            if (attempts.isEmpty()) {
                log.error("No usable provider. Chain=[{}] but none had an API key.", providersRaw);
                throw new ApiException(
                        "AI scanning not configured: no provider in [" + providersRaw + "] has an API key. " +
                                "Set at least one of: AI_OPENROUTER_API_KEYS, GROQ_API_KEY, AI_HUGGINGFACE_API_KEY, " +
                                "AI_NVIDIA_API_KEY, AI_GEMINI_API_KEYS, AI_CEREBRAS_API_KEY.",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            log.info("Attempt chain ({} attempts, {}s timeout each):", attempts.size(), attemptTimeoutSeconds);
            for (int i = 0; i < attempts.size(); i++) {
                Attempt a = attempts.get(i);
                log.info("  [{}/{}] {} -> {} (key {})",
                        i + 1, attempts.size(), a.label(), a.provider().baseUrl(),
                        mask(a.provider().apiKey()));
            }

            String[] prepared = prepareImage(imageBase64, imageMediaType);
            String dataUri = "data:" + prepared[1] + ";base64," + prepared[0];

            Map<String, Object> baseBody = buildRequestBody(dataUri, plan);
            List<AttemptResult> results = new ArrayList<>();

            for (int i = 0; i < attempts.size(); i++) {
                Attempt attempt = attempts.get(i);
                AttemptResult ar = new AttemptResult();
                ar.label = attempt.label();
                long started = System.currentTimeMillis();

                try {
                    Map<String, Object> body = new LinkedHashMap<>(baseBody);
                    body.put("model", attempt.model());

                    log.info(">>> ATTEMPT {}/{} [{}] POST {}/chat/completions",
                            i + 1, attempts.size(), attempt.label(), attempt.provider().baseUrl());

                    if (logRequestBody) {
                        wire.info("[{}] request body: {}", attempt.label(), redactBody(body));
                    }

                    String content = callChatCompletions(body, attempt, ar);
                    ScanAnalysis analysis = parseModelResponse(content, plan, attempt.label());

                    if (analysis.predictions.isEmpty() && analysis.rawModelOutput != null) {
                        throw new ApiException("[" + attempt.label() + "] returned unparseable output: " +
                                truncate(analysis.rawModelOutput, 300), HttpStatus.BAD_GATEWAY);
                    }

                    ar.success = true;
                    ar.millis = System.currentTimeMillis() - started;
                    ar.picks = analysis.predictions.size();
                    ar.detail = "OK";
                    results.add(ar);

                    analysis.providerUsed = attempt.provider().id();
                    analysis.modelUsed = attempt.model();
                    analysis.scanId = scanId;

                    log.info("<<< SUCCESS [{}] {}ms, {} pick(s) of {} detected",
                            attempt.label(), ar.millis,
                            analysis.predictions.size(), analysis.totalPicksDetected);

                    logPickTable(analysis, attempt.label());
                    logSummary(results, attempts.size(), System.currentTimeMillis() - scanStarted, true);
                    return analysis;

                } catch (Exception ex) {
                    ar.success = false;
                    ar.millis = System.currentTimeMillis() - started;
                    ar.detail = ex.getClass().getSimpleName() + ": " + rootMessage(ex);
                    results.add(ar);
                    log.warn("<<< FAILED [{}] after {}ms: {}", attempt.label(), ar.millis, ar.detail);
                    log.debug("Full stack trace for [{}]", attempt.label(), ex);
                }
            }

            logSummary(results, attempts.size(), System.currentTimeMillis() - scanStarted, false);
            String failureList = results.stream()
                    .map(r -> r.label + " (" + r.millis + "ms) -> " + r.detail)
                    .collect(Collectors.joining("\n"));
            throw new ApiException(
                    "AI scanning failed. All " + attempts.size() + " attempt(s) failed:\n" + failureList,
                    HttpStatus.BAD_GATEWAY);

        } finally {
            MDC.remove(MDC_SCAN_ID);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Log helpers
    // ──────────────────────────────────────────────────────────────────

    private void logPickTable(ScanAnalysis analysis, String label) {
        if (analysis.predictions.isEmpty()) {
            log.info("[{}] No picks to display.", label);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== PICKS [").append(label).append("] — ")
                .append(analysis.predictions.size()).append(" of ")
                .append(analysis.totalPicksDetected).append(" detected ===\n");
        sb.append(String.format("%-4s %-34s %-5s %-9s %s%n",
                "#", "TEAMS", "PRED", "ACCURACY", "REASON"));
        sb.append("-".repeat(90)).append("\n");
        for (PickPrediction p : analysis.predictions) {
            sb.append(String.format("%-4d %-34s %-5s %-9s %s%n",
                    p.getSectionIndex(),
                    truncate(p.getTeamName(), 34),
                    p.getPrediction(),
                    p.getAccuracyPercent() + "%",
                    truncate(p.getReason(), 80)));
        }
        sb.append("=".repeat(90));
        log.info(sb.toString());
    }

    private void logSummary(List<AttemptResult> results, int totalAttempts,
                            long totalMs, boolean success) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== SCAN SUMMARY (").append(success ? "SUCCESS" : "ALL FAILED")
                .append(", ").append(totalMs).append("ms total, ")
                .append(results.size()).append('/').append(totalAttempts).append(" attempted) ===\n");
        sb.append(String.format("%-6s %-42s %-8s %-9s %-8s %s%n",
                "RESULT", "PROVIDER/MODEL", "TIME", "TOKENS", "FINISH", "DETAIL"));
        for (AttemptResult r : results) {
            String tokens = (r.promptTokens != null || r.completionTokens != null)
                    ? nz(r.promptTokens) + "/" + nz(r.completionTokens) : "-";
            sb.append(String.format("%-6s %-42s %-8s %-9s %-8s %s%n",
                    r.success ? "OK" : "FAIL",
                    truncate(r.label, 42),
                    r.millis + "ms",
                    tokens,
                    r.finishReason == null ? "-" : r.finishReason,
                    truncate(r.detail, 160)));
        }
        sb.append("=".repeat(60));
        if (success) log.info(sb.toString());
        else         log.error(sb.toString());
    }

    // ──────────────────────────────────────────────────────────────────
    // Provider / chain construction
    // ──────────────────────────────────────────────────────────────────

    /**
     * Builds the full ordered list of (provider, model) attempts.
     *
     * For each id in ai.providers:
     *   1. Resolve base URL and model list (env > built-in defaults).
     *   2. Resolve API key(s): ai.<id>.api-keys (preferred) or ai.<id>.api-key.
     *   3. Expand into one Provider per key; each carries the full model list.
     *   4. Flatten (key × model) → attempt chain in order.
     *
     * Providers with no key are silently skipped so deployments with a subset
     * of keys still work — just set the ones you have.
     */
    private List<Attempt> buildAttemptChain() {
        List<Attempt> chain = new ArrayList<>();

        for (String id : splitCsv(providersRaw)) {
            String baseUrl = env.getProperty("ai." + id + ".base-url", defaultBaseUrl(id));
            String models  = env.getProperty("ai." + id + ".models", "");

            if (isBlank(models)) models = defaultModels(id);

            if (isBlank(baseUrl)) {
                log.warn("Provider [{}] SKIPPED: no base-url and no built-in default", id);
                continue;
            }

            List<String> modelList = splitCsv(models);
            if (modelList.isEmpty()) {
                log.warn("Provider [{}] SKIPPED: no models configured", id);
                continue;
            }

            List<String> apiKeys = resolveApiKeys(id);
            if (apiKeys.isEmpty()) {
                log.info("Provider [{}] SKIPPED: no API key (ai.{}.api-key / ai.{}.api-keys)", id, id, id);
                continue;
            }

            log.debug("Provider [{}] ENABLED: {} key(s), {} model(s) {}", id, apiKeys.size(), modelList.size(), modelList);

            boolean multiKey = apiKeys.size() > 1;
            for (int k = 0; k < apiKeys.size(); k++) {
                String key        = apiKeys.get(k);
                String providerId = multiKey ? id + "-key" + (k + 1) : id;
                Provider provider = new Provider(providerId, stripTrailingSlash(baseUrl), key, modelList);
                for (String m : modelList) {
                    chain.add(new Attempt(provider, m));
                }
            }
        }

        return chain;
    }

    private List<String> resolveApiKeys(String id) {
        List<String> keys = splitCsv(env.getProperty("ai." + id + ".api-keys", ""));
        if (!keys.isEmpty()) return keys;
        String single = env.getProperty("ai." + id + ".api-key", "");
        return isBlank(single) ? List.of() : List.of(single);
    }

    /**
     * Built-in base URLs. Override any with ai.<id>.base-url=...
     */
    private String defaultBaseUrl(String id) {
        return switch (id) {
            case "openrouter"  -> "https://openrouter.ai/api/v1";
            case "groq"        -> "https://api.groq.com/openai/v1";
            case "huggingface" -> "https://router.huggingface.co/v1";
            case "nvidia"      -> "https://integrate.api.nvidia.com/v1";
            case "gemini"      -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "cerebras"    -> "https://api.cerebras.ai/v1";
            default -> null;
        };
    }

    /**
     * Built-in model lists — all confirmed to accept image input as of Aug 2026.
     * Override any with ai.<id>.models=model1,model2,...
     *
     * OpenRouter
     *   openrouter/free              — Smart free router, auto-selects any free VLM.
     *                                   Use as first attempt so you always get something.
     *   google/gemma-4-31b-it:free   — Best free general VLM; 262K ctx; very reliable.
     *   nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free — text+image+video+audio.
     *   google/gemma-4-26b-a4b-it:free — Efficient MoE variant; image + 60s video.
     *   qwen/qwen2.5-vl-72b-instruct:free — Strongest OCR/document-reading VLM free.
     *   moonshotai/kimi-vl-a3b-thinking:free — Thinking VLM; good at dense layouts.
     *   meta-llama/llama-3.2-11b-vision-instruct:free — Stable; runs since Sep 2024.
     *   minimax/minimax-m3:free      — 1M ctx; handles very long slips.
     *
     * Groq  (ultra-fast LPU inference; free developer tier)
     *   qwen/qwen3.6-27b             — 27B; thinking+non-thinking; best free OCR on Groq.
     *   qwen/qwen3.8-27b             — Successor to 3.6; same vision capabilities.
     *   meta-llama/llama-4-scout-17b-16e-instruct — 128K ctx; up to 5 images per request.
     *
     * HuggingFace  (free ~1K req/day; credits reset monthly)
     *   meta-llama/Llama-3.2-11B-Vision-Instruct — Best-supported HF vision model.
     *   Qwen/Qwen2.5-VL-72B-Instruct             — Highest accuracy; may be slow.
     *   NOTE: Qwen2.5-VL-7B-Instruct returns 400 on HF router — do NOT use it.
     *
     * NVIDIA NIM  (free tier via NGC)
     *   meta/llama-3.2-90b-vision-instruct  — Highest quality but slow (cold start).
     *   meta/llama-3.2-11b-vision-instruct  — Faster, good quality.
     *   nvidia/nemotron-nano-12b-v2-vl       — NVIDIA's own compact vision model.
     *   minimaxai/minimax-m3                 — Multimodal, 1M ctx.
     *
     * Gemini  (free via AI Studio; Flash only on free tier)
     *   gemini-2.5-flash             — SHUTS DOWN 2026-10-16. Remove after that date.
     *   gemini-3-flash               — Successor; verify available in your region.
     *   gemini-3.1-flash-lite        — Lightest free Gemini option.
     *
     * Cerebras  (free tier; only gemma-4-31b accepts images)
     *   gemma-4-31b                  — 2 images per request max on free tier.
     */
    private String defaultModels(String id) {
        return switch (id) {
            case "openrouter" -> String.join(",",
                    "openrouter/free",
                    "google/gemma-4-31b-it:free",
                    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
                    "google/gemma-4-26b-a4b-it:free",
                    "qwen/qwen2.5-vl-72b-instruct:free",
                    "moonshotai/kimi-vl-a3b-thinking:free",
                    "meta-llama/llama-3.2-11b-vision-instruct:free",
                    "minimax/minimax-m3:free");
            case "groq" -> String.join(",",
                    "qwen/qwen3.6-27b",
                    "qwen/qwen3.8-27b",
                    "meta-llama/llama-4-scout-17b-16e-instruct");
            case "huggingface" -> String.join(",",
                    "meta-llama/Llama-3.2-11B-Vision-Instruct",
                    "Qwen/Qwen2.5-VL-72B-Instruct");
            case "nvidia" -> String.join(",",
                    "meta/llama-3.2-90b-vision-instruct",
                    "meta/llama-3.2-11b-vision-instruct",
                    "nvidia/nemotron-nano-12b-v2-vl",
                    "minimaxai/minimax-m3",
                    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning");
            case "gemini" -> String.join(",",
                    "gemini-2.5-flash",
                    "gemini-3-flash",
                    "gemini-3.1-flash-lite");
            case "cerebras" -> "gemma-4-31b";
            default -> "";
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // Request body
    // ──────────────────────────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String dataUri, ScanPlan plan) {
        Map<String, Object> imageContent = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUri));

        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", buildPrompt(plan));

        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(textContent, imageContent));

        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content", """
                You are Predator AI, an elite football betting analyst who reads virtual betting slips from images.

                Rules:
                - The image is a virtual football betting slip containing multiple fixtures.
                - Inspect the image and identify every visible match, in printed order.
                - For each fixture predict exactly ONE outcome: Home Win (1), Draw (X), or Away Win (2).
                - Base predictions on the odds shown, implied probabilities, recognizable team strength,
                  and football reasoning. Never just pick the lowest odds automatically. Consider upsets and draws.
                - Provide an accuracyPercent (0-100) reflecting your confidence in the prediction.
                  Use 80-95 for strong signals, 60-79 for moderate, below 60 for uncertain.
                - If image quality prevents reading a fixture, set prediction to "unreadable" and accuracyPercent to 0.
                - Never fabricate fixtures or odds that are not visible in the image.

                Return ONLY a single valid JSON object. No markdown, no code fences, no text outside the JSON.
                """);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("temperature", 0.4);
        body.put("top_p", 0.9);
        body.put("max_tokens", 4096);
        body.put("stream", false);

        log.debug("Request built: prompt {} chars, image data-uri {} chars",
                buildPrompt(plan).length(), dataUri.length());

        return body;
    }

    private String buildPrompt(ScanPlan plan) {
        String coverageInstruction = plan.isFullCoverage()
                ? "Analyze EVERY pick/game/section on the slip (full coverage)."
                : "The user's plan only covers up to " + plan.getMaxPicks() + " picks. " +
                  "Analyze at most the first " + plan.getMaxPicks() + " picks/sections on the slip, " +
                  "in the order they appear, and leave the rest out entirely.";

        return "You are looking at an image of a sports betting slip containing one or more picks.\n\n" +
                "1. Count and identify every distinct pick/section on the slip, in printed order.\n" +
                "2. " + coverageInstruction + "\n" +
                "3. For each analyzed pick provide:\n" +
                "   - teamName        : \"Home Team vs Away Team\" as printed on the slip\n" +
                "   - prediction      : 1 (home win), X (draw), or 2 (away win)\n" +
                "   - accuracyPercent : integer 0-100 reflecting your confidence (not the slip odds)\n" +
                "   - reason          : exactly ONE line of ~10 words explaining the prediction\n\n" +
                "Respond with ONLY a single JSON object matching exactly this shape:\n" +
                "{\n" +
                "  \"totalPicksDetected\": <integer>,\n" +
                "  \"picks\": [\n" +
                "    {\n" +
                "      \"sectionIndex\":    <integer, 1-based>,\n" +
                "      \"teamName\":        \"<Home Team vs Away Team>\",\n" +
                "      \"originalPick\":    \"<market/selection printed on slip, if legible>\",\n" +
                "      \"prediction\":      \"<1 | X | 2 | unreadable>\",\n" +
                "      \"accuracyPercent\": <integer 0-100>,\n" +
                "      \"reason\":          \"<one line, ~10 words>\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    // ──────────────────────────────────────────────────────────────────
    // HTTP
    // ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callChatCompletions(Map<String, Object> body, Attempt attempt, AttemptResult ar) {

        WebClient.RequestBodySpec spec = webClientBuilder.build()
                .post()
                .uri(attempt.provider().baseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + attempt.provider().apiKey())
                .header("Content-Type", "application/json");

        // Groq requires HTTP-Referer for some models
        if (attempt.provider().id().startsWith("groq")) {
            spec = (WebClient.RequestBodySpec) spec.header("HTTP-Referer", "https://virtuwinbackend.onrender.com");
        }
        // OpenRouter recommends these headers for routing/tracking
        if (attempt.provider().id().startsWith("openrouter")) {
            spec = (WebClient.RequestBodySpec) spec
                    .header("HTTP-Referer", "https://virtuwinbackend.onrender.com")
                    .header("X-Title", "Predator AI Slip Scanner");
        }

        final long httpStart = System.currentTimeMillis();

        Map<String, Object> result = (Map<String, Object>) spec
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r ->
                        r.bodyToMono(String.class).defaultIfEmpty("<empty body>").map(respBody -> {
                            log.warn("[{}] HTTP {} after {}ms. Body: {}",
                                    attempt.label(), r.statusCode(),
                                    System.currentTimeMillis() - httpStart, truncate(respBody, 800));
                            return new ApiException(
                                    "[" + attempt.label() + "] HTTP " + r.statusCode() + ": " +
                                            truncate(respBody, 500) + explainStatus(r.statusCode().value()),
                                    HttpStatus.BAD_GATEWAY);
                        }))
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(attemptTimeoutSeconds))
                .onErrorMap(ex -> !(ex instanceof ApiException),
                        ex -> new ApiException("[" + attempt.label() + "] " +
                                ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                                HttpStatus.BAD_GATEWAY))
                .block();

        long httpMs = System.currentTimeMillis() - httpStart;
        log.info("[{}] HTTP 200 in {}ms", attempt.label(), httpMs);

        if (result == null) {
            throw new ApiException("[" + attempt.label() + "] returned an empty response.", HttpStatus.BAD_GATEWAY);
        }

        Object errorNode = result.get("error");
        if (errorNode != null) {
            log.warn("[{}] HTTP 200 but body contains error: {}", attempt.label(),
                    truncate(String.valueOf(errorNode), 600));
            throw new ApiException("[" + attempt.label() + "] provider error: " +
                    truncate(String.valueOf(errorNode), 400), HttpStatus.BAD_GATEWAY);
        }

        Object usageObj = result.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            ar.promptTokens     = asInt(usage.get("prompt_tokens"));
            ar.completionTokens = asInt(usage.get("completion_tokens"));
            log.info("[{}] usage: prompt={} completion={} total={}",
                    attempt.label(), nz(ar.promptTokens), nz(ar.completionTokens),
                    nz(asInt(usage.get("total_tokens"))));
            Object img = usage.get("image_tokens");
            if (img != null) log.info("[{}] image_tokens={}", attempt.label(), img);
        }
        if (result.get("provider") != null) {
            log.info("[{}] routed to upstream: {}", attempt.label(), result.get("provider"));
        }

        List<Object> choices = (List<Object>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[{}] no choices. Raw: {}", attempt.label(), truncate(String.valueOf(result), 800));
            throw new ApiException("[" + attempt.label() + "] returned no choices.", HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);

        Object finish = firstChoice.get("finish_reason");
        if (finish != null) {
            ar.finishReason = String.valueOf(finish);
            if ("length".equals(ar.finishReason)) {
                log.warn("[{}] finish_reason=length — output TRUNCATED; JSON may be incomplete. " +
                        "Raise max_tokens or lower the pick cap.", attempt.label());
            } else {
                log.debug("[{}] finish_reason={}", attempt.label(), ar.finishReason);
            }
        }

        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        Object content = message != null ? message.get("content") : null;

        if (content == null || content.toString().isBlank()) {
            log.warn("[{}] empty message content. Choice: {}", attempt.label(),
                    truncate(String.valueOf(firstChoice), 600));
            throw new ApiException("[" + attempt.label() + "] returned an empty message.", HttpStatus.BAD_GATEWAY);
        }

        String text = content.toString();
        log.info("[{}] content: {} chars", attempt.label(), text.length());
        if (logFullResponse) {
            wire.info("[{}] full content:\n{}", attempt.label(), text);
        } else {
            log.debug("[{}] preview: {}", attempt.label(), truncate(text.replace('\n', ' '), previewChars));
        }
        return text;
    }

    private String explainStatus(int status) {
        return switch (status) {
            case 400 -> " | Hint: malformed request or unsupported/retired model id. " +
                    "On HuggingFace this often means the model is gated or unavailable via router.";
            case 401 -> " | Hint: API key invalid or missing. Check the key for this provider.";
            case 402 -> " | Hint: free-tier credits exhausted. " +
                    "On HuggingFace credits reset monthly; on OpenRouter check your balance. " +
                    "If you have multiple keys configured, the next key will be tried automatically.";
            case 403 -> " | Hint: key not enabled for this API, or model is gated/enterprise-only.";
            case 404 -> " | Hint: model id not found or retired. Verify in the provider's live catalog.";
            case 413 -> " | Hint: payload too large — lower ai.image.max-edge-px.";
            case 422 -> " | Hint: model does not accept image input (not a VLM). " +
                    "On Groq only qwen/qwen3.6-27b and llama-4-scout currently support images.";
            case 429 -> " | Hint: rate limited. Next provider/key in chain will be tried. " +
                    "Consider adding more API keys via ai.<provider>.api-keys=key1,key2,...";
            case 503 -> " | Hint: model cold-starting or provider unavailable; retry shortly.";
            default -> "";
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // Image preparation
    // ──────────────────────────────────────────────────────────────────

    private String[] prepareImage(String imageBase64, String imageMediaType) {
        long started = System.currentTimeMillis();
        try {
            byte[] raw = Base64.getDecoder().decode(imageBase64);
            log.info("Image in: {} KB raw, {} KB base64, type {}",
                    raw.length / 1024, imageBase64.length() / 1024, imageMediaType);

            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) {
                log.warn("Could not decode image (unsupported format?), sending original bytes unchanged");
                return new String[]{imageBase64, imageMediaType};
            }
            log.debug("Decoded image: {}x{} px", src.getWidth(), src.getHeight());

            int edge = maxEdgePx;
            float quality = jpegQuality;

            for (int i = 0; i < 4; i++) {
                byte[] encoded = encodeJpeg(scale(src, edge), quality);
                String b64 = Base64.getEncoder().encodeToString(encoded);
                log.debug("Encode pass {}: edge={}px quality={} -> {} KB base64",
                        i + 1, edge, quality, b64.length() / 1024);
                if (b64.length() <= maxBase64Bytes) {
                    log.info("Image out: edge {}px quality {} {} KB ({}ms)",
                            edge, quality, b64.length() / 1024, System.currentTimeMillis() - started);
                    return new String[]{b64, "image/jpeg"};
                }
                edge    = (int) (edge * 0.75);
                quality = Math.max(0.4f, quality - 0.1f);
            }

            byte[] encoded = encodeJpeg(scale(src, edge), 0.4f);
            String b64 = Base64.getEncoder().encodeToString(encoded);
            log.warn("Image still {} KB after max compression (limit {} KB); sending anyway",
                    b64.length() / 1024, maxBase64Bytes / 1024);
            return new String[]{b64, "image/jpeg"};

        } catch (Exception ex) {
            log.warn("Image preparation failed, falling back to original bytes", ex);
            return new String[]{imageBase64, imageMediaType};
        }
    }

    private BufferedImage scale(BufferedImage src, int maxEdge) {
        int w = src.getWidth(), h = src.getHeight();
        if (Math.max(w, h) <= maxEdge) return toRgb(src);
        double factor = (double) maxEdge / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * factor));
        int nh = Math.max(1, (int) Math.round(h * factor));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPEG writer in this JRE");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ──────────────────────────────────────────────────────────────────
    // Parsing
    // ──────────────────────────────────────────────────────────────────

    private ScanAnalysis parseModelResponse(String content, ScanPlan plan, String label) {
        ScanAnalysis analysis = new ScanAnalysis();
        String jsonText = extractJson(content);

        if (jsonText.length() != content.trim().length()) {
            log.debug("[{}] stripped {} chars of non-JSON wrapper",
                    label, content.trim().length() - jsonText.length());
        }

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            analysis.totalPicksDetected = root.path("totalPicksDetected").asInt(
                    root.path("totalMatches").asInt(0));

            JsonNode picksNode = root.path("picks");
            if (!picksNode.isArray()) {
                picksNode = root.path("predictions");
                if (picksNode.isArray()) {
                    log.debug("[{}] model used 'predictions' key; handled", label);
                }
            }

            int cap = plan.isFullCoverage() ? Integer.MAX_VALUE : plan.getMaxPicks();

            if (!picksNode.isArray()) {
                log.warn("[{}] no picks/predictions array. Keys: {}", label, fieldNames(root));
            } else {
                int available = picksNode.size();
                int count = 0;
                for (JsonNode n : picksNode) {
                    if (count >= cap) {
                        log.info("[{}] plan cap: kept {} of {} picks", label, cap, available);
                        break;
                    }
                    PickPrediction pick = new PickPrediction();

                    pick.setSectionIndex(n.path("sectionIndex").asInt(
                            n.path("matchNumber").asInt(count + 1)));

                    // teamName — primary; fall back to matchLabel
                    String teamName = n.path("teamName").asText("");
                    if (isBlank(teamName)) teamName = n.path("matchLabel").asText("");
                    pick.setTeamName(teamName);
                    pick.setMatchLabel(teamName);

                    pick.setOriginalPick(n.path("originalPick").asText(""));
                    pick.setPrediction(n.path("prediction").asText(""));

                    // accuracyPercent — primary; fall back to confidence string
                    int pct = n.path("accuracyPercent").asInt(-1);
                    if (pct < 0) {
                        pct = switch (n.path("confidence").asText("").toLowerCase()) {
                            case "high"   -> 85;
                            case "medium" -> 65;
                            case "low"    -> 45;
                            default       -> 0;
                        };
                    }
                    pick.setAccuracyPercent(pct);

                    String legacyConf = n.path("confidence").asText("");
                    if (isBlank(legacyConf)) {
                        legacyConf = pct >= 80 ? "High" : pct >= 60 ? "Medium" : "Low";
                    }
                    pick.setConfidence(legacyConf);

                    // reason — primary; fall back to analysis
                    String reason = n.path("reason").asText("");
                    if (isBlank(reason)) reason = n.path("analysis").asText("");
                    pick.setReason(reason);
                    pick.setAnalysis(reason);

                    analysis.predictions.add(pick);
                    count++;
                }
                log.info("[{}] parsed {} pick(s) from {} returned, {} detected on slip",
                        label, count, available, analysis.totalPicksDetected);
            }

            if (analysis.totalPicksDetected == 0) {
                analysis.totalPicksDetected = analysis.predictions.size();
            }

        } catch (Exception ex) {
            log.warn("[{}] JSON parse FAILED: {}. Payload head: {}",
                    label, ex.getMessage(), truncate(jsonText, 500));
            analysis.rawModelOutput = content;
        }

        return analysis;
    }

    private String extractJson(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl != -1) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end != -1) s = s.substring(0, end);
        }
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        return (start != -1 && end != -1 && end > start) ? s.substring(start, end + 1) : s;
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static boolean isBlank(String s)  { return s == null || s.isBlank(); }

    private static List<String> splitCsv(String raw) {
        if (isBlank(raw)) return List.of();
        return Stream.of(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String mask(String key) {
        if (isBlank(key)) return "<none>";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 3) +
                " (len " + key.length() + ")";
    }

    private String redactBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body)
                    .replaceAll("data:image/[a-zA-Z]+;base64,[A-Za-z0-9+/=]+",
                            "data:image/...;base64,<REDACTED>");
        } catch (Exception ex) {
            return "<body could not be serialized: " + ex.getMessage() + ">";
        }
    }

    private static String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private static Integer asInt(Object o) { return (o instanceof Number n) ? n.intValue() : null; }
    private static String  nz(Integer i)   { return i == null ? "-" : String.valueOf(i); }

    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        String msg = ex.getMessage();
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) msg = cur.getMessage();
        }
        return msg == null ? ex.toString() : msg;
    }
}