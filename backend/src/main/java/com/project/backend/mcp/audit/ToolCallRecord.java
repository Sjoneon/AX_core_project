package com.project.backend.mcp.audit;

import static java.util.Objects.requireNonNull;

import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.mcp.tool.ToolEnvelope;
import com.project.backend.mcp.tool.ToolSupport;

/**
 * One tool call as stored in chat_tool_calls. Arguments are allow-listed and the result is a short summary; customer
 * text, email, tokens and Authorization headers are never part of a record.
 */
public record ToolCallRecord(String name,Map<String,Object> arguments,List<String> injected,String selection,String status,
        String errorCode,Map<String,Object> summary,long elapsedMs,String traceId) {
    private static final Map<String,Set<String>> ALLOWED=Map.of(
            "lookup_model",Set.of("model_code"),
            "search_knowledge",Set.of("topic","top_k","model_code"),
            "estimate_repair_cost",Set.of("candidate_ids","confidence","time_slot","month","product_category"),
            "prepare_service_handoff",Set.of("reason"));
    private static final Pattern SAFE_TEXT=Pattern.compile("[\\p{L}\\p{N}._/·-]{1,40}");

    public static ToolCallRecord success(String name,Map<String,Object> args,String selection,ToolEnvelope result,long elapsedMs){
        String trace=result.meta()==null?null:Objects.toString(result.meta().get("trace_id"),null);
        return new ToolCallRecord(name,arguments(name,args),List.of("conversation_id"),selection,result.status(),null,summary(name,result),elapsedMs,trace);
    }
    public static ToolCallRecord failure(String name,Map<String,Object> args,String selection,String errorCode,long elapsedMs){
        return new ToolCallRecord(name,arguments(name,args),List.of("conversation_id"),selection,"ERROR",errorCode,Map.of(),elapsedMs,null);
    }
    /** Allow-listed argument values only. The search query is reduced to its length. */
    static Map<String,Object> arguments(String name,Map<String,Object> args){
        var kept=new TreeMap<String,Object>();
        for(String key:ALLOWED.getOrDefault(name,Set.of()))if(args.get(key)!=null)kept.put(key,safe(key.equals("model_code")?ToolSupport.model(args.get(key).toString()):args.get(key)));
        if(args.get("query") instanceof String query)kept.put("query_length",query.length());
        return kept;
    }
    // A model may copy customer text into any argument; free text is replaced instead of stored.
    private static Object safe(Object value){
        if(value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof List<?> list)return list.stream().limit(5).map(ToolCallRecord::safe).toList();
        return value instanceof String text&&SAFE_TEXT.matcher(text).matches()?text:"INVALID_VALUE";
    }
    static Map<String,Object> summary(String name,ToolEnvelope result){
        var data=result.data()==null?Map.<String,Object>of():result.data();
        var value=new LinkedHashMap<String,Object>();value.put("confidence",result.confidence());
        switch(name){
            case "lookup_model"->{value.put("match_status",data.get("match_status"));value.put("normalized_code",data.get("normalized_code"));value.put("representative_model_code",data.get("representative_model_code"));}
            case "search_knowledge"->{value.put("documents",size(data.get("documents")));value.put("safety_notices",size(data.get("safety_notices")));value.put("model_filter_status",data.get("model_filter_status"));}
            case "check_warranty"->value.put("product_period_status",data.get("productWarranty") instanceof Map<?,?> p?p.get("periodStatus"):null);
            case "get_repair_candidates"->value.put("allowed_candidates",size(data.get("allowed_candidates")));
            case "estimate_repair_cost"->value.put("alternatives",data.get("alternatives") instanceof List<?> list?list.stream().limit(2).map(raw->{
                var a=requireNonNull((Map<?,?>)raw);var range=a.get("expected_range") instanceof Map<?,?> r?r:Map.of();
                return Map.of("candidate_id",Objects.toString(a.get("candidate_id"),""),"median",Objects.toString(range.get("median"),""),
                        "min",Objects.toString(range.get("min"),""),"max",Objects.toString(range.get("max"),""),"confidence",Objects.toString(a.get("confidence"),""));}).toList():List.of());
            case "find_service_centers"->{value.put("fallback_level",data.get("fallback_level"));value.put("centers",size(data.get("centers")));}
            case "prepare_service_handoff"->value.put("delivery_status",data.get("delivery_status"));
            case "get_product_context"->value.put("product_confirmed",data.get("product_confirmed"));
            case "get_diagnostic_plan"->{value.put("skip_reason",data.get("skip_reason"));value.put("steps",size(data.get("steps")));}
            default->{ }
        }
        return value;
    }
    private static int size(Object value){return value instanceof List<?> list?list.size():0;}
}
