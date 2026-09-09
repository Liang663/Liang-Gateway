package com.liang.gateway.ai.internal.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.liang.gateway.ai.AiBadRequestException;
import com.liang.gateway.ai.AiNotFoundException;
import com.liang.gateway.ai.CallLogStats;
import com.liang.gateway.ai.ChatApi;
import com.liang.gateway.ai.ChatUpstream;
import com.liang.gateway.ai.ChatUsage;
import com.liang.gateway.ai.ModelView;
import com.liang.gateway.ai.TokenCounts;
import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.IdentityCodes;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigRepository;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogRepository;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmModelEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmModelRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultChatApi implements ChatApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatApi.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final LlmModelRepository modelRepository;
    private final LlmApikeyConfigRepository apikeyRepository;
    private final LlmCallLogRepository callLogRepository;
    private final AiClock aiClock;
    private final JsonMapper objectMapper;

    public DefaultChatApi(
            LlmModelRepository modelRepository,
            LlmApikeyConfigRepository apikeyRepository,
            LlmCallLogRepository callLogRepository,
            AiClock aiClock,
            JsonMapper objectMapper) {
        this.modelRepository = modelRepository;
        this.apikeyRepository = apikeyRepository;
        this.callLogRepository = callLogRepository;
        this.aiClock = aiClock;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<List<ModelView>> listModels() {
        return modelRepository
                .findByEnabledTrueOrderByNameAsc()
                .map(model -> new ModelView(
                        model.getName(),
                        model.getProvider(),
                        model.getInputPriceFenPerMillion(),
                        model.getOutputPriceFenPerMillion()))
                .collectList();
    }

    @Override
    public Mono<ChatUpstream> prepare(String model, byte[] requestBody) {
        if (model == null || model.isBlank()) {
            return Mono.error(new AiBadRequestException("model is required"));
        }
        byte[] inbound = requestBody == null ? new byte[0] : requestBody;
        return requireEnabledModel(model).flatMap(catalog -> apikeyRepository
                .findByCode(catalog.getApikeyCode())
                .switchIfEmpty(Mono.error(new AiNotFoundException("Model is not available")))
                .flatMap(apikey -> {
                    if (!apikey.isEnabled() || apikey.isExpired(aiClock.nowShanghai())) {
                        return Mono.error(new AiNotFoundException("Model is not available"));
                    }
                    PreparedBody prepared;
                    try {
                        prepared = prepareBody(inbound);
                    } catch (AiBadRequestException ex) {
                        return Mono.error(ex);
                    }
                    log.debug(
                            "Prepared upstream for model {} using key prefix {}",
                            catalog.getName(),
                            apikey.getPrefix());
                    Map<String, String> headers = new LinkedHashMap<>();
                    headers.put("Authorization", "Bearer " + apikey.getSecret());
                    headers.put("Content-Type", "application/json");
                    return Mono.just(new ChatUpstream(
                            chatCompletionsUrl(apikey.getBaseUrl()),
                            headers,
                            prepared.body(),
                            prepared.stream(),
                            apikey.getCode()));
                }));
    }

    @Override
    public Mono<Optional<ChatUsage>> readUsage(String model, byte[] jsonBody) {
        if (model == null || model.isBlank()) {
            return Mono.error(new AiBadRequestException("model is required"));
        }
        return requireModel(model).map(catalog -> {
            TokenCounts counts = tokenCounts(usageFromJson(jsonBody));
            if (counts == null) {
                return Optional.empty();
            }
            return Optional.of(toChatUsage(catalog, counts));
        });
    }

    @Override
    public Optional<TokenCounts> readSseUsageFrame(byte[] sseFrame) {
        return Optional.ofNullable(tokenCounts(usageFromSseEvent(sseFrame)));
    }

    @Override
    public Mono<ChatUsage> priceUsage(String model, TokenCounts tokens) {
        if (model == null || model.isBlank()) {
            return Mono.error(new AiBadRequestException("model is required"));
        }
        if (tokens == null) {
            return Mono.error(new AiBadRequestException("tokens is required"));
        }
        return requireModel(model).map(catalog -> toChatUsage(catalog, tokens));
    }

    @Override
    public boolean isFirstContentFrame(byte[] sseFrame) {
        JsonNode node = parseJson(extractSseData(sseFrame));
        if (node == null) {
            return false;
        }
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray()) {
            return false;
        }
        for (JsonNode choice : choices) {
            JsonNode content = choice.path("delta").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> recordCallLog(
            String llmApikeyCode,
            String model,
            boolean success,
            String message,
            Integer firstTokenMs,
            int totalDurationMs) {
        if (llmApikeyCode == null || llmApikeyCode.isBlank()) {
            return Mono.error(new AiBadRequestException("llmApikeyCode is required"));
        }
        if (model == null || model.isBlank()) {
            return Mono.error(new AiBadRequestException("model is required"));
        }
        return apikeyRepository
                .findByCode(llmApikeyCode)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optional -> {
                    LlmApikeyConfigEntity apikey = optional.orElse(null);
                    String prefix = apikey == null ? "" : apikey.getPrefix();
                    String secret = apikey == null ? null : apikey.getSecret();
                    String sanitized = sanitizeMessage(message, secret);
                    LocalDateTime now = aiClock.nowShanghai();
                    return callLogRepository
                            .save(LlmCallLogEntity.create(
                                    IdentityCodes.callLogCode(),
                                    llmApikeyCode,
                                    model,
                                    success,
                                    sanitized,
                                    firstTokenMs,
                                    totalDurationMs,
                                    now))
                            .doOnSuccess(unused -> log.debug(
                                    "Recorded call log for key prefix {} model {} success {}",
                                    prefix,
                                    model,
                                    success));
                })
                .then();
    }

    @Override
    public Mono<CallLogStats> stats(String llmApikeyCode, Instant from, Instant to, String model) {
        if (llmApikeyCode == null || llmApikeyCode.isBlank()) {
            return Mono.error(new AiBadRequestException("llmApikeyCode is required"));
        }
        LocalDateTime fromTime = from == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : LocalDateTime.ofInstant(from, AiClock.SHANGHAI);
        LocalDateTime toTime = to == null ? aiClock.nowShanghai() : LocalDateTime.ofInstant(to, AiClock.SHANGHAI);
        String modelFilter = model == null || model.isBlank() ? null : model;
        return callLogRepository
                .findForStats(llmApikeyCode, fromTime, toTime)
                .filter(row -> modelFilter == null || modelFilter.equals(row.getModel()))
                .collectList()
                .map(rows -> {
                    long total = rows.size();
                    long success = rows.stream().filter(LlmCallLogEntity::isSuccess).count();
                    var averageOpt = rows.stream()
                            .map(LlmCallLogEntity::getFirstTokenMs)
                            .filter(value -> value != null)
                            .mapToInt(Integer::intValue)
                            .average();
                    Double average = averageOpt.isPresent() ? averageOpt.getAsDouble() : null;
                    double failureRate = total == 0 ? 0.0d : (double) (total - success) / (double) total;
                    return new CallLogStats(total, success, average, failureRate);
                });
    }

    private Mono<LlmModelEntity> requireModel(String model) {
        return modelRepository
                .findByName(model)
                .switchIfEmpty(Mono.error(new AiNotFoundException("Model is not available")));
    }

    private Mono<LlmModelEntity> requireEnabledModel(String model) {
        return requireModel(model).flatMap(catalog -> {
            if (!catalog.isEnabled()) {
                return Mono.error(new AiNotFoundException("Model is not available"));
            }
            return Mono.just(catalog);
        });
    }

    private PreparedBody prepareBody(byte[] inbound) {
        if (inbound.length == 0) {
            throw new AiBadRequestException("request body is required");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(inbound);
        } catch (Exception ex) {
            throw new AiBadRequestException("request body must be JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AiBadRequestException("request body must be a JSON object");
        }
        ObjectNode object = (ObjectNode) root;
        boolean stream = object.path("stream").asBoolean(false);
        if (!stream) {
            return new PreparedBody(inbound, false);
        }
        JsonNode optionsNode = object.get("stream_options");
        ObjectNode options;
        if (optionsNode != null && optionsNode.isObject()) {
            options = (ObjectNode) optionsNode;
        } else {
            options = object.putObject("stream_options");
        }
        options.put("include_usage", true);
        try {
            return new PreparedBody(objectMapper.writeValueAsBytes(object), true);
        } catch (Exception ex) {
            throw new AiBadRequestException("failed to write request body");
        }
    }

    private JsonNode usageFromJson(byte[] payload) {
        JsonNode root = parseJson(payload == null ? "" : new String(payload, StandardCharsets.UTF_8));
        if (root == null) {
            return null;
        }
        return root.get("usage");
    }

    private JsonNode usageFromSseEvent(byte[] event) {
        String data = extractSseData(event);
        if (data.isBlank() || "[DONE]".equals(data.trim())) {
            return null;
        }
        JsonNode node = parseJson(data);
        if (node == null) {
            return null;
        }
        return node.get("usage");
    }

    private static TokenCounts tokenCounts(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return null;
        }
        JsonNode promptNode = usageNode.get("prompt_tokens");
        JsonNode completionNode = usageNode.get("completion_tokens");
        if (promptNode == null || !promptNode.isNumber() || completionNode == null || !completionNode.isNumber()) {
            return null;
        }
        return new TokenCounts(promptNode.asLong(), completionNode.asLong());
    }

    private static ChatUsage toChatUsage(LlmModelEntity catalog, TokenCounts counts) {
        long amountFen = amountFen(
                counts.promptTokens(),
                counts.completionTokens(),
                catalog.getInputPriceFenPerMillion(),
                catalog.getOutputPriceFenPerMillion());
        return new ChatUsage(counts.promptTokens(), counts.completionTokens(), amountFen);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String extractSseData(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return "";
        }
        String text = normalizeNewlines(new String(frame, StandardCharsets.UTF_8)).trim();
        StringBuilder data = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                String value = trimmed.substring("data:".length()).strip();
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        return data.length() == 0 ? text : data.toString();
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String chatCompletionsUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    static long amountFen(long promptTokens, long completionTokens, long inputFenPerMillion, long outputFenPerMillion) {
        BigDecimal amount = BigDecimal.valueOf(promptTokens)
                .multiply(BigDecimal.valueOf(inputFenPerMillion))
                .add(BigDecimal.valueOf(completionTokens).multiply(BigDecimal.valueOf(outputFenPerMillion)))
                .divide(MILLION, 0, RoundingMode.HALF_UP);
        return amount.longValue();
    }

    static String sanitizeMessage(String message, String secret) {
        if (message == null) {
            return null;
        }
        String sanitized = message;
        if (secret != null && !secret.isBlank()) {
            sanitized = sanitized.replace(secret, "***");
        }
        if (sanitized.length() > 512) {
            sanitized = sanitized.substring(0, 512);
        }
        return sanitized;
    }

    private record PreparedBody(byte[] body, boolean stream) {}
}
