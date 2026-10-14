package com.project.backend.mcp.tool;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.project.backend.data.DataManifest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ToolDataCatalog {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public final List<Map<String,Object>> knowledge,parts,repairCases,approvedPlans,centers;
    public final Map<String,Object> models,route;
    /** Cost table origin: basis, surcharge rule and the 20-case validation summary. */
    public final Map<String,Object> costSource;
    public final String version;
    public final Map<String,Map<String,Object>> knowledgeById;
    /** Applicability and supplement links parsed once from each record's embedded B30 overlay. */
    public final Map<String,KnowledgeOverlay> overlays;
    public final Map<String,KnowledgeDocument> structuredKnowledgeById;
    // Every file is hash-verified by the manifest before parsing; any mismatch stops startup.
    @Autowired
    public ToolDataCatalog(DataManifest manifest) {
        knowledge=readList(manifest,"knowledge.json");parts=readList(manifest,"parts.json");repairCases=readList(manifest,"repair-cases.json");
        approvedPlans=readList(manifest,"approved-plans.json");centers=readList(manifest,"centers.json");
        models=readMap(manifest,"models.json");route=readMap(manifest,"route.json");version=manifest.version();costSource=manifest.section("costSource");
        if(knowledge.size()!=manifest.count("knowledgeCount")||models.size()!=manifest.count("exactSalesCodes")||parts.size()!=manifest.count("costGroups"))
            throw new IllegalStateException("자료 건수가 매니페스트와 다릅니다.");
        knowledgeById=index(knowledge);overlays=overlays(knowledge,models);
        var structured=readList(manifest,"knowledge-structured.json");
        if(!Objects.equals(manifest.section("knowledgeProjection").get("sourceSha256"),manifest.section("files").get("homecare/knowledge.json")))
            throw new IllegalStateException("구조화 자료의 원본 해시가 매니페스트와 다릅니다.");
        if(structured.size()!=manifest.count("structuredKnowledgeCount"))throw new IllegalStateException("구조화 자료 건수가 매니페스트와 다릅니다.");
        structuredKnowledgeById=structured.stream().collect(Collectors.toUnmodifiableMap(d->text(d,"id"),KnowledgeDocument::new));
        if(!structuredKnowledgeById.keySet().equals(knowledgeById.keySet()))throw new IllegalStateException("원본과 구조화 자료 ID가 다릅니다.");
        for(var doc:structured){
            if(!(doc.get("schemaVersion") instanceof Number schema)||schema.intValue()!=1)
                throw new IllegalStateException("지원하지 않는 구조화 자료 스키마입니다.");
            var original=knowledgeById.get(text(doc,"id"));
            for(var field:List.of("category","topic","title","recordType","errorCodes","baseDate"))
                if(!Objects.equals(doc.get(field),original.get(field)))throw new IllegalStateException("원본과 구조화 자료 필드가 다릅니다: "+field);
            if(Boolean.TRUE.equals(doc.get("isActionApproved")))throw new IllegalStateException("검색 자료는 조치 실행 승인이 아닙니다.");
        }
    }
    public ToolDataCatalog(List<Map<String,Object>> knowledge,List<Map<String,Object>> parts,
            List<Map<String,Object>> cases,List<Map<String,Object>> plans,List<Map<String,Object>> centers,
            Map<String,Object> models,Map<String,Object> route) {
        this.knowledge=List.copyOf(knowledge);this.parts=List.copyOf(parts);this.repairCases=List.copyOf(cases);
        this.approvedPlans=List.copyOf(plans);this.centers=List.copyOf(centers);this.models=Map.copyOf(models);this.route=Map.copyOf(route);
        this.version="TEST_DATA";this.costSource=Map.of();knowledgeById=index(this.knowledge);overlays=overlays(this.knowledge,this.models);
        structuredKnowledgeById=this.knowledge.stream().collect(Collectors.toUnmodifiableMap(d->text(d,"id"),KnowledgeDocument::plain));
    }
    private static List<Map<String,Object>> readList(DataManifest manifest,String name){
        return JSON.readValue(manifest.read("homecare/"+name),new TypeReference<>(){});
    }
    private static Map<String,Object> readMap(DataManifest manifest,String name){
        return JSON.readValue(manifest.read("homecare/"+name),new TypeReference<>(){});
    }
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> knowledge){
        return knowledge.stream().collect(Collectors.toUnmodifiableMap(d->text(d,"id"),Function.identity()));
    }
    private static Map<String,KnowledgeOverlay> overlays(List<Map<String,Object>> knowledge,Map<String,Object> models){
        return knowledge.stream().collect(Collectors.toUnmodifiableMap(d->text(d,"id"),d->KnowledgeOverlay.of(d,models.keySet())));
    }
    public static String text(Map<String,Object> data,String key){return Objects.toString(data.get(key),"");}
    public static List<String> strings(Map<String,Object> data,String key){
        Object value=data.get(key);return value instanceof List<?> list?list.stream().map(Object::toString).toList():List.of();
    }
    public static Map<String,Object> map(Object... pairs){
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;
    }
}
