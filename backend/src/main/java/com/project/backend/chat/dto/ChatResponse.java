package com.project.backend.chat.dto;

import java.util.List;

import com.project.backend.rag.RagSource;
import com.project.backend.warranty.WarrantyModels;

public record ChatResponse(String reply, Long conversationId, List<RagSource> sources, WarrantyModels.Result warranty,
        java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> tools) {
    public ChatResponse(String reply,Long id,List<RagSource> sources,WarrantyModels.Result warranty){this(reply,id,sources,warranty,java.util.Map.of());}
}
