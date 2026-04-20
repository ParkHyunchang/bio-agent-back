package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    Optional<AgentSession> findBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
    List<AgentSession> findAllByOrderByUpdatedAtDesc();
}
