package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.PaperDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final String GENERIC_ERROR_MESSAGE = "AI 분석 중 오류가 발생했습니다.";

    @Value("${anthropic.api.key:}")
    private String apiKey;

    /** anthropicRestClient(빈)와 동일 설정값을 SSE HttpURLConnection에서도 사용. */
    @Value("${app.anthropic.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${app.anthropic.read-timeout-ms:180000}")
    private int readTimeoutMs;

    private final RestClient anthropicRestClient;
    private final ObjectMapper objectMapper;

    public String reviewPaper(PaperDetail paper) {
        return reviewPaper(paper, "normal", "default");
    }

    public String reviewPaper(PaperDetail paper, String length, String perspective) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Anthropic API 키가 설정되지 않았습니다. application.yml의 anthropic.api.key를 설정해주세요.";
        }

        ReviewPrompt rp = buildPrompt(paper, length, perspective);

        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", rp.maxTokens,
                "messages", List.of(Map.of("role", "user", "content", rp.prompt))
        );

        try {
            String response = anthropicRestClient.post()
                    .uri(ANTHROPIC_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String stopReason = root.path("stop_reason").asText("");
            if ("max_tokens".equals(stopReason)) {
                log.warn("Claude 응답이 max_tokens({})에서 잘림 pmid={}. max_tokens 상향 필요.",
                        rp.maxTokens, paper.getPmid());
            }
            return root.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            log.error("Claude API 호출 오류 (pmid={})", paper.getPmid(), e);
            return GENERIC_ERROR_MESSAGE;
        }
    }

    /**
     * Anthropic Messages API의 SSE 스트리밍을 받아 텍스트 청크 단위로 onChunk를 호출.
     * 호출자(SseEmitter)는 onChunk를 받아 클라이언트로 전달한다. 전체 누적 텍스트를 반환.
     */
    public static class ApiKeyMissingException extends RuntimeException {
        public ApiKeyMissingException(String msg) { super(msg); }
    }

    public String streamReviewPaper(PaperDetail paper, String length, String perspective,
                                    Consumer<String> onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiKeyMissingException(
                    "Anthropic API 키가 설정되지 않았습니다. 서버의 ANTHROPIC_API_KEY 환경변수를 설정해주세요.");
        }

        ReviewPrompt rp = buildPrompt(paper, length, perspective);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", rp.maxTokens);
        requestBody.put("stream", true);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", rp.prompt)));

        StringBuilder accumulated = new StringBuilder();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(ANTHROPIC_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("accept", "text/event-stream");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            byte[] payload = objectMapper.writeValueAsBytes(requestBody);
            conn.getOutputStream().write(payload);

            int status = conn.getResponseCode();
            InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (status >= 400) {
                String errBody = stream != null ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
                log.error("Claude SSE 오류 status={} body={}", status, errBody);
                onChunk.accept(GENERIC_ERROR_MESSAGE);
                return GENERIC_ERROR_MESSAGE;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    JsonNode evt = objectMapper.readTree(data);
                    String type = evt.path("type").asText("");
                    if ("content_block_delta".equals(type)) {
                        String text = evt.path("delta").path("text").asText("");
                        if (!text.isEmpty()) {
                            accumulated.append(text);
                            onChunk.accept(text);
                        }
                    } else if ("message_stop".equals(type)) {
                        break;
                    }
                }
            }
            return accumulated.toString();
        } catch (Exception e) {
            log.error("Claude SSE 호출 오류 (pmid={})", paper.getPmid(), e);
            String fallback = accumulated.length() > 0 ? accumulated.toString() : GENERIC_ERROR_MESSAGE;
            if (accumulated.length() == 0) onChunk.accept(GENERIC_ERROR_MESSAGE);
            return fallback;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private record ReviewPrompt(String prompt, int maxTokens) {}

    private ReviewPrompt buildPrompt(PaperDetail paper, String length, String perspective) {
        String authorsStr = paper.getAuthors() != null
                ? String.join(", ", paper.getAuthors())
                : "저자 정보 없음";

        boolean hasFullText = paper.getFullText() != null && !paper.getFullText().isBlank();
        String contentLabel = hasFullText ? "전체 본문 (PMC)" : "초록";
        String contentBody = hasFullText ? paper.getFullText() : paper.getAbstractText();
        // 한국어는 토큰 효율이 낮아 (1자 ≈ 2~3 토큰) 넉넉히 잡아야 잘리지 않음
        int baseMax = hasFullText ? 8000 : 4000;

        String lengthHint;
        int maxTokens;
        switch (length == null ? "normal" : length) {
            case "short" -> {
                lengthHint = "각 섹션을 1-2문장으로 매우 간결하게 요약해주세요.";
                maxTokens = Math.max(1500, baseMax / 3);
            }
            case "detailed" -> {
                lengthHint = "각 섹션을 풍부한 세부 사항과 함께 자세히 설명해주세요. 가능하다면 수치나 구체적 예시도 포함합니다.";
                maxTokens = baseMax * 3 / 2;
            }
            default -> {
                lengthHint = "각 섹션을 2-4문장으로 균형 있게 정리해주세요.";
                maxTokens = baseMax;
            }
        }

        String perspectiveBlock = switch (perspective == null ? "default" : perspective) {
            case "clinical" -> """
                    분석 관점: **임상 적용**에 초점을 맞춰주세요. 환자 인구, 임상적 유의성, 치료/진단에의 영향, 한계와 일반화 가능성을 강조합니다.
                    """;
            case "mechanism" -> """
                    분석 관점: **분자/세포 기전**에 초점을 맞춰주세요. 신호 경로, 단백질·유전자 상호작용, 실험적 증거의 인과 관계를 강조합니다.
                    """;
            case "statistics" -> """
                    분석 관점: **통계 및 방법론**에 초점을 맞춰주세요. 표본 크기, 통계 검정, 효과 크기, p-value, 신뢰구간, 잠재적 편향을 강조합니다.
                    """;
            default -> "";
        };

        String prompt = String.format("""
                당신은 생명과학 연구 전문가입니다. 다음 논문의 내용을 분석하여 한국어로 요약해주세요.

                제목: %s
                저자: %s
                저널: %s (%s)

                %s:
                %s

                %s
                길이 가이드: %s

                다음 형식으로 정리해주세요:

                ## 연구 목적
                무엇을 알아내려 했는지 설명해주세요.

                ## 주요 방법
                어떤 실험/분석 방법을 사용했는지 설명해주세요.

                ## 핵심 결과
                무엇을 발견했는지 핵심 내용을 설명해주세요.

                ## 의의 및 시사점
                이 연구가 왜 중요한지, 향후 연구나 임상에 어떤 영향을 줄 수 있는지 설명해주세요.
                """,
                paper.getTitle(),
                authorsStr,
                paper.getJournal(),
                paper.getPubDate(),
                contentLabel,
                contentBody,
                perspectiveBlock,
                lengthHint
        );

        return new ReviewPrompt(prompt, maxTokens);
    }
}
