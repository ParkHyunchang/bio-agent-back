package com.hyunchang.bioagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.SessionSummaryDto;
import com.hyunchang.bioagent.entity.AgentMessage;
import com.hyunchang.bioagent.entity.AgentSession;
import com.hyunchang.bioagent.repository.AgentMessageRepository;
import com.hyunchang.bioagent.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 세션별 대화 히스토리를 메시지 단위로 DB에 저장합니다.
 * agent_session  : 세션 메타데이터 (preview, created_at, updated_at)
 * agent_message  : 메시지 1건 = 1행 (role + content JSON + seq)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationStore {

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
            new TypeReference<>() {};

    // ── Claude API 메시지 로드 ────────────────────────────────────

    public List<Map<String, Object>> load(String sessionId) {
        return messageRepository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", m.getRole());
                    msg.put("content", deserializeContent(m.getContent()));
                    return msg;
                })
                .collect(Collectors.toList());
    }

    // ── 메시지 저장 (새 메시지만 추가) ───────────────────────────

    @Transactional
    public void save(String sessionId, List<Map<String, Object>> claudeMessages) {
        int existingCount = messageRepository.countBySessionId(sessionId);

        // 세션 upsert
        AgentSession session = sessionRepository.findBySessionId(sessionId)
                .orElse(AgentSession.builder().sessionId(sessionId).build());
        session.setPreview(derivePreview(claudeMessages));
        sessionRepository.save(session);

        // 신규 메시지만 INSERT
        int newCount = 0;
        for (int i = existingCount; i < claudeMessages.size(); i++) {
            Map<String, Object> msg = claudeMessages.get(i);
            AgentMessage agentMessage = AgentMessage.builder()
                    .sessionId(sessionId)
                    .role((String) msg.get("role"))
                    .content(serializeContent(msg.get("content")))
                    .seq(i)
                    .build();
            messageRepository.save(agentMessage);
            newCount++;
        }

        log.debug("세션 저장: sessionId={}, 기존={}건, 신규={}건",
                sessionId, existingCount, newCount);
    }

    // ── 세션 삭제 ─────────────────────────────────────────────────

    @Transactional
    public void clear(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteBySessionId(sessionId);
        log.info("세션 삭제: sessionId={}", sessionId);
    }

    // ── 프론트엔드 표시용 메시지 ──────────────────────────────────

    public List<Map<String, Object>> loadDisplay(String sessionId) {
        return deriveDisplayMessages(load(sessionId));
    }

    // ── 세션 목록 (사이드바용) ────────────────────────────────────

    public List<SessionSummaryDto> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(s -> new SessionSummaryDto(
                        s.getSessionId(),
                        s.getPreview() != null ? s.getPreview() : "이미지 분석",
                        s.getCreatedAt(),
                        s.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    // ── 표시용 메시지 변환 ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deriveDisplayMessages(List<Map<String, Object>> claudeMessages) {
        List<Map<String, Object>> display = new ArrayList<>();

        for (Map<String, Object> msg : claudeMessages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            if (!(content instanceof List)) continue;

            List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

            boolean isToolResult = blocks.stream()
                    .anyMatch(b -> "tool_result".equals(b.get("type")));
            if (isToolResult) continue;

            String text = blocks.stream()
                    .filter(b -> "text".equals(b.get("type")))
                    .map(b -> (String) b.get("text"))
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst().orElse(null);

            if (text == null) continue;

            String imageUrl = null;
            for (Map<String, Object> b : blocks) {
                if ("image".equals(b.get("type"))) {
                    Object src = b.get("source");
                    if (src instanceof Map) {
                        Map<?, ?> source = (Map<?, ?>) src;
                        String mediaType = (String) source.get("media_type");
                        String data = (String) source.get("data");
                        if (data != null && mediaType != null) {
                            imageUrl = "data:" + mediaType + ";base64," + data;
                        }
                    }
                    break;
                }
            }
            if (imageUrl == null && text.contains("[첨부 이미지:")) {
                text = text.replaceFirst("\\[첨부 이미지:.*?]\\s*", "").trim();
            }

            String displayRole = "assistant".equals(role) ? "agent" : "user";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", displayRole);
            item.put("text", text);
            if (imageUrl != null) {
                item.put("hadImage", true);
                item.put("imageUrl", imageUrl);
            }
            display.add(item);
        }

        return display;
    }

    // ── 미리보기 제목 추출 ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String derivePreview(List<Map<String, Object>> claudeMessages) {
        // 1순위: 첫 번째 사용자 텍스트
        for (Map<String, Object> msg : claudeMessages) {
            if (!"user".equals(msg.get("role"))) continue;
            Object content = msg.get("content");
            if (!(content instanceof List)) continue;
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
            if (blocks.stream().anyMatch(b -> "tool_result".equals(b.get("type")))) continue;

            String text = blocks.stream()
                    .filter(b -> "text".equals(b.get("type")))
                    .map(b -> (String) b.get("text"))
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.replaceFirst("\\[첨부 이미지:.*?]\\s*", "").trim())
                    .filter(t -> !t.isBlank())
                    .findFirst().orElse(null);

            if (text != null) return truncate(text);
        }

        // 2순위: 첫 번째 에이전트 응답 첫 줄
        for (Map<String, Object> msg : claudeMessages) {
            if (!"assistant".equals(msg.get("role"))) continue;
            Object content = msg.get("content");
            if (!(content instanceof List)) continue;
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

            String text = blocks.stream()
                    .filter(b -> "text".equals(b.get("type")))
                    .map(b -> (String) b.get("text"))
                    .filter(t -> t != null && !t.isBlank())
                    .flatMap(t -> t.lines())
                    .filter(l -> !l.isBlank())
                    .findFirst().orElse(null);

            if (text != null) return truncate(text);
        }

        return "이미지 분석";
    }

    private String truncate(String text) {
        return text.length() > 50 ? text.substring(0, 50) + "…" : text;
    }

    // ── JSON 직렬화 헬퍼 ─────────────────────────────────────────

    private String serializeContent(Object content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            log.error("content 직렬화 실패", e);
            return "[]";
        }
    }

    private Object deserializeContent(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            log.error("content 역직렬화 실패", e);
            return List.of();
        }
    }
}
