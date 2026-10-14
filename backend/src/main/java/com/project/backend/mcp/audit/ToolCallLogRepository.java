package com.project.backend.mcp.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog,Long> {
    List<ToolCallLog> findByConversationIdOrderByIdAsc(Long conversationId);
    @Modifying @Query("delete from ToolCallLog t where t.conversationId = :conversationId")
    int deleteByConversation(@Param("conversationId") Long conversationId);
    @Modifying @Query("delete from ToolCallLog t where t.conversationId in (select c.id from Conversation c where c.user.id = :userId)")
    int deleteByUser(@Param("userId") Long userId);
    @Modifying @Query("delete from ToolCallLog t where t.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
