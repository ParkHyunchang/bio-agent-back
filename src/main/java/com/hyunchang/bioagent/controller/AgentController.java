package com.hyunchang.bioagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.bioagent.dto.AgentResponse;
import com.hyunchang.bioagent.dto.SessionSummaryDto;
import com.hyunchang.bioagent.service.AgentService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.apache.catalina.connector.ClientAbortException;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int MAX_MESSAGE_LENGTH = 8000;
    private static final String GENERIC_ERROR_MESSAGE = "에이전트 처리 중 오류가 발생했습니다.";

    private final AgentService agentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.upload.max-image-bytes:10485760}")
    private long maxImageBytes;

    @Value("${app.upload.allowed-mime-types:image/png,image/jpeg,image/jpg,image/webp}")
    private String allowedMimeTypesRaw;

    private Set<String> allowedMimeTypes;

    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Set<String> getAllowedMimeTypes() {
        if (allowedMimeTypes == null) {
            allowedMimeTypes = new HashSet<>(Arrays.asList(allowedMimeTypesRaw.toLowerCase().split(",")));
            allowedMimeTypes.removeIf(String::isBlank);
        }
        return allowedMimeTypes;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam("message") String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        String resolvedSessionId = resolveSessionId(sessionId);
        validateMessage(message);
        validateFile(file);

        SseEmitter emitter = new SseEmitter(300_000L);

        // servlet 스레드의 MDC를 executor 스레드로 복사 (correlation ID 유지)
        final java.util.Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        executor.submit(() -> {
            if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
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

                String donePayload = objectMapper.writeValueAsString(
                        Map.of("sessionId", resolvedSessionId, "message", response != null ? response : "")
                );
                emitter.send(SseEmitter.event().name("done").data(donePayload));
                emitter.complete();
            } catch (ClientAbortException e) {
                log.info("클라이언트가 스트리밍을 중단했습니다 (sessionId={})", resolvedSessionId);
                emitter.complete();
            } catch (Exception e) {
                if (e.getCause() instanceof ClientAbortException) {
                    log.info("클라이언트가 스트리밍을 중단했습니다 (sessionId={})", resolvedSessionId);
                    emitter.complete();
                } else {
                    log.error("에이전트 스트리밍 실패 (sessionId={})", resolvedSessionId, e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(GENERIC_ERROR_MESSAGE));
                    } catch (IOException ignored) {}
                    emitter.completeWithError(e);
                }
            } finally {
                MDC.clear();
            }
        });

        return emitter;
    }

    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(
            @RequestParam("message") String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        String resolvedSessionId = resolveSessionId(sessionId);
        validateMessage(message);
        validateFile(file);

        try {
            byte[] imageBytes = null;
            String filename = null;
            String contentType = null;

            if (file != null && !file.isEmpty()) {
                imageBytes = file.getBytes();
                filename = file.getOriginalFilename();
                contentType = file.getContentType();
                log.info("에이전트 채팅: sessionId={}, file={}", resolvedSessionId, filename);
            } else {
                log.info("에이전트 채팅: sessionId={}, messageLength={}", resolvedSessionId, message.length());
            }

            String response = agentService.chat(resolvedSessionId, message, imageBytes, filename, contentType);
            return ResponseEntity.ok(new AgentResponse(resolvedSessionId, response));
        } catch (Exception e) {
            log.error("에이전트 채팅 실패 (sessionId={})", resolvedSessionId, e);
            return ResponseEntity.internalServerError()
                    .body(new AgentResponse(resolvedSessionId, GENERIC_ERROR_MESSAGE));
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionSummaryDto>> getSessions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) {
        return ResponseEntity.ok(agentService.getSessions(page, size));
    }

    @GetMapping("/session/{sessionId}/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(@PathVariable String sessionId) {
        validateSessionId(sessionId);
        return ResponseEntity.ok(agentService.getDisplayHistory(sessionId));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        validateSessionId(sessionId);
        agentService.clearHistory(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ── 검증 헬퍼 ─────────────────────────────────────────────────

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        validateSessionId(sessionId);
        return sessionId;
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || !UUID_PATTERN.matcher(sessionId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 세션 ID 형식입니다.");
        }
    }

    private void validateMessage(String message) {
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 파라미터가 필요합니다.");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "메시지가 너무 깁니다 (최대 " + MAX_MESSAGE_LENGTH + "자).");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return;
        if (file.getSize() > maxImageBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "이미지 크기가 제한을 초과했습니다 (최대 " + (maxImageBytes / (1024 * 1024)) + "MB).");
        }
        String contentType = file.getContentType();
        if (contentType == null || !getAllowedMimeTypes().contains(contentType.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "허용되지 않은 이미지 형식입니다.");
        }
    }
}
