package com.example.connect.transforms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Custom Kafka Connect SMT to extract standard context headers from JSON column
 * in outbox events and add them as Kafka headers.
 * 
 * This transform expects a JSON field containing context like:
 * {
 *   "request_id": "req-123",
 *   "user_id": "user-456",
 *   "tenant_id": "tenant-789",
 *   "service_name": "bff",
 *   "transaction_type": "create-link"
 * }
 * 
 * It produces headers:
 * - X-Request-ID
 * - X-User-ID
 * - X-Tenant-ID
 * - X-Service-Name
 * - X-Transaction-Type
 */
public class StandardContextHeaderTransform<R extends ConnectRecord<R>> implements Transformation<R> {
    
    private static final Logger log = LoggerFactory.getLogger(StandardContextHeaderTransform.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configuration property names
    public static final String CONTEXT_FIELD_CONFIG = "context.field";
    public static final String REMOVE_FIELD_CONFIG = "remove.context.field";
    public static final String HEADER_PREFIX_CONFIG = "header.prefix";
    
    // Default values
    private static final String DEFAULT_CONTEXT_FIELD = "context";
    private static final String DEFAULT_HEADER_PREFIX = "X-";
    
    // Header mappings (JSON field -> Header name)
    private static final Map<String, String> HEADER_MAPPINGS = Map.of(
        "request_id", "Request-ID",
        "user_id", "User-ID",
        "tenant_id", "Tenant-ID",
        "service_name", "Service-Name",
        "transaction_type", "Transaction-Type"
    );
    
    // Configuration
    private String contextField;
    private boolean removeContextField;
    private String headerPrefix;
    
    public static final ConfigDef CONFIG_DEF = new ConfigDef()
        .define(CONTEXT_FIELD_CONFIG,
            ConfigDef.Type.STRING,
            DEFAULT_CONTEXT_FIELD,
            ConfigDef.Importance.HIGH,
            "Field name containing the JSON context")
        .define(HEADER_PREFIX_CONFIG,
            ConfigDef.Type.STRING,
            DEFAULT_HEADER_PREFIX,
            ConfigDef.Importance.MEDIUM,
            "Prefix for header names (e.g., 'X-')")
        .define(REMOVE_FIELD_CONFIG,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.LOW,
            "Whether to remove context field from the message after extracting");
    
    @Override
    public void configure(Map<String, ?> configs) {
        SimpleConfig config = new SimpleConfig(CONFIG_DEF, configs);
        this.contextField = config.getString(CONTEXT_FIELD_CONFIG);
        this.headerPrefix = config.getString(HEADER_PREFIX_CONFIG);
        this.removeContextField = config.getBoolean(REMOVE_FIELD_CONFIG);
        
        log.info("Configured StandardContextHeaderTransform with contextField={}, headerPrefix={}, removeField={}",
            contextField, headerPrefix, removeContextField);
    }
    
    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }
        
        // Extract context JSON based on value type
        String contextJson = null;
        Object newValue = record.value();
        
        if (record.value() instanceof Struct) {
            Struct struct = (Struct) record.value();
            contextJson = extractFieldAsString(struct, contextField);
            
            // Remove field if configured
            if (removeContextField && contextJson != null) {
                newValue = removeContextField(struct);
            }
        } else if (record.value() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) record.value();
            Object contextValue = map.get(contextField);
            contextJson = contextValue != null ? contextValue.toString() : null;
            
            // Remove field if configured
            if (removeContextField && contextJson != null) {
                map.remove(contextField);
            }
        }
        
        // Parse JSON and add headers if context found
        Headers headers = new ConnectHeaders();
        if (record.headers() != null) {
            record.headers().forEach(header -> 
                headers.add(header.key(), header.value(), header.schema()));
        }
        
        if (contextJson != null && !contextJson.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> contextMap = objectMapper.readValue(contextJson, Map.class);
                
                // Add each context field as a header
                for (Map.Entry<String, String> mapping : HEADER_MAPPINGS.entrySet()) {
                    String jsonField = mapping.getKey();
                    String headerName = headerPrefix + mapping.getValue();
                    
                    Object value = contextMap.get(jsonField);
                    if (value != null) {
                        headers.addString(headerName, value.toString());
                        log.debug("Added header {}: {}", headerName, value);
                    }
                }
                
                log.debug("Extracted {} context headers from JSON", contextMap.size());
                
            } catch (Exception e) {
                log.warn("Failed to parse context JSON: {}", e.getMessage());
            }
        }
        
        return record.newRecord(
            record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
            record.valueSchema(), newValue, record.timestamp(), headers
        );
    }
    
    private String extractFieldAsString(Struct struct, String fieldName) {
        try {
            Field field = struct.schema().field(fieldName);
            if (field != null) {
                Object value = struct.get(field);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            log.debug("Failed to extract field {}: {}", fieldName, e.getMessage());
        }
        return null;
    }
    
    private Struct removeContextField(Struct original) {
        // Create new struct without context field
        Struct newStruct = new Struct(original.schema());
        for (Field field : original.schema().fields()) {
            if (!field.name().equals(contextField)) {
                newStruct.put(field, original.get(field));
            }
        }
        return newStruct;
    }
    
    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }
    
    @Override
    public void close() {
        // Nothing to close
    }
}