package com.hyunchang.bioagent.controller;

import com.hyunchang.bioagent.dto.AgentResponse;
import com.hyunchang.bioagent.dto.SessionSummaryDto;
import com.hyunchang.bioagent.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.apache.catalina.connector.ClientAbortException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam("message") String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        String resolvedSessionId = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString() : sessionId;

        SseEmitter emitter = new SseEmitter(300_000L);

        executor.submit(() -> {
            try {
                byte[] imageBytes = (file != null && !file.isEmpty()) ? file.getBytes() : null;
                String filename   = (file != null) ? file.getOriginalFilename() : null;
                String ct         = (file != null) ? file.getContentType() : null;

                String response = agentService.chat(
                        resolvedSessionId, message, imageBytes, filename, ct,
                        label -> {
                            try {
                                emitter.send(SseEmitter.event().name("progress").data(label));
                            } catch (IOException ignored) {}
                        }
                );

                emitter.send(SseEmitter.event().name("done")
                        .data("{\"sessionId\":\"" + resolvedSessionId + "\",\"message\":"
                                + escapeJson(response) + "}"));
                emitter.complete();
            } catch (ClientAbortException e) {
                log.info("클라이언트가 스트리밍을 중단했습니다 (sessionId={})", resolvedSessionId);
                emitter.complete();
            } catch (Exception e) {
                if (e.getCause() instanceof ClientAbortException) {
                    log.info("클라이언트가 스트리밍을 중단했습니다 (sessionId={})", resolvedSessionId);
                    emitter.complete();
                } else {
                    log.error("에이전트 스트리밍 실패", e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(Objects.requireNonNullElse(e.getMessage(), "알 수 없는 오류")));
                    } catch (IOException ignored) {}
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    private String escapeJson(String text) {
        if (text == null) return "\"\"";
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

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
