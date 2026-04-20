package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_message", indexes = {
        @Index(name = "idx_agent_msg_session_seq", columnList = "session_id, seq")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** "user" | "assistant" */
    @Column(nullable = false, length = 20)
    private String role;

    /** Claude API content 배열 (JSON) */
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    /** 세션 내 메시지 순서 */
    @Column(nullable = false)
    private Integer seq;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
