package com.project.backend.mcp.tool;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.retrieval.KnowledgeRetriever;

/** Source-backed possibilities and customer-requested part prices are different inputs. */
public final class RepairCandidatePolicy {
    public static final int MINIMUM_CASES=3;
    private RepairCandidatePolicy(){ }
    private static String compact(String value){return value.replaceAll("[\\s·()]+","").toUpperCase(Locale.ROOT);}
    public static boolean deniedRequest(String input){return input.matches("(?s).*(아니|아닌|아닙|말고|않|무시|지시|선택하|선택해|라고 써).*" );}
    public static String modelFromInput(ToolDataCatalog catalog,ConsultationService.Snapshot s){
        var matches=catalog.models.entrySet().stream().filter(e->e.getValue() instanceof Map<?,?> info&&s.category().name().equals(info.get("category")))
                .map(Map.Entry::getKey).filter(code->Pattern.compile("(?i)(?<![A-Z0-9./-])"+Pattern.quote(code)+"(?![A-Z0-9./-])").matcher(s.input()).find()).toList();
        return matches.size()==1?matches.getFirst():null;
    }
    public static List<String> requestedParts(ToolDataCatalog catalog,ConsultationService.Snapshot s,String input){
        // A denied or quoted diagnosis is not a part-price request.
        if(deniedRequest(input))return List.of();
        String message=compact(input);
        return catalog.parts.stream().filter(p->s.category().name().equals(p.get("category")))
                .filter(p->message.contains(compact(text(p,"name"))))
                .map(p->text(p,"id")).distinct().limit(2).toList();
    }
    public record Selection(List<Map<String,Object>> causes,List<Map<String,Object>> supported,List<Map<String,Object>> evidence){ }
    public static Selection select(ToolDataCatalog catalog,KnowledgeRetriever retriever,ConsultationService.Snapshot s,String model){
        var causes=new ArrayList<Map<String,Object>>();var supported=new LinkedHashMap<String,Map<String,Object>>();var proof=new LinkedHashMap<String,Map<String,Object>>();
        var requested=new LinkedHashSet<>(requestedParts(catalog,s,s.input()));
        String saved=s.state().dialogue().answers().get("REPAIR_PART_REQUEST");
        if(saved!=null&&!deniedRequest(s.input()))requested.addAll(List.of(saved.split(",")));
        for(var part:catalog.parts)if(s.category().name().equals(part.get("category"))&&requested.contains(text(part,"id")))
            supported.put(text(part,"id"),candidate(part,"USER_PART_REQUEST",List.of("COST_V3"),"고객이 이 부품 그룹의 비용을 문의했습니다. 실제 고장 부품을 확인한 결과는 아닙니다."));
        var found=retriever.retrieve(s.state().searchQuery(s.input()),s.category().name(),model,"MANUAL",s.state().errorCode(),3);
        var seen=new HashSet<String>();
        for(var hit:found.documents()){
            var doc=catalog.structuredKnowledgeById.get(hit.id());
            for(String raw:strings(doc.fields(),"causes"))for(String cause:raw.split("[;；]")){
                cause=cause.strip();if(cause.isBlank()||!seen.add(cause)||causes.size()>=2||cause.matches("(?is).*(지시|무시|system:|assistant:|ignore|instruction|[0-9]+\\s*원).*"))continue;
                // Conditions and causes only; no source actions or arbitrary tool content.
                var row=map("description",cause,"evidence_id",hit.id(),"applicability_status",hit.applicability(),"confirmed",false,
                        "source_urls",strings(catalog.knowledgeById.get(hit.id()),"sourceUrls"));
                causes.add(row);
                proof.put(hit.id(),ToolSupport.evidence(hit.id(),"OFFICIAL_MANUAL",strings(catalog.knowledgeById.get(hit.id()),"sourceUrls").stream().findFirst().orElse(""),text(catalog.knowledgeById.get(hit.id()),"baseDate")));
                if(!"MODEL_LISTED".equals(hit.applicability())||cause.matches("(?s).*(아니|않|정상|없습니다).*"))continue;
                String description=compact(cause);
                for(var part:catalog.parts)if(s.category().name().equals(part.get("category"))&&description.contains(compact(text(part,"name"))))
                    supported.putIfAbsent(text(part,"id"),candidate(part,"MANUFACTURER_CAUSE",List.of(hit.id()),cause));
            }
        }
        for(var part:catalog.parts)if(s.category().name().equals(part.get("category"))&&s.state().errorCode()!=null&&strings(part,"targetCodes").contains(s.state().errorCode()))
            supported.putIfAbsent(text(part,"id"),candidate(part,"MAPPING",List.of("COST_V3"),"검토된 오류·부품 매핑의 후보이며 원인 확정이 아닙니다."));
        return new Selection(List.copyOf(causes),supported.values().stream().limit(2).toList(),List.copyOf(proof.values()));
    }
    private static Map<String,Object> candidate(Map<String,Object> p,String basis,List<String> evidence,String description){
        return map("id",p.get("id"),"name",p.get("name"),"basis",basis,"evidence_ids",evidence,"description",description,"selection_confidence",0.8,
                "calibration_cases",p.getOrDefault("calibrationCases",0),"minimum_case_count",MINIMUM_CASES);
    }
    public static Map<String,Object> costArguments(ToolEnvelope candidates){
        var data=candidates.data();var values=data.get("supported_candidates") instanceof List<?> list?list:List.of();
        var ids=values.stream().filter(v->v instanceof Map<?,?>).map(v->Objects.toString(((Map<?,?>)v).get("id"))).limit(2).toList();
        return map("candidate_set_id",data.get("candidate_set_id"),"candidate_ids",ids,"confidence",ids.isEmpty()?0:0.8);
    }
}
