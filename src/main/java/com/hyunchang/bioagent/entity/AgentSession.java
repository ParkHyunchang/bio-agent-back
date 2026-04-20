package com.hyunchang.bioagent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 36)
    private String sessionId;

    /** Claude API로 전송하는 전체 메시지 히스토리 (JSON) */
    @Column(name = "claude_messages", columnDefinition = "MEDIUMTEXT")
    private String claudeMessages;

    /** 프론트엔드 표시용 대화 메시지 (JSON) */
    @Column(name = "display_messages", columnDefinition = "MEDIUMTEXT")
    private String displayMessages;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
