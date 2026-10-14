package com.project.backend.conversation;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.project.backend.rag.RagSource;
import com.project.backend.warranty.WarrantyModels;
import com.project.backend.warranty.WarrantyResultConverter;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Convert(converter = RagSourcesConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<RagSource> sources = List.of();

    @Convert(converter = WarrantyResultConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private WarrantyModels.Result warranty;

    @Convert(converter=com.project.backend.mcp.tool.ToolResultsConverter.class)
    @Column(name="tool_results",columnDefinition="LONGTEXT")
    private java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> tools=java.util.Map.of();

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    public ChatMessage(Conversation conversation, String role, String content) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public void setSources(List<RagSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public List<RagSource> getSources() {
        return sources == null ? List.of() : sources;
    }

    public void setWarranty(WarrantyModels.Result result) { this.warranty = result; }
    public WarrantyModels.Result getWarranty() { return warranty; }
    public void setTools(java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> value){this.tools=java.util.Map.copyOf(value);}
    public java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> getTools(){return tools==null?java.util.Map.of():tools;}

    public Long getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
