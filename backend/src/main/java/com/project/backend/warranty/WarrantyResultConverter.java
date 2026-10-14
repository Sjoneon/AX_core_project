package com.project.backend.warranty;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

@Converter
public class WarrantyResultConverter implements AttributeConverter<WarrantyModels.Result, String> {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public String convertToDatabaseColumn(WarrantyModels.Result result) {
        return result == null ? null : MAPPER.writeValueAsString(result);
    }

    public WarrantyModels.Result convertToEntityAttribute(String text) {
        return text == null || text.isBlank() ? null : MAPPER.readValue(text, WarrantyModels.Result.class);
    }
}
