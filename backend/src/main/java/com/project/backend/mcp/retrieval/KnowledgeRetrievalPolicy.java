package com.project.backend.mcp.retrieval;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import com.project.backend.mcp.tool.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Applicability is enforced before ranking for every topic, including safety and supplements. */
final class KnowledgeRetrievalPolicy {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> REVIEW=Set.of("source_applicability_review","source_conflict_review",
            "source_normalization_evidence","excluded_optional_reference");
    private final ToolDataCatalog catalog;
    private final Set<String> commonIds;
    private final Map<String,List<String>> displaySubjects;
    KnowledgeRetrievalPolicy(ToolDataCatalog catalog){
        this.catalog=catalog;
        var ids=new HashSet<String>();
        var displays=new HashMap<String,List<String>>();
        for(var source:catalog.knowledge){
            try {
                Map<String,Object> wrapper=JSON.readValue(text(source,"text"),new TypeReference<>(){});
                if(wrapper.get("record") instanceof Map<?,?> record&&("fault".equals(record.get("display_class"))||"T2_error_codes".equals(record.get("table")))){
                    var labels=record.get("raw_display_labels") instanceof List<?> list?list.stream().map(Object::toString).toList():List.<String>of();
                    displays.put(text(source,"id"),labels);
                }
                if(wrapper.get("record") instanceof Map<?,?> record&&"품목 공통".equals(record.get("scope"))
                        &&"verified_excerpt".equals(record.get("verification_status"))&&!Boolean.FALSE.equals(record.get("runtime_eligible"))
                        &&!strings(source,"sourceUrls").isEmpty())ids.add(text(source,"id"));
            }catch(RuntimeException plainFact){ /* Plain isolated facts have no verified common scope. */ }
        }
        commonIds=Set.copyOf(ids);
        displaySubjects=Map.copyOf(displays);
    }
    boolean common(String id){return commonIds.contains(id);}
    boolean displaySubject(String id){return displaySubjects.containsKey(id);}
    boolean exactDisplay(String id,String query){
        String q=query.replaceAll("\\s+","");
        return displaySubjects.getOrDefault(id,List.of()).stream().anyMatch(label->label.length()>2&&q.contains(label.replaceAll("\\s+","")));
    }
    String commonModel(String model){
        if(model==null||!(catalog.models.get(model) instanceof Map<?,?> entry)
                ||!Boolean.TRUE.equals(entry.get("reuse_approved_common_data_only")))return null;
        return Objects.toString(entry.get("common_data_lookup_model_code"),null);
    }
    String status(String id,String model){return catalog.overlays.get(id).status(model,commonModel(model));}
    boolean eligible(String id,String category,String model){
        var source=catalog.knowledgeById.get(id);
        if(source==null||!category.equals(text(source,"category"))||REVIEW.contains(text(source,"recordType")))return false;
        String status=status(id,model);
        return "MODEL_LISTED".equals(status)||"CONDITIONAL".equals(status)
                ||"UNDETERMINED".equals(status)&&common(id);
    }
    String primary(String id){
        var doc=catalog.structuredKnowledgeById.get(id);
        return (doc.title()+" "+String.join(" ",strings(doc.fields(),"symptoms"))).toLowerCase(Locale.ROOT);
    }
}
