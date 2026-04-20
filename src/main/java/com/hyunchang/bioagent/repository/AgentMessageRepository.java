package com.hyunchang.bioagent.repository;

import com.hyunchang.bioagent.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {
    List<AgentMessage> findBySessionIdOrderBySeqAsc(String sessionId);
    int countBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
}
