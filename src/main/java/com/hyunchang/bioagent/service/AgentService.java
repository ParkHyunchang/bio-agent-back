package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.ExperimentSearchResult;
import com.hyunchang.bioagent.dto.GelPredictResult;
import com.hyunchang.bioagent.dto.InterpretationResult;
import com.hyunchang.bioagent.dto.SearchResponse;
import com.hyunchang.bioagent.entity.GelRecord;
import com.hyunchang.bioagent.repository.GelRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final int MAX_ITERATIONS = 5;

    private static final String SYSTEM_PROMPT = """
            당신은 PCR 젤 전기영동 분석 전문 AI 에이전트입니다.
            사용자가 PCR 젤 이미지를 업로드하면 ML 모델과 해석 도구를 사용하여
            qPCR Ct값을 예측하고 결과의 의미를 전문적으로 설명합니다.

            ## 도구 사용 순서 (이미지가 있을 때 반드시 이 순서 준수)
            1. analyze_gel_image 호출 → Ct값, 모델 성능, 밴드 특징 획득
            2. interpret_result 호출 → 획득한 값을 파라미터로 전달하여 구조화된 해석 획득
            3. 두 도구의 결과를 종합하여 자연어로 답변

            ## 답변 형식 (이미지 분석 시)
            - **분류**: interpret_result의 classification 값 명시
            - **Ct값 의미**: ctRangeDescription 설명
            - **밴드 품질**: bandQuality 값과 이미지 상태 설명
            - **모델 신뢰도**: modelReliability 값과 그 의미 설명
            - **권장 사항**: recommendation 내용, retestRecommended가 true면 재검 강조

            ## 이미지 분석 시 도구 순서
            1. analyze_gel_image → Ct값, 모델 성능, 밴드 특징 획득
            2. interpret_result  → 구조화된 해석 획득
            3. search_past_experiments → 과거 유사 실험과 비교 (totalRecords > 0일 때)
            4. 세 도구 결과를 종합하여 자연어 답변

            ## search_past_experiments 결과 활용
            - similarCount가 0이면 "유사 실험 없음"으로 처리
            - inTypicalRange가 true/false에 따라 정상/이상 여부 언급
            - 과거 실험 통계(평균·범위)와 현재 값을 비교하여 맥락 제공

            ## search_papers 사용 시점
            - 사용자가 논문, 관련 연구, 과학적 근거를 요청할 때
            - 분석 결과에 대한 과학적 맥락이 필요할 때 (예: 특정 Ct 임계값의 근거)
            - 검색어는 영어로 작성하고 구체적으로 입력 (예: "qPCR Ct value 35 positive threshold clinical")
            - 검색 결과의 논문 제목·저자·저널을 인용하여 답변에 신뢰성 부여
            - ⚠️ 한 응답 내에서 search_papers는 반드시 1회만 호출하세요. 결과가 없어도 재시도하지 마세요.

            ## 일반 질문 시
            - 도구 없이 PCR/qPCR 전문 지식으로 답변
            - 한국어로 답변
            """;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final GelService gelService;
    private final InterpretationService interpretationService;
    private final ConversationStore conversationStore;
    private final GelRecordRepository gelRecordRepository;
    private final PubMedService pubMedService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    // ── 공개 메서드 ────────────────────────────────────────────────

    /**
     * 에이전트 채팅. 세션 히스토리를 유지하며 tool use 루프를 실행합니다.
     *
     * @param sessionId   대화 세션 ID
     * @param userMessage 사용자 텍스트 질문
     * @param imageBytes  PCR 젤 이미지 바이트 (null 가능)
     * @param filename    이미지 파일명 (null 가능)
     * @param contentType 이미지 MIME 타입 (null 가능)
     */
    public String chat(String sessionId, String userMessage, byte[] imageBytes, String filename, String contentType) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Anthropic API 키가 설정되지 않았습니다.";
        }

        // 이전 대화 히스토리 로드 (Claude API용 - base64 제거)
        List<Map<String, Object>> messages = conversationStore.loadForClaude(sessionId);
        int historySize = messages.size();
        log.info("대화 히스토리 로드: sessionId={}, 이전 메시지={}개", sessionId, historySize);

        // 새 사용자 메시지 추가
        messages.add(buildUserMessage(userMessage, imageBytes, contentType));

        List<Map<String, Object>> tools = buildTools(imageBytes != null);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            JsonNode response = callClaude(messages, tools);
            String stopReason = response.path("stop_reason").asText();
            log.info("에이전트 루프 #{} stop_reason={}", i + 1, stopReason);

            if ("end_turn".equals(stopReason)) {
                String text = extractText(response);
                // 최종 assistant 응답도 히스토리에 포함해 저장
                messages.add(Map.of("role", "assistant", "content",
                        List.of(Map.of("type", "text", "text", text))));
                conversationStore.save(sessionId, messages);
                return text;
            }

            if ("tool_use".equals(stopReason)) {
                messages.add(Map.of("role", "assistant", "content", jsonNodeToObject(response.path("content"))));
                List<Map<String, Object>> toolResults = executeTools(response.path("content"), imageBytes, filename);
                messages.add(Map.of("role", "user", "content", toolResults));
            } else {
                break;
            }
        }

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

    // ── 메시지 빌더 ───────────────────────────────────────────────

    private Map<String, Object> buildUserMessage(String text, byte[] imageBytes, String contentType) {
        List<Map<String, Object>> content = new ArrayList<>();

        if (imageBytes != null) {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = (contentType != null && contentType.startsWith("image/")) ? contentType : "image/png";
            content.add(Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", mediaType,
                            "data", base64
                    )
            ));
        }

        content.add(Map.of("type", "text", "text", text));
        return Map.of("role", "user", "content", content);
    }

    // ── 도구 정의 ─────────────────────────────────────────────────

    private List<Map<String, Object>> buildTools(boolean hasImage) {
        List<Map<String, Object>> tools = new ArrayList<>();

        if (hasImage) {
            tools.add(Map.of(
                    "name", "analyze_gel_image",
                    "description", "업로드된 PCR 젤 이미지를 ML 모델로 분석하여 Ct값을 예측합니다. "
                            + "밴드 강도, 면적, 너비 등 특징을 추출하고 예측 Ct값을 반환합니다.",
                    "input_schema", Map.of("type", "object", "properties", Map.of())
            ));
        }

        tools.add(Map.of(
                "name", "get_model_status",
                "description", "현재 학습된 ML 모델의 상태를 조회합니다. "
                        + "훈련 샘플 수, R², RMSE 등 모델 성능 지표를 반환합니다.",
                "input_schema", Map.of("type", "object", "properties", Map.of())
        ));

        tools.add(Map.of(
                "name", "interpret_result",
                "description", "예측된 Ct값과 모델 성능 지표를 바탕으로 구조화된 해석 결과를 반환합니다. "
                        + "analyze_gel_image 호출 후 반드시 이 도구를 호출하여 해석을 완성하세요.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "predicted_ct",    Map.of("type", "number",  "description", "analyze_gel_image에서 얻은 예측 Ct값"),
                                "model_r2",        Map.of("type", "number",  "description", "모델 R² 값"),
                                "model_rmse",      Map.of("type", "number",  "description", "모델 RMSE 값"),
                                "band_intensity",  Map.of("type", "number",  "description", "밴드 강도 (features.band_intensity)"),
                                "lanes_detected",  Map.of("type", "integer", "description", "검출된 밴드 수 (features.lanes_detected)")
                        ),
                        "required", List.of("predicted_ct", "model_r2", "model_rmse")
                )
        ));

        tools.add(Map.of(
                "name", "search_past_experiments",
                "description", "과거 훈련 데이터에서 현재 예측 Ct값과 유사한 실험을 검색합니다. " +
                        "유사 실험 목록, 통계(평균·범위), 현재 값이 과거 데이터의 일반 범위 내에 있는지 반환합니다.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "predicted_ct", Map.of("type", "number", "description", "비교할 현재 예측 Ct값"),
                                "range",        Map.of("type", "number", "description", "검색 범위 ±Ct (기본값: 5)")
                        ),
                        "required", List.of("predicted_ct")
                )
        ));

        tools.add(Map.of(
                "name", "search_papers",
                "description", "PubMed에서 관련 논문을 검색합니다. " +
                        "PCR/qPCR 방법론, Ct값 임계값, 특정 질병 진단 기준 등 과학적 근거가 필요할 때 사용하세요.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query",       Map.of("type", "string",  "description", "영어 검색어 (예: 'qPCR Ct value threshold positive detection')"),
                                "max_results", Map.of("type", "integer", "description", "반환할 최대 논문 수 (기본값: 5)")
                        ),
                        "required", List.of("query")
                )
        ));

        return tools;
    }

    // ── 도구 실행 ─────────────────────────────────────────────────

    private List<Map<String, Object>> executeTools(JsonNode contentArray, byte[] imageBytes, String filename) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode block : contentArray) {
            if (!"tool_use".equals(block.path("type").asText())) continue;

            String toolName = block.path("name").asText();
            String toolUseId = block.path("id").asText();

            String resultContent;
            try {
                resultContent = switch (toolName) {
                    case "analyze_gel_image"     -> runAnalyzeGelImage(imageBytes, filename);
                    case "get_model_status"      -> runGetModelStatus();
                    case "interpret_result"      -> runInterpretResult(block.path("input"));
                    case "search_past_experiments" -> runSearchPastExperiments(block.path("input"));
                    case "search_papers"           -> runSearchPapers(block.path("input"));
                    default -> "{\"error\": \"알 수 없는 도구: " + toolName + "\"}";
                };
                log.info("도구 실행 완료: {} → {}자", toolName, resultContent.length());
            } catch (Exception e) {
                log.error("도구 실행 실패: {}", toolName, e);
                resultContent = "{\"error\": \"" + e.getMessage() + "\"}";
            }

            results.add(Map.of(
                    "type", "tool_result",
                    "tool_use_id", toolUseId,
                    "content", resultContent
            ));
        }

        return results;
    }

    private String runAnalyzeGelImage(byte[] imageBytes, String filename) throws Exception {
        if (imageBytes == null) return "{\"error\": \"이미지가 없습니다.\"}";
        GelPredictResult result = gelService.predictFromBytes(imageBytes, filename != null ? filename : "image.png");
        return objectMapper.writeValueAsString(result);
    }

    private String runGetModelStatus() throws Exception {
        Map<String, Object> status = gelService.getModelStatus();
        return objectMapper.writeValueAsString(status);
    }

    private String runSearchPastExperiments(JsonNode input) throws Exception {
        double predictedCt = input.path("predicted_ct").asDouble(0);
        double range = input.path("range").asDouble(5.0);

        List<GelRecord> all = gelRecordRepository.findAll();
        List<GelRecord> similar = gelRecordRepository.findSimilarByCt(
                predictedCt, predictedCt - range, predictedCt + range);

        // 전체 통계
        ExperimentSearchResult.Statistics allStats = buildStats(all);

        // 유사 실험 통계
        ExperimentSearchResult.Statistics similarStats = similar.isEmpty() ? null : buildStats(similar);

        // 현재 값이 전체 데이터 평균±2σ 범위 내인지 확인
        boolean inTypicalRange = false;
        if (allStats != null && all.size() >= 3) {
            double lo = allStats.getAvgCt() - 2 * allStats.getStdCt();
            double hi = allStats.getAvgCt() + 2 * allStats.getStdCt();
            inTypicalRange = predictedCt >= lo && predictedCt <= hi;
        }

        List<ExperimentSearchResult.ExperimentItem> items = similar.stream()
                .map(r -> ExperimentSearchResult.ExperimentItem.builder()
                        .id(r.getId())
                        .fileName(r.getFileName())
                        .ctValue(r.getCtValue())
                        .bandIntensity(r.getBandIntensity())
                        .date(r.getCreatedAt() != null ? r.getCreatedAt().toLocalDate().toString() : null)
                        .build())
                .toList();

        ExperimentSearchResult result = ExperimentSearchResult.builder()
                .totalRecords(all.size())
                .similarCount(similar.size())
                .similarExperiments(items)
                .allStats(allStats)
                .similarStats(similarStats)
                .inTypicalRange(inTypicalRange)
                .build();

        log.info("과거 실험 검색: predictedCt={}, range=±{}, 전체={}, 유사={}",
                predictedCt, range, all.size(), similar.size());
        return objectMapper.writeValueAsString(result);
    }

    private String runSearchPapers(JsonNode input) throws Exception {
        String query = input.path("query").asText("");
        int maxResults = input.path("max_results").asInt(5);
        if (query.isBlank()) return "{\"error\": \"검색어가 비어 있습니다.\"}";

        SearchResponse response = pubMedService.search(query, 1, maxResults);

        List<Map<String, Object>> papers = response.getPapers().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pmid",    p.getPmid());
                    m.put("title",   p.getTitle());
                    m.put("authors", p.getAuthors() != null && !p.getAuthors().isEmpty()
                            ? p.getAuthors().get(0) + (p.getAuthors().size() > 1 ? " et al." : "")
                            : "");
                    m.put("journal", p.getJournal());
                    m.put("pubDate", p.getPubDate());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query",       query);
        result.put("total",       response.getTotal());
        result.put("papers",      papers);
        result.put("tooBroad",    response.isTooBroad());

        log.info("논문 검색 완료: query='{}', 결과={}건", query, papers.size());
        return objectMapper.writeValueAsString(result);
    }

    private ExperimentSearchResult.Statistics buildStats(List<GelRecord> records) {
        if (records.isEmpty()) return null;
        double avg = records.stream().mapToDouble(GelRecord::getCtValue).average().orElse(0);
        double min = records.stream().mapToDouble(GelRecord::getCtValue).min().orElse(0);
        double max = records.stream().mapToDouble(GelRecord::getCtValue).max().orElse(0);
        double std = Math.sqrt(records.stream()
                .mapToDouble(r -> Math.pow(r.getCtValue() - avg, 2)).average().orElse(0));
        return ExperimentSearchResult.Statistics.builder()
                .avgCt(Math.round(avg * 100.0) / 100.0)
                .minCt(min)
                .maxCt(max)
                .stdCt(Math.round(std * 100.0) / 100.0)
                .build();
    }

    private String runInterpretResult(JsonNode input) throws Exception {
        double predictedCt   = input.path("predicted_ct").asDouble(0);
        double modelR2       = input.path("model_r2").asDouble(0);
        double modelRmse     = input.path("model_rmse").asDouble(0);
        double bandIntensity = input.path("band_intensity").asDouble(0);
        int lanesDetected    = input.path("lanes_detected").asInt(0);

        InterpretationResult result = interpretationService.interpret(
                predictedCt, modelR2, modelRmse, bandIntensity, lanesDetected);
        return objectMapper.writeValueAsString(result);
    }

    // ── Claude API 호출 ───────────────────────────────────────────

    private JsonNode callClaude(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", 2000);
        requestBody.put("system", SYSTEM_PROMPT);
        requestBody.put("tools", tools);
        requestBody.put("messages", messages);

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
            log.error("Claude API 호출 실패", e);
            throw new RuntimeException("Claude API 호출 실패: " + e.getMessage(), e);
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────

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
