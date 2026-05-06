package com.hyunchang.bioagent.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모든 컨트롤러에서 발생하는 예외를 일관된 JSON 형식으로 변환.
 * 응답 바디에는 사용자 친화적 메시지 + 추적용 requestId만 포함하고,
 * 스택 트레이스는 클라이언트로 보내지 않는다(서버 로그에만 기록).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 비즈니스 로직 측에서 명시적으로 throw한 4xx/5xx — 메시지를 그대로 사용. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex, WebRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        if (status.is5xxServerError()) {
            log.warn("[ERR-RSE] {} {} → {}", status.value(), req.getDescription(false), ex.getReason(), ex);
        } else {
            log.info("[ERR-RSE] {} {} → {}", status.value(), req.getDescription(false), ex.getReason());
        }
        return ResponseEntity.status(status).body(buildBody(status, ex.getReason()));
    }

    /** PubMed/Anthropic 등 다운스트림 4xx (rate limit 포함) — 502로 변환해 클라이언트에 노출. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> handleDownstream4xx(HttpClientErrorException ex, WebRequest req) {
        if (ex.getStatusCode().value() == 429) {
            log.warn("[ERR-429] downstream rate limit on {}", req.getDescription(false));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(buildBody(HttpStatus.TOO_MANY_REQUESTS, "외부 서비스 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."));
        }
        log.warn("[ERR-DS-4XX] {} on {}", ex.getStatusCode().value(), req.getDescription(false));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildBody(HttpStatus.BAD_GATEWAY, "외부 서비스 응답 오류가 발생했습니다."));
    }

    /** 다운스트림 5xx 또는 네트워크 장애 — 502로 변환. */
    @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class, RestClientException.class})
    public ResponseEntity<Map<String, Object>> handleDownstream5xx(Exception ex, WebRequest req) {
        log.error("[ERR-DS-5XX] {} on {}", ex.getClass().getSimpleName(), req.getDescription(false), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildBody(HttpStatus.BAD_GATEWAY, "외부 서비스에 연결할 수 없습니다."));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildBody(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex, WebRequest req) {
        log.info("[ERR-400] {} on {}", ex.getMessage(), req.getDescription(false));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildBody(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    /** 예상치 못한 예외 — 스택 트레이스는 서버 로그에만, 클라이언트에는 일반 메시지. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, WebRequest req) {
        log.error("[ERR-500] unhandled exception on {}", req.getDescription(false), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildBody(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."));
    }

    private Map<String, Object> buildBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", message != null && !message.isBlank() ? message : status.getReasonPhrase());
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (requestId != null) body.put("requestId", requestId);
        return body;
    }
}
