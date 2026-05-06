package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.ExperimentSearchResult;
import com.hyunchang.bioagent.dto.InterpretationResult;
import com.hyunchang.bioagent.dto.SearchResponse;
import com.hyunchang.bioagent.entity.GelRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolHandler {

    private final GelTrainingService gelTrainingService;
    private final GelDataService gelDataService;
    private final InterpretationService interpretationService;
    private final PubMedService pubMedService;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> TOOL_LABELS = Map.of(
            "analyze_gel_image",       "젤 이미지 분석 중...",
            "get_model_status",        "ML 모델 상태 확인 중...",
            "interpret_result",        "분석 결과 해석 중...",
            "search_past_experiments", "학습 데이터 검색 중...",
            "search_papers",           "관련 논문 검색 중..."
    );

    public List<Map<String, Object>> buildTools(boolean hasImage) {
        List<Map<String, Object>> tools = new ArrayList<>();

        if (hasImage) {
            tools.add(Map.of(
                    "name", "analyze_gel_image",
                    "description", "업로드된 mecA PCR 젤 이미지를 ML 모델로 레인별 분석합니다. "
                            + "M, 10^8~10^1, NTC 총 10개 레인에서 각각 밴드 특징을 추출하고 "
                            + "레인별 예측 Ct값 배열을 반환합니다. "
                            + "저농도 레인(10^1~10^3)의 검출 여부를 집중 분석합니다.",
                    "input_schema", Map.of("type", "object", "properties", Map.of())
            ));
        }

        tools.add(Map.of(
                "name", "get_model_status",
                "description", "현재 학습된 ML 모델의 상태를 조회합니다. "
                        + "학습 샘플 수, R², RMSE 등 모델 성능 지표를 반환합니다.",
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
                "description", "등록된 학습 데이터에서 현재 예측 Ct값과 유사한 학습 샘플을 검색합니다. "
                        + "유사 학습 데이터 목록, 통계(평균·범위), 현재 값이 학습 데이터 일반 범위 내에 있는지 반환합니다. "
                        + "사용자에게 보여줄 때는 반드시 '학습 데이터'라는 용어를 사용하고 '과거 실험' 같은 표현은 사용하지 마세요.",
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
                "description", "PubMed에서 관련 논문을 검색합니다. "
                        + "PCR/qPCR 방법론, Ct값 임계값, 특정 질병 진단 기준 등 과학적 근거가 필요할 때 사용하세요.",
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

    public List<Map<String, Object>> executeTools(JsonNode contentArray, byte[] imageBytes, String filename,
                                                   Consumer<String> progressCallback) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode block : contentArray) {
            if (!"tool_use".equals(block.path("type").asText())) continue;

            String toolName = block.path("name").asText();
            String toolUseId = block.path("id").asText();

            if (progressCallback != null) {
                progressCallback.accept(TOOL_LABELS.getOrDefault(toolName, toolName + " 실행 중..."));
            }

            String resultContent;
            try {
                resultContent = switch (toolName) {
                    case "analyze_gel_image"       -> runAnalyzeGelImage(imageBytes, filename);
                    case "get_model_status"        -> runGetModelStatus();
                    case "interpret_result"        -> runInterpretResult(block.path("input"));
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
        log.info("젤 이미지 분석 시작: file={}, size={}bytes", filename, imageBytes.length);
        var lanes = gelTrainingService.predictMultiLaneFromBytes(
                imageBytes, filename != null ? filename : "image.png");
        long detected = lanes.stream()
                .filter(l -> !Boolean.TRUE.equals(l.getIsNegative())
                          && !"M".equals(l.getConcentrationLabel())
                          && !"NTC".equals(l.getConcentrationLabel()))
                .count();
        log.info("젤 이미지 분석 완료: 전체 레인={}개, 밴드 검출={}개", lanes.size(), detected);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lanes", lanes);
        result.put("lane_count", lanes.size());
        return objectMapper.writeValueAsString(result);
    }

    private String runGetModelStatus() throws Exception {
        return objectMapper.writeValueAsString(gelTrainingService.getModelStatus());
    }

    private String runSearchPastExperiments(JsonNode input) throws Exception {
        double predictedCt = input.path("predicted_ct").asDouble(0);
        double range = input.path("range").asDouble(5.0);

        List<GelRecord> all = gelDataService.findAllEntities();
        List<GelRecord> similar = gelDataService.findSimilarByCt(
                predictedCt, predictedCt - range, predictedCt + range);

        ExperimentSearchResult.Statistics allStats = buildStats(all);
        ExperimentSearchResult.Statistics similarStats = similar.isEmpty() ? null : buildStats(similar);

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

        log.info("학습 데이터 검색: predictedCt={}, range=±{}, 전체={}, 유사={}",
                predictedCt, range, all.size(), similar.size());
        // 참고: ExperimentSearchResult의 필드명(similarCount/similarExperiments/totalRecords)은
        // API 호환성 유지 위해 유지하되, 프롬프트에서 "학습 데이터" 표현으로 렌더링하도록 지시.
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
                            ? p.getAuthors().get(0) + (p.getAuthors().size() > 1 ? " et al." : "") : "");
                    m.put("journal", p.getJournal());
                    m.put("pubDate", p.getPubDate());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query",    query);
        result.put("total",    response.getTotal());
        result.put("papers",   papers);
        result.put("tooBroad", response.isTooBroad());

        log.info("논문 검색 완료: query='{}', 결과={}건", query, papers.size());
        return objectMapper.writeValueAsString(result);
    }

    private String runInterpretResult(JsonNode input) throws Exception {
        double predictedCt   = input.path("predicted_ct").asDouble(0);
        double modelR2       = input.path("model_r2").asDouble(0);
        double modelRmse     = input.path("model_rmse").asDouble(0);
        double bandIntensity = input.path("band_intensity").asDouble(0);
        int lanesDetected    = input.path("lanes_detected").asInt(0);
        log.info("결과 해석 요청: predictedCt={}, modelR2={}, modelRmse={}", predictedCt, modelR2, modelRmse);
        InterpretationResult result = interpretationService.interpret(
                predictedCt, modelR2, modelRmse, bandIntensity, lanesDetected);
        log.info("결과 해석 완료: classification={}, reliability={}, retest={}",
                result.getClassification(), result.getModelReliability(), result.isRetestRecommended());
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
}
