package com.project.backend.mcp.tool;

import java.util.*;
import com.project.backend.mcp.consultation.ConsultationService;

/** One read-only MCP tool. The name must match the FastMCP tool name in mcp_server/homecare_mcp/tools. */
public interface HomeCareTool {
    String name();
    default Set<String> arguments(){return Set.of("conversation_id");}
    ToolEnvelope execute(String email,ConsultationService.Snapshot snapshot,Map<String,Object> args);
}
