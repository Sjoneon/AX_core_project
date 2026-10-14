package com.project.backend.mcp.tool;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;

/** Search and customer projections. The B30 source wrapper is retained only in the internal catalog. */
public record KnowledgeDocument(Map<String,Object> fields) {
    public String searchText(){return text(fields,"searchText");}
    public String displayText(){return text(fields,"displayText");}
    public List<String> preconditions(){return strings(fields,"preconditions");}
    public String title(){return text(fields,"displayTitle");}
    public Map<String,Object> view(){
        return map("content",displayText(),"symptoms",fields.get("symptoms"),"error_codes",fields.get("errorCodes"),
                "causes",fields.get("causes"),"action_descriptions",fields.get("actionDescriptions"),
                "branches",fields.get("branches"),"preconditions",preconditions(),"warnings",fields.get("warnings"),
                "official_sources",fields.get("sources"),"relationships",fields.get("relationships"),
                "requirements",requirements(),"reference_details",fields.getOrDefault("referenceDetails",Map.of()),
                "model_applicability",fields.getOrDefault("modelApplicability",Map.of()),
                "is_action_approved",false);
    }
    private Map<String,Object> requirements(){
        var result=new LinkedHashMap<String,Object>();
        if(fields.get("constraints") instanceof Map<?,?> groups)groups.forEach((group,values)->{
            var selected=new LinkedHashMap<String,Object>();
            if(values instanceof Map<?,?> entries)entries.forEach((key,value)->{
                String name=key.toString();
                if(name.startsWith("requires_")||name.startsWith("not_")||name.contains("power_state")
                        ||name.equals("feature_branch_gates")||name.equals("incomplete_source_steps_for_standalone_execution")
                        ||name.equals("standalone_execution_allowed"))selected.put(name,value);
            });
            if(!selected.isEmpty())result.put(group.toString(),selected);
        });
        return result;
    }
    /** Plain facts used by tests/isolated callers remain supported; embedded wrappers require the generated projection. */
    public static KnowledgeDocument plain(Map<String,Object> source){
        String body=text(source,"text");
        if(body.stripLeading().startsWith("{"))throw new IllegalStateException("구조화되지 않은 원본 JSON은 검색·표시에 사용할 수 없습니다.");
        return new KnowledgeDocument(map("id",source.get("id"),"displayTitle",source.get("title"),"searchText",body,"displayText",body,
                "symptoms",List.of(),"errorCodes",strings(source,"errorCodes"),"causes",List.of(),
                "actionDescriptions",List.of(),"branches",Map.of(),"preconditions",strings(source,"conditions"),
                "warnings",List.of(),"sources",strings(source,"sourceUrls").stream().map(url->map("url",url,"role","PRIMARY","locator",Map.of())).toList(),
                "modelApplicability",map("original_model_codes",strings(source,"modelCodes")),
                "relationships",List.of()));
    }
}
