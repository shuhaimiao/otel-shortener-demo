package com.example.urlapi.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA converter for storing JSON payloads in PostgreSQL JSONB columns.
 */
@Converter
public class JsonPayloadConverter implements AttributeConverter<Object, String> {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonPayloadConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String convertToDatabaseColumn(Object attribute) {
        if (attribute == null) {
            return null;
        }
        
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            logger.error("Error converting object to JSON: {}", attribute, e);
            throw new IllegalArgumentException("Unable to convert object to JSON", e);
        }
    }
    
    @Override
    public Object convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        
        try {
            // Return as generic Object - the actual type will be determined by usage
            return objectMapper.readValue(dbData, Object.class);
        } catch (JsonProcessingException e) {
            logger.error("Error converting JSON to object: {}", dbData, e);
            throw new IllegalArgumentException("Unable to convert JSON to object", e);
        }
    }
}