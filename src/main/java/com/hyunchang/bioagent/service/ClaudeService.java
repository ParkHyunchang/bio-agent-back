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

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final String GENERIC_ERROR_MESSAGE = "AI 분석 중 오류가 발생했습니다.";

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final RestClient anthropicRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String reviewPaper(PaperDetail paper) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Anthropic API 키가 설정되지 않았습니다. application.yml의 anthropic.api.key를 설정해주세요.";
        }

        String authorsStr = paper.getAuthors() != null
                ? String.join(", ", paper.getAuthors())
                : "저자 정보 없음";

        String prompt = String.format("""
                당신은 생명과학 연구 전문가입니다. 다음 논문의 내용을 분석하여 한국어로 요약해주세요.

                제목: %s
                저자: %s
                저널: %s (%s)

                논문 내용:
                %s

                다음 형식으로 정리해주세요:

                ## 연구 목적
                무엇을 알아내려 했는지 2-3문장으로 설명해주세요.

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
                paper.getAbstractText()
        );

        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", 1500,
                "messages", List.of(Map.of("role", "user", "content", prompt))
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
            return root.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            log.error("Claude API 호출 오류 (pmid={})", paper.getPmid(), e);
            return GENERIC_ERROR_MESSAGE;
        }
    }
}
