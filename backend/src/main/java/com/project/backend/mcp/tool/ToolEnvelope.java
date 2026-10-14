package com.project.backend.mcp.tool;
import java.util.*;
/**
 * Common tool result. source names the data files and fact IDs, confidence is HIGH/MEDIUM/LOW/UNDETERMINED,
 * caveats are fixed interpretation notices and warnings are situational. Results are candidates only.
 */
public record ToolEnvelope(String status,Map<String,Object> data,List<Map<String,Object>> evidence,
        List<String> missing_fields,List<String> warnings,Map<String,Object> error,Map<String,Object> meta,
        Map<String,Object> source,String confidence,List<String> caveats,boolean independent_action_allowed) {
    public ToolEnvelope {
        if(independent_action_allowed)throw new IllegalArgumentException("독립 실행 허용 값은 항상 false입니다.");
    }
    /** Server-policy result created without the MCP path (safety gate, agent fallback). */
    public static ToolEnvelope policy(String status,Map<String,Object> data,Map<String,Object> meta,List<String> caveats){
        return new ToolEnvelope(status,data,List.of(),List.of(),List.of(),null,meta,
                ToolDataCatalog.map("files",List.of(),"fact_ids",List.of()),"UNDETERMINED",caveats,false);
    }
}
