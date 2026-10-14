package com.project.backend.mcp.consultation;

import java.util.*;

/** Confirmations belong to one reviewed plan revision and registered product, never to a model family. */
public record SelfCareState(String scope,Map<String,String> conditions,Map<String,String> results) {
    public SelfCareState {
        conditions=conditions==null?Map.of():Map.copyOf(conditions);
        results=results==null?Map.of():Map.copyOf(results);
    }
    public static SelfCareState start(){return new SelfCareState(null,Map.of(),Map.of());}
    public SelfCareState answer(String expectedScope,String id,String answer){
        var values=new LinkedHashMap<String,String>(Objects.equals(scope,expectedScope)?conditions:Map.of());
        values.put(id,answer);
        return new SelfCareState(expectedScope,values,Objects.equals(scope,expectedScope)?results:Map.of());
    }
    public SelfCareState result(String code,boolean solved){
        var values=new LinkedHashMap<>(results);values.put(code,solved?"RESOLVED":"NOT_RESOLVED");
        return new SelfCareState(scope,conditions,values);
    }
}
