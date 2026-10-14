package com.project.backend.mcp.tool;

import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Model applicability and supplement links of one B30 knowledge record, read from its embedded overlay.
 * effective_model_lists replaces the original model list. Every status is a candidate, never an approval.
 */
public record KnowledgeOverlay(String basis,Set<String> approved,Set<String> conditional,Set<String> excluded,
        List<String> supplementIds,boolean qaReview,boolean applicabilityUpdate,boolean partCompatibility) {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static final String UNDETERMINED="UNDETERMINED";

    public static KnowledgeOverlay of(Map<String,Object> doc,Set<String> salesCodes){
        Map<String,Object> raw=Map.of();
        try{if(doc.get("text") instanceof String text&&text.startsWith("{"))raw=JSON.readValue(text,new TypeReference<Map<String,Object>>(){});}
        catch(RuntimeException notOverlay){ }
        var supplements=new ArrayList<String>();
        if(raw.get("procedure_supplements") instanceof List<?> links)for(Object link:links)if(link instanceof Map<?,?> s){
            if(s.get("preferred_fact_id") instanceof String id)supplements.add(id);
            if(s.get("new_procedure_fact_ids") instanceof List<?> ids)ids.forEach(id->supplements.add(id.toString()));
        }
        boolean qa=present(raw.get("current_QA_review")),update=present(raw.get("latest_applicability_update"));
        boolean parts=Objects.toString(doc.get("recordType"),"").contains("part_compatibility");
        if(raw.get("effective_model_lists") instanceof Map<?,?> lists&&!lists.isEmpty()){
            Set<String> approved=codes(lists.get("approved_model_codes")),conditional=codes(lists.get("conditional_model_codes"));
            return new KnowledgeOverlay(approved.isEmpty()&&conditional.isEmpty()?UNDETERMINED:"EFFECTIVE_MODEL_LISTS",
                    approved,conditional,codes(lists.get("excluded_model_codes")),List.copyOf(supplements),qa,update,parts);
        }
        // Original lists may hold a category name or "품목 공통"; only exact sales codes count as a model list.
        var listed=new HashSet<>(codes(doc.get("modelCodes")));listed.retainAll(salesCodes);
        return new KnowledgeOverlay(listed.isEmpty()?UNDETERMINED:"ORIGINAL_MODEL_LIST",Set.copyOf(listed),Set.of(),Set.of(),
                List.copyOf(supplements),qa,update,parts);
    }

    /**
     * MODEL_LISTED, CONDITIONAL or UNDETERMINED may be shown as candidates. EXCLUDED and NOT_LISTED must not be shown
     * as guidance for this model; MODEL_UNCONFIRMED means the customer's model is not known yet.
     */
    public String status(String model,String commonDataModel){
        if(model==null)return UNDETERMINED.equals(basis)?UNDETERMINED:"MODEL_UNCONFIRMED";
        var codes=new HashSet<String>();codes.add(model);
        // A color-family representative may share common data, but never part compatibility.
        if(commonDataModel!=null&&!partCompatibility)codes.add(commonDataModel);
        if(codes.stream().anyMatch(excluded::contains))return "EXCLUDED";
        if(UNDETERMINED.equals(basis))return UNDETERMINED;
        if(codes.stream().anyMatch(approved::contains))return "MODEL_LISTED";
        if(codes.stream().anyMatch(conditional::contains))return "CONDITIONAL";
        return "NOT_LISTED";
    }

    private static Set<String> codes(Object value){
        return value instanceof List<?> list?Set.copyOf(list.stream().map(Object::toString).toList()):Set.of();
    }
    private static boolean present(Object value){
        return value instanceof Map<?,?> map?!map.isEmpty():value instanceof List<?> list?!list.isEmpty():value!=null;
    }
}
