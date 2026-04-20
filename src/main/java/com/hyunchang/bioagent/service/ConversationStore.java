package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.SessionSummaryDto;
import com.hyunchang.bioagent.entity.AgentSession;
import com.hyunchang.bioagent.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 세션별 대화 히스토리를 DB에 저장합니다.
 * claude_messages: Claude API 전송용 전체 메시지
 * display_messages: 프론트엔드 표시용 단순화 메시지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationStore {

    private final AgentSessionRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
            new TypeReference<>() {};

    // ── Claude API 메시지 ─────────────────────────────────────────

    public List<Map<String, Object>> load(String sessionId) {
        return repository.findBySessionId(sessionId)
                .map(s -> deserialize(s.getClaudeMessages()))
                .orElse(new ArrayList<>());
    }

    @Transactional
    public void save(String sessionId, List<Map<String, Object>> claudeMessages) {
        String claudeJson  = serialize(claudeMessages);
        String displayJson = serialize(deriveDisplayMessages(claudeMessages));

        AgentSession session = repository.findBySessionId(sessionId)
                .orElse(AgentSession.builder().sessionId(sessionId).build());

        session.setClaudeMessages(claudeJson);
        session.setDisplayMessages(displayJson);
        repository.save(session);

        log.debug("세션 저장: sessionId={}, claude={}turns, display={}turns",
                sessionId, claudeMessages.size(), deserialize(displayJson).size());
    }

    @Transactional
    public void clear(String sessionId) {
        repository.deleteBySessionId(sessionId);
        log.info("세션 삭제: sessionId={}", sessionId);
    }

    // ── 프론트엔드 표시용 메시지 ──────────────────────────────────

    public List<Map<String, Object>> loadDisplay(String sessionId) {
        return repository.findBySessionId(sessionId)
                .map(s -> deserialize(s.getDisplayMessages()))
                .orElse(new ArrayList<>());
    }

    public List<SessionSummaryDto> listSessions() {
        return repository.findAllByOrderByUpdatedAtDesc().stream()
                .map(s -> {
                    List<Map<String, Object>> display = deserialize(s.getDisplayMessages());
                    String preview = display.stream()
                            .filter(m -> "user".equals(m.get("role")))
                            .findFirst()
                            .map(m -> (String) m.get("text"))
                            .orElse("(빈 대화)");
                    if (preview != null && preview.length() > 45)
                        preview = preview.substring(0, 45) + "…";
                    return new SessionSummaryDto(s.getSessionId(), preview, s.getCreatedAt(), s.getUpdatedAt());
                })
                .collect(Collectors.toList());
    }

    // ── 표시용 메시지 변환 ────────────────────────────────────────

    /**
     * Claude API 메시지에서 사용자에게 보여줄 대화만 추출합니다.
     * tool_use / tool_result 메시지는 제외하고 텍스트 버블만 남깁니다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deriveDisplayMessages(List<Map<String, Object>> claudeMessages) {
        List<Map<String, Object>> display = new ArrayList<>();

        for (Map<String, Object> msg : claudeMessages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            if (!(content instanceof List)) continue;

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

            // tool_result 메시지(도구 실행 결과)는 표시 불필요
            boolean isToolResult = blocks.stream()
                    .anyMatch(b -> "tool_result".equals(b.get("type")));
            if (isToolResult) continue;

            // 텍스트 블록만 추출
            String text = blocks.stream()
                    .filter(b -> "text".equals(b.get("type")))
                    .map(b -> (String) b.get("text"))
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst().orElse(null);

            // tool_use만 있고 텍스트 없는 assistant 메시지는 스킵
            if (text == null) continue;

            boolean hadImage = blocks.stream()
                    .anyMatch(b -> "image".equals(b.get("type")));

            // sanitize 후 저장된 이미지 참조 텍스트 감지
            if (!hadImage && text.contains("[첨부 이미지:")) {
                hadImage = true;
                text = text.replaceFirst("\\[첨부 이미지:.*?]\\s*", "").trim();
            }

            String displayRole = "assistant".equals(role) ? "agent" : "user";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", displayRole);
            item.put("text", text);
            if (hadImage) item.put("hadImage", true);
            display.add(item);
        }

        return display;
    }

    // ── JSON 직렬화 헬퍼 ─────────────────────────────────────────

    private String serialize(List<Map<String, Object>> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.error("직렬화 실패", e);
            return "[]";
        }
    }

    private List<Map<String, Object>> deserialize(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            log.error("역직렬화 실패", e);
            return new ArrayList<>();
        }
    }
}
