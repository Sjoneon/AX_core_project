package com.project.backend.mcp.tool;
import java.util.Map;
import jakarta.persistence.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
@Converter
public class ToolResultsConverter implements AttributeConverter<Map<String,ToolEnvelope>,String> {
    // Rows saved before contract 3.0.0 lack independent_action_allowed; a missing value reads as false.
    private static final JsonMapper JSON=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    public String convertToDatabaseColumn(Map<String,ToolEnvelope> data){return data==null||data.isEmpty()?null:JSON.writeValueAsString(data);}
    public Map<String,ToolEnvelope> convertToEntityAttribute(String data){return data==null?Map.of():JSON.readValue(data,new TypeReference<>(){});}
}
