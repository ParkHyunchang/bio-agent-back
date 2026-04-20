package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.dto.AgentResponse;
import com.hyunchang.bioagent.dto.SessionSummaryDto;
import com.hyunchang.bioagent.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(
            @RequestParam("message") String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            // sessionId가 없으면 새 세션 생성
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
                log.info("새 대화 세션 생성: {}", sessionId);
            }

            byte[] imageBytes = null;
            String filename = null;
            String contentType = null;

            if (file != null && !file.isEmpty()) {
                imageBytes = file.getBytes();
                filename = file.getOriginalFilename();
                contentType = file.getContentType();
                log.info("에이전트 채팅: sessionId={}, file={}", sessionId, filename);
            } else {
                log.info("에이전트 채팅: sessionId={}, message={}", sessionId, message);
            }

            String response = agentService.chat(sessionId, message, imageBytes, filename, contentType);
            return ResponseEntity.ok(new AgentResponse(sessionId, response));
        } catch (Exception e) {
            log.error("에이전트 채팅 실패", e);
            return ResponseEntity.internalServerError()
                    .body(new AgentResponse(sessionId != null ? sessionId : "", "에이전트 처리 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionSummaryDto>> getSessions() {
        return ResponseEntity.ok(agentService.getSessions());
    }

    @GetMapping("/session/{sessionId}/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(agentService.getDisplayHistory(sessionId));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        agentService.clearHistory(sessionId);
        return ResponseEntity.noContent().build();
    }
}
