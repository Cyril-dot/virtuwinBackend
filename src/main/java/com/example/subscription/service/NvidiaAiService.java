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
 * Calls OpenAI-compatible chat completions endpoints to analyze a betting-slip
 * image and produce per-pick predictions.
 *
 * Despite the class name (kept so existing injection points don't change), this
 * is no longer NVIDIA-only. It walks a chain of PROVIDERS, each with its own
 * model list:
 *
 *   gemini     https://generativelanguage.googleapis.com/v1beta/openai   <- primary, free-tier models
 *   cerebras   https://api.cerebras.ai/v1                                <- fallback
 *
 * GEMINI NOTE: Google's Gemini API exposes an OpenAI-compatible endpoint at
 * /v1beta/openai/chat/completions. As of the free tier rules in effect since
 * April 1 2026, only Flash-class models are free (Pro models are paid-only),
 * so the default model list below sticks to Flash / Flash-Lite. Get a key at
 * https://aistudio.google.com/apikey and set ai.gemini.api-key (or GEMINI_API_KEY
 * if you wire that into the property). NOTE: gemini-2.5-flash and
 * gemini-2.5-flash-lite are scheduled to shut down on 2026-10-16 - when that
 * date passes, drop them from ai.gemini.models (or this will start failing with
 * HTTP 404) and lean on gemini-3-flash / gemini-3.1-flash-lite instead.
 *
 * CEREBRAS NOTE: Cerebras Inference is an OpenAI-compatible endpoint too, but
 * vision (image input) is currently only supported by gemma-4-31b - it is the
 * only model in the default list. Free-tier accounts are capped at 2 images per
 * request, which is fine here since we only ever send one. Get a key at
 * https://cloud.cerebras.ai and set ai.cerebras.api-key.
 *
 * Providers whose API key is blank are SKIPPED, so you can deploy with only one
 * of the two keys set (though both is recommended so Cerebras can cover Gemini
 * outages/rate limits and vice versa).
 *
 * LOGGING: every scan gets a short trace id (MDC key "scanId") that prefixes all
 * log lines for that request, so concurrent scans stay untangled in the log file.
 * Each attempt logs the outbound request summary, HTTP status, latency, token
 * usage, finish reason, and a preview of the returned content. A summary table
 * of all attempts is printed at the end whether the scan succeeded or failed.
 * API keys are always masked; the base64 image is never logged.
 */
@Service
public class NvidiaAiService {

    private static final Logger log = LoggerFactory.getLogger(NvidiaAiService.class);
    /** Separate logger so raw request/response payloads can be toggled independently. */
    private static final Logger wire = LoggerFactory.getLogger(NvidiaAiService.class.getName() + ".wire");

    private static final String MDC_SCAN_ID = "scanId";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder;
    private final Environment env;

    // ---- provider chain -------------------------------------------------

    /** Ordered, comma-separated provider ids to try. Gemini first, Cerebras as fallback. */
    @Value("${ai.providers:gemini,cerebras}")
    private String providersRaw;

    /**
     * Per-attempt timeout. A vision model producing 2-4k tokens of JSON with
     * stream=false routinely needs 20-60s, so a short default guarantees that
     * every model in the chain "times out".
     */
    @Value("${ai.attempt-timeout-seconds:60}")
    private long attemptTimeoutSeconds;

    // ---- image prep -----------------------------------------------------

    @Value("${ai.image.max-edge-px:1024}")
    private int maxEdgePx;

    @Value("${ai.image.max-base64-bytes:180000}")
    private int maxBase64Bytes;

    @Value("${ai.image.jpeg-quality:0.75}")
    private float jpegQuality;

    // ---- logging switches ----------------------------------------------

    /** Log the full model output text on every attempt (not just a preview). */
    @Value("${ai.log.full-response:false}")
    private boolean logFullResponse;

    /** Log the outbound JSON body (image data URI is always redacted). */
    @Value("${ai.log.request-body:false}")
    private boolean logRequestBody;

    /** Characters of model output shown in the preview line. */
    @Value("${ai.log.preview-chars:400}")
    private int previewChars;

    public NvidiaAiService(WebClient.Builder webClientBuilder, Environment env) {
        this.webClientBuilder = webClientBuilder;
        this.env = env;
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public record Provider(String id, String baseUrl, String apiKey, List<String> models) {
    }

    private record Attempt(Provider provider, String model) {
        String label() {
            return provider.id() + "/" + model;
        }
    }

    /** Per-attempt outcome, collected for the end-of-scan summary table. */
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
        public String rawModelOutput;   // populated only if JSON parsing failed
        public String modelUsed;        // which model actually answered
        public String providerUsed;     // which provider it came from
        public String scanId;           // trace id, matches the log lines
    }

    // ------------------------------------------------------------------
    // Public entry point (signature unchanged)
    // ------------------------------------------------------------------

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
                log.error("No usable provider. Configured chain=[{}] but none had an API key.", providersRaw);
                throw new ApiException(
                        "AI scanning is not configured: no provider in [" + providersRaw +
                                "] has an API key set. Set ai.gemini.api-key (Gemini, primary) or " +
                                "ai.cerebras.api-key (Cerebras, fallback).",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            log.info("Attempt chain ({} attempts, {}s timeout each):", attempts.size(), attemptTimeoutSeconds);
            for (int i = 0; i < attempts.size(); i++) {
                Attempt a = attempts.get(i);
                log.info("  [{}/{}] {} -> {} (key {})",
                        i + 1, attempts.size(), a.label(), a.provider().baseUrl(), mask(a.provider().apiKey()));
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
                            attempt.label(), ar.millis, analysis.predictions.size(),
                            analysis.totalPicksDetected);
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

    /** Prints an aligned table of every attempt so one glance explains the outcome. */
    private void logSummary(List<AttemptResult> results, int totalAttempts, long totalMs, boolean success) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== SCAN SUMMARY (").append(success ? "SUCCESS" : "ALL FAILED")
                .append(", ").append(totalMs).append("ms total, ")
                .append(results.size()).append('/').append(totalAttempts).append(" attempted) ===\n");
        sb.append(String.format("%-6s %-38s %-8s %-9s %-8s %s%n",
                "RESULT", "PROVIDER/MODEL", "TIME", "TOKENS", "FINISH", "DETAIL"));

        for (AttemptResult r : results) {
            String tokens = (r.promptTokens != null || r.completionTokens != null)
                    ? nz(r.promptTokens) + "/" + nz(r.completionTokens)
                    : "-";
            sb.append(String.format("%-6s %-38s %-8s %-9s %-8s %s%n",
                    r.success ? "OK" : "FAIL",
                    truncate(r.label, 38),
                    r.millis + "ms",
                    tokens,
                    r.finishReason == null ? "-" : r.finishReason,
                    truncate(r.detail, 160)));
        }
        sb.append("=".repeat(60));

        if (success) {
            log.info(sb.toString());
        } else {
            log.error(sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // Provider / chain construction
    // ------------------------------------------------------------------

    private List<Attempt> buildAttemptChain() {
        List<Attempt> chain = new ArrayList<>();

        for (String id : splitCsv(providersRaw)) {
            String baseUrl = env.getProperty("ai." + id + ".base-url", defaultBaseUrl(id));
            String apiKey = env.getProperty("ai." + id + ".api-key", "");
            String models = env.getProperty("ai." + id + ".models", "");

            if (isBlank(models)) {
                models = defaultModels(id);
            }

            if (isBlank(baseUrl)) {
                log.warn("Provider [{}] SKIPPED: no base-url configured and no built-in default", id);
                continue;
            }
            if (isBlank(apiKey)) {
                log.info("Provider [{}] SKIPPED: no API key set (ai.{}.api-key)", id, id);
                continue;
            }

            List<String> modelList = splitCsv(models);
            if (modelList.isEmpty()) {
                log.warn("Provider [{}] SKIPPED: no models configured", id);
                continue;
            }

            log.debug("Provider [{}] ENABLED: {} with {} model(s) {}",
                    id, baseUrl, modelList.size(), modelList);

            Provider provider = new Provider(id, stripTrailingSlash(baseUrl), apiKey, modelList);
            for (String m : modelList) {
                chain.add(new Attempt(provider, m));
            }
        }

        return chain;
    }

    private String defaultBaseUrl(String id) {
        return switch (id) {
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "cerebras" -> "https://api.cerebras.ai/v1";
            default -> null;
        };
    }

    /**
     * Vision-capable defaults.
     *
     * VERIFY these against the provider's live catalog before deploying - model
     * ids and free-tier eligibility change, and a retired id returns a 404 that
     * looks like an outage. In particular: gemini-2.5-flash and
     * gemini-2.5-flash-lite are slated to shut down 2026-10-16 - after that,
     * ai.gemini.models should drop to just gemini-3-flash,gemini-3.1-flash-lite.
     */
    private String defaultModels(String id) {
        return switch (id) {
            // Free-tier (Flash-class) Gemini models only - Pro models have been
            // paid-only since 2026-04-01 and will 402/permission-error here.
            case "gemini" -> String.join(",",
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-3-flash",
                    "gemini-3.1-flash-lite");
            // gemma-4-31b is currently the only vision-capable model Cerebras
            // serves on the shared/free tier.
            case "cerebras" -> "gemma-4-31b";
            default -> "";
        };
    }

    // ------------------------------------------------------------------
    // Request body
    // ------------------------------------------------------------------

    private Map<String, Object> buildRequestBody(String dataUri, ScanPlan plan) {

        Map<String, Object> imageContent = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUri));

        String prompt = buildPrompt(plan);

        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", prompt);

        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(textContent, imageContent));

        // ONE schema only. The previous version had a system prompt demanding
        // {"predictions":[{"matchNumber",...}]} and a user prompt demanding
        // {"picks":[{"sectionIndex",...}]}, while the parser only read the second -
        // so a model that obeyed the system prompt produced zero picks.
        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content",
                """
                You are Predator AI, an elite football betting analyst who reads virtual betting slips from images.

                Rules:
                - The image is a virtual football betting slip containing multiple fixtures.
                - Inspect the image and identify every visible match, in printed order.
                - For each fixture predict exactly ONE outcome: Home Win (1), Draw (X), or Away Win (2).
                - Base predictions on the odds shown, implied probabilities, recognizable team strength,
                  and football reasoning. Never just pick the lowest odds automatically. Consider upsets and draws.
                - If image quality prevents reading a fixture, set its prediction to "unreadable" rather than guessing.
                - Never fabricate fixtures or odds that are not visible in the image.

                Return ONLY a single valid JSON object. No markdown, no code fences, no text outside the JSON.
                """);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("temperature", 0.4);
        body.put("top_p", 0.9);
        body.put("max_tokens", 4096);
        body.put("stream", false);

        log.debug("Request built: prompt {} chars, image data-uri {} chars, max_tokens 4096, temp 0.4",
                prompt.length(), dataUri.length());

        return body;
    }

    private String buildPrompt(ScanPlan plan) {
        String coverageInstruction = plan.isFullCoverage()
                ? "Analyze EVERY pick/game/section on the slip (full coverage)."
                : "The user's plan only covers up to " + plan.getMaxPicks() + " picks. " +
                  "Analyze at most the first " + plan.getMaxPicks() + " picks/sections on the slip, " +
                  "in the order they appear, and leave the rest out entirely.";

        return "You are looking at an image of a sports betting slip/coupon containing one or more " +
                "individual picks (each pick is one section of the slip: teams, market, odds).\n\n" +
                "1. Count and identify every distinct pick/section on the slip, in printed order.\n" +
                "2. " + coverageInstruction + "\n" +
                "3. For each analyzed pick give your own independent prediction (not just a restatement " +
                "of the slip), a confidence level, and a 1-3 sentence analysis.\n\n" +
                "Respond with ONLY a single JSON object matching exactly this shape:\n" +
                "{\n" +
                "  \"totalPicksDetected\": <integer, total picks found on the whole slip>,\n" +
                "  \"picks\": [\n" +
                "    {\n" +
                "      \"sectionIndex\": <integer, 1-based order on the slip>,\n" +
                "      \"matchLabel\": \"<teams/event as read off the slip>\",\n" +
                "      \"originalPick\": \"<the selection/market printed on the slip, if legible>\",\n" +
                "      \"prediction\": \"<1, X, 2, or unreadable>\",\n" +
                "      \"confidence\": \"High\" | \"Medium\" | \"Low\",\n" +
                "      \"analysis\": \"<brief reasoning>\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /**
     * Single attempt against one provider+model. Short per-attempt timeout, and
     * crucially NO retryWhen(Retry.max(0)) - that operator wraps the real error in
     * RetryExhaustedException, which then fails the "instanceof ApiException" check
     * in onErrorMap and produces the useless "Retries exhausted: 0/0" message that
     * hid the actual cause. Omitting the operator entirely is how you say
     * "do not retry"; the caller handles failover.
     */
    @SuppressWarnings("unchecked")
    private String callChatCompletions(Map<String, Object> body, Attempt attempt, AttemptResult ar) {

        WebClient.RequestBodySpec spec = webClientBuilder.build()
                .post()
                .uri(attempt.provider().baseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + attempt.provider().apiKey())
                .header("Content-Type", "application/json");

        final long httpStart = System.currentTimeMillis();

        Map<String, Object> result = (Map<String, Object>) spec
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r ->
                        r.bodyToMono(String.class).defaultIfEmpty("<empty body>").map(respBody -> {
                            log.warn("[{}] HTTP {} after {}ms. Body: {}",
                                    attempt.label(), r.statusCode(),
                                    System.currentTimeMillis() - httpStart, truncate(respBody, 800));
                            return new ApiException("[" + attempt.label() + "] HTTP " + r.statusCode() +
                                    ": " + truncate(respBody, 500) + explainStatus(r.statusCode().value()),
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
            throw new ApiException("[" + attempt.label() + "] returned an empty response.",
                    HttpStatus.BAD_GATEWAY);
        }

        // Some providers return {"error": {...}} with HTTP 200.
        Object errorNode = result.get("error");
        if (errorNode != null) {
            log.warn("[{}] HTTP 200 but body contains an error object: {}",
                    attempt.label(), truncate(String.valueOf(errorNode), 600));
            throw new ApiException("[" + attempt.label() + "] provider error: " +
                    truncate(String.valueOf(errorNode), 400), HttpStatus.BAD_GATEWAY);
        }

        // Token usage, useful for cost tracking on paid endpoints.
        Object usageObj = result.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            ar.promptTokens = asInt(usage.get("prompt_tokens"));
            ar.completionTokens = asInt(usage.get("completion_tokens"));
            log.info("[{}] usage: prompt={} completion={} total={}",
                    attempt.label(), nz(ar.promptTokens), nz(ar.completionTokens),
                    nz(asInt(usage.get("total_tokens"))));
            // Cerebras (gemma-4-31b) reports image tokens separately - useful to
            // see how much of the prompt budget the image itself consumed.
            Object imageTokens = usage.get("image_tokens");
            if (imageTokens != null) {
                log.info("[{}] image_tokens={}", attempt.label(), imageTokens);
            }
        }
        if (result.get("provider") != null) {
            log.info("[{}] routed to upstream provider: {}", attempt.label(), result.get("provider"));
        }

        List<Object> choices = (List<Object>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[{}] no choices in response. Raw: {}", attempt.label(),
                    truncate(String.valueOf(result), 800));
            throw new ApiException("[" + attempt.label() + "] returned no choices.",
                    HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);

        Object finish = firstChoice.get("finish_reason");
        if (finish != null) {
            ar.finishReason = String.valueOf(finish);
            if ("length".equals(ar.finishReason)) {
                log.warn("[{}] finish_reason=length - output was TRUNCATED by max_tokens, so the JSON " +
                        "is likely incomplete. Raise max_tokens or lower the pick cap.", attempt.label());
            } else {
                log.debug("[{}] finish_reason={}", attempt.label(), ar.finishReason);
            }
        }

        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        Object content = message != null ? message.get("content") : null;

        if (content == null || content.toString().isBlank()) {
            log.warn("[{}] empty message content. Choice: {}", attempt.label(),
                    truncate(String.valueOf(firstChoice), 600));
            throw new ApiException("[" + attempt.label() + "] returned an empty message.",
                    HttpStatus.BAD_GATEWAY);
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

    /** Turns common HTTP codes into an actionable hint appended to the error. */
    private String explainStatus(int status) {
        return switch (status) {
            case 400 -> " | Hint: Gemini returns 400 for a malformed request or an unsupported/retired " +
                    "model id - double check ai.gemini.models against the live catalog.";
            case 401 -> " | Hint: API key invalid, wrong header, or missing the right scope.";
            case 402 -> " | Hint: billing/credits required - this model id is no longer on the free tier.";
            case 403 -> " | Hint: Gemini - key not enabled for the Generative Language API, or a Pro " +
                    "model was requested on a free-tier key. Cerebras - gated/dedicated-endpoint model.";
            case 404 -> " | Hint: model id not found or retired. Verify it in the provider's catalog.";
            case 413 -> " | Hint: payload too large - lower ai.image.max-edge-px.";
            case 422 -> " | Hint: model likely does not accept image input (not a VLM). On Cerebras, " +
                    "only gemma-4-31b currently supports images.";
            case 429 -> " | Hint: rate limited. Gemini free tier is capped at a handful of requests/min; " +
                    "Cerebras free tier is similarly limited. Back off or let the other provider take over.";
            case 503 -> " | Hint: model cold-starting or provider unavailable; retry shortly.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------
    // Image preparation
    // ------------------------------------------------------------------

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
            log.debug("Decoded image: {}x{} px, type {}", src.getWidth(), src.getHeight(), src.getType());

            int edge = maxEdgePx;
            float quality = jpegQuality;

            for (int i = 0; i < 4; i++) {
                byte[] encoded = encodeJpeg(scale(src, edge), quality);
                String b64 = Base64.getEncoder().encodeToString(encoded);
                log.debug("Encode pass {}: edge={}px quality={} -> {} KB base64",
                        i + 1, edge, quality, b64.length() / 1024);

                if (b64.length() <= maxBase64Bytes) {
                    log.info("Image out: edge {}px, quality {}, {} KB base64 ({}ms)",
                            edge, quality, b64.length() / 1024, System.currentTimeMillis() - started);
                    return new String[]{b64, "image/jpeg"};
                }
                edge = (int) (edge * 0.75);
                quality = Math.max(0.4f, quality - 0.1f);
            }

            byte[] encoded = encodeJpeg(scale(src, edge), 0.4f);
            String b64 = Base64.getEncoder().encodeToString(encoded);
            log.warn("Image still {} KB base64 after max compression (limit {} KB); sending anyway",
                    b64.length() / 1024, maxBase64Bytes / 1024);
            return new String[]{b64, "image/jpeg"};

        } catch (Exception ex) {
            log.warn("Image preparation failed, falling back to original bytes", ex);
            return new String[]{imageBase64, imageMediaType};
        }
    }

    private BufferedImage scale(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (Math.max(w, h) <= maxEdge) {
            return toRgb(src);
        }
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

    /** JPEG cannot carry alpha; flatten to RGB first or encoding throws. */
    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available in this JRE");
        }
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

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private ScanAnalysis parseModelResponse(String content, ScanPlan plan, String label) {
        ScanAnalysis analysis = new ScanAnalysis();
        String jsonText = extractJson(content);

        if (jsonText.length() != content.trim().length()) {
            log.debug("[{}] stripped {} chars of non-JSON wrapper from the response",
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
                    log.debug("[{}] model used 'predictions' key instead of 'picks'; handled", label);
                }
            }

            int cap = plan.isFullCoverage() ? Integer.MAX_VALUE : plan.getMaxPicks();

            if (!picksNode.isArray()) {
                log.warn("[{}] parsed JSON has no picks/predictions array. Keys present: {}",
                        label, fieldNames(root));
            } else {
                int available = picksNode.size();
                int count = 0;
                for (JsonNode pickNode : picksNode) {
                    if (count >= cap) {
                        log.info("[{}] plan cap reached: kept {} of {} pick(s) returned",
                                label, cap, available);
                        break;
                    }
                    PickPrediction pick = new PickPrediction();
                    pick.setSectionIndex(pickNode.path("sectionIndex").asInt(
                            pickNode.path("matchNumber").asInt(count + 1)));
                    pick.setMatchLabel(pickNode.path("matchLabel").asText(""));
                    pick.setOriginalPick(pickNode.path("originalPick").asText(""));
                    pick.setPrediction(pickNode.path("prediction").asText(""));
                    pick.setConfidence(pickNode.path("confidence").asText(""));
                    pick.setAnalysis(pickNode.path("analysis").asText(
                            pickNode.path("reason").asText("")));
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
            // Log it. The old code swallowed this silently and returned a
            // valid-looking empty result, making parse failures indistinguishable
            // from success upstream.
            log.warn("[{}] JSON parse FAILED: {}. First 500 chars of payload: {}",
                    label, ex.getMessage(), truncate(jsonText, 500));
            analysis.rawModelOutput = content;
        }

        return analysis;
    }

    /** Strips ```json fences etc, in case the model doesn't follow instructions perfectly. */
    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd != -1) {
                trimmed = trimmed.substring(0, fenceEnd);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> splitCsv(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
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
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Never print a key in full. Shows only enough to identify which key is loaded. */
    private static String mask(String key) {
        if (isBlank(key)) {
            return "<none>";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 3) +
                " (len " + key.length() + ")";
    }

    /** Replaces the base64 data URI with a placeholder so log files stay readable. */
    private String redactBody(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            return json.replaceAll("data:image/[a-zA-Z]+;base64,[A-Za-z0-9+/=]+",
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

    private static Integer asInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : null;
    }

    private static String nz(Integer i) {
        return i == null ? "-" : String.valueOf(i);
    }

    /** Walks the cause chain so wrapped exceptions still surface something useful. */
    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        String msg = ex.getMessage();
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                msg = cur.getMessage();
            }
        }
        return msg == null ? ex.toString() : msg;
    }
}