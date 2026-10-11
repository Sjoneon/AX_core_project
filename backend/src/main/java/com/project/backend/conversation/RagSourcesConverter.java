package com.project.backend.conversation;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.project.backend.rag.RagSource;

@Converter
public class RagSourcesConverter implements AttributeConverter<List<RagSource>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<RagSource>> TYPE = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<RagSource> sources) {
        return sources == null || sources.isEmpty() ? null : MAPPER.writeValueAsString(sources);
    }

    @Override
    public List<RagSource> convertToEntityAttribute(String value) {
        return value == null ? List.of() : MAPPER.readValue(value, TYPE);
    }
}
