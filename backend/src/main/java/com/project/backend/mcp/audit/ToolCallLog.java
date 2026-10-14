package com.project.backend.mcp.audit;

import java.time.Instant;
import jakarta.persistence.*;

/**
 * Audit row of one tool call (ops/rds/sql/V005__chat_tool_calls.sql). chat_messages.tool_results keeps the full envelopes
 * for the UI; this table keeps the call order, allow-listed arguments, a short summary and the elapsed time.
 */
@Entity
@Table(name="chat_tool_calls")
public class ToolCallLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="conversation_id",nullable=false) private Long conversationId;
    @Column(name="message_id") private Long messageId;
    @Column(name="call_order",nullable=false) private int callOrder;
    @Column(name="tool_name",nullable=false,length=64) private String toolName;
    @Column(name="arguments_json",length=1000) private String arguments;
    @Column(name="injected_fields",length=200) private String injectedFields;
    @Column(name="selection_source",nullable=false,length=30) private String selectionSource;
    @Column(nullable=false,length=30) private String status;
    @Column(name="error_code",length=40) private String errorCode;
    @Column(name="result_summary",length=2000) private String resultSummary;
    @Column(name="elapsed_ms",nullable=false) private long elapsedMs;
    @Column(name="trace_id",length=36) private String traceId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ToolCallLog(){ }
    public ToolCallLog(Long conversationId,Long messageId,int callOrder,ToolCallRecord call,String arguments,String summary){
        this.conversationId=conversationId;this.messageId=messageId;this.callOrder=callOrder;this.toolName=call.name();
        this.arguments=arguments;this.injectedFields=String.join(",",call.injected());this.selectionSource=call.selection();
        this.status=call.status();this.errorCode=call.errorCode();this.resultSummary=summary;this.elapsedMs=call.elapsedMs();
        this.traceId=call.traceId();this.createdAt=Instant.now();
    }
    public Long getId(){return id;}
    public Long getConversationId(){return conversationId;}
    public Long getMessageId(){return messageId;}
    public int getCallOrder(){return callOrder;}
    public String getToolName(){return toolName;}
    public String getArguments(){return arguments;}
    public String getInjectedFields(){return injectedFields;}
    public String getSelectionSource(){return selectionSource;}
    public String getStatus(){return status;}
    public String getErrorCode(){return errorCode;}
    public String getResultSummary(){return resultSummary;}
    public long getElapsedMs(){return elapsedMs;}
    public String getTraceId(){return traceId;}
    public Instant getCreatedAt(){return createdAt;}
}
