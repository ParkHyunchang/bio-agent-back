package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final int MAX_ITERATIONS = 5;

    private static final String SYSTEM_PROMPT = """
            당신은 mecA PCR 젤 전기영동 분석 전문 AI 에이전트입니다.
            사용자가 PCR 젤 이미지를 업로드하면 ML 모델과 해석 도구를 사용하여
            레인별 qPCR Ct값을 예측하고 결과의 의미를 전문적으로 설명합니다.

            ## mecA 멀티레인 젤 구조
            - 젤은 10개 레인으로 구성됩니다: M(래더), 10^8, 10^7, 10^6, 10^5, 10^4, 10^3, 10^2, 10^1, NTC
            - mecA 프라이머(Tm=59.72°C)의 앰플리콘은 약 280~300bp
            - 각 레인은 10배 희석 계열 (10^8이 최고 농도)

            ## 이미지 분석 시 도구 순서 (반드시 이 순서 준수)
            1. analyze_gel_image 호출 → 10개 레인별 Ct 예측값 및 밴드 특징 획득
            2. interpret_result 호출 → 대표 레인(10^5 권장)의 값으로 구조화된 해석 획득
            3. search_past_experiments 호출 → 과거 유사 실험 비교 (totalRecords > 0일 때)
            4. 세 도구 결과를 종합하여 자연어 답변

            ## analyze_gel_image 결과 해석 규칙
            - lanes 배열에서 label="M" 및 label="NTC"의 predicted_ct는 무의미하므로 해석에서 제외
            - NTC 레인의 is_negative=false이면 "오염 의심" 경고를 반드시 언급
            - is_saturated=true인 레인은 "포화 (Ct 과소평가 가능)"으로 표기
            - 저농도 레인(10^1, 10^2, 10^3)에서 is_negative=false이면 반드시 강조 (검출 한계 이하 검출)
            - LOD(검출 한계) = 밴드 검출된 레인(is_negative=false) 중 최저 농도 레인의 레이블로 정의

            ## 답변 형식 (이미지 분석 시)
            - **레인별 요약 표**: 레인 | 예측 Ct | 상태 (검출/미검출/포화/NTC)
            - **검출 한계 (LOD)**: 몇 배 희석까지 검출되었는지
            - **저농도 구간 분석** (10^1~10^3): 각 레인의 검출 여부와 Ct값 상세 설명
            - **QC 판정**: NTC 음성 여부, 포화 레인 유무
            - **권장 사항**: retestRecommended가 true면 재검 강조

            ## search_past_experiments 결과 활용
            - similarCount가 0이면 "유사 실험 없음"으로 처리
            - inTypicalRange가 true/false에 따라 정상/이상 여부 언급

            ## search_papers 사용 시점
            - 사용자가 논문, 관련 연구, 과학적 근거를 요청할 때
            - 검색어는 영어로 작성 (예: "qPCR Ct value threshold positive detection mecA MRSA")
            - ⚠️ 한 응답 내에서 search_papers는 반드시 1회만 호출하세요.

            ## 일반 질문 시
            - 도구 없이 PCR/qPCR 전문 지식으로 답변
            - 한국어로 답변
            """;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final AgentToolHandler agentToolHandler;
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    // ── 공개 메서드 ────────────────────────────────────────────────

    public String chat(String sessionId, String userMessage, byte[] imageBytes, String filename, String contentType) {
        return chat(sessionId, userMessage, imageBytes, filename, contentType, null);
    }

    public String chat(String sessionId, String userMessage, byte[] imageBytes, String filename, String contentType,
                       Consumer<String> progressCallback) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Anthropic API 키가 설정되지 않았습니다.";
        }

        log.info("에이전트 채팅 시작: sessionId={}, hasImage={}, message='{}'",
                sessionId, imageBytes != null,
                userMessage.length() > 60 ? userMessage.substring(0, 60) + "..." : userMessage);

        List<Map<String, Object>> messages = conversationStore.loadForClaude(sessionId);
        log.info("대화 히스토리 로드: sessionId={}, 이전 메시지={}개", sessionId, messages.size());

        messages.add(buildUserMessage(userMessage, imageBytes, contentType));
        List<Map<String, Object>> tools = agentToolHandler.buildTools(imageBytes != null);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            JsonNode response = callClaude(messages, tools);
            String stopReason = response.path("stop_reason").asText();
            log.info("에이전트 루프 #{} stop_reason={}", i + 1, stopReason);

            if ("end_turn".equals(stopReason)) {
                String text = extractText(response);
                log.info("에이전트 응답 완료: sessionId={}, 응답={}자", sessionId, text.length());
                messages.add(Map.of("role", "assistant", "content",
                        List.of(Map.of("type", "text", "text", text))));
                conversationStore.save(sessionId, messages);
                return text;
            }

            if ("tool_use".equals(stopReason)) {
                messages.add(Map.of("role", "assistant", "content", jsonNodeToObject(response.path("content"))));
                List<Map<String, Object>> toolResults = agentToolHandler.executeTools(
                        response.path("content"), imageBytes, filename, progressCallback);
                messages.add(Map.of("role", "user", "content", toolResults));
            } else {
                log.warn("에이전트 예상치 못한 stop_reason={}, 루프 종료", stopReason);
                break;
            }
        }

        log.warn("에이전트 최대 반복 횟수 초과: sessionId={}, maxIterations={}", sessionId, MAX_ITERATIONS);
        return "에이전트 응답 처리 중 오류가 발생했습니다.";
    }

    public void clearHistory(String sessionId) {
        conversationStore.clear(sessionId);
    }

    public List<Map<String, Object>> getDisplayHistory(String sessionId) {
        return conversationStore.loadDisplay(sessionId);
    }

    public List<com.hyunchang.bioagent.dto.SessionSummaryDto> getSessions() {
        return conversationStore.listSessions();
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private Map<String, Object> buildUserMessage(String text, byte[] imageBytes, String contentType) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (imageBytes != null) {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = (contentType != null && contentType.startsWith("image/")) ? contentType : "image/png";
            content.add(Map.of(
                    "type", "image",
                    "source", Map.of("type", "base64", "media_type", mediaType, "data", base64)
            ));
        }
        content.add(Map.of("type", "text", "text", text));
        return Map.of("role", "user", "content", content);
    }

    private JsonNode callClaude(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", 2000);
        requestBody.put("system", SYSTEM_PROMPT);
        requestBody.put("tools", tools);
        requestBody.put("messages", messages);
        log.info("Claude API 호출: model={}, messages={}개, tools={}개",
                MODEL, messages.size(), tools.size());
        try {
            String response = restClient.post()
                    .uri(ANTHROPIC_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("Claude API 호출 실패: {}", e.getMessage(), e);
            throw new RuntimeException("Claude API 호출 실패: " + e.getMessage(), e);
        }
    }

    private String extractText(JsonNode response) {
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        return "";
    }

    private Object jsonNodeToObject(JsonNode node) {
        try {
            return objectMapper.convertValue(node, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
