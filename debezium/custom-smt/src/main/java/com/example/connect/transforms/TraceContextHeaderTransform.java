package com.example.connect.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Custom Kafka Connect SMT to extract W3C trace context from outbox events
 * and add it as a properly formatted traceparent header.
 * 
 * This transform expects the following fields in the message:
 * - trace_id: 32 character hex string
 * - parent_span_id: 16 character hex string  
 * - trace_flags: 2 character hex string (optional, defaults to "01")
 * 
 * It produces a W3C traceparent header in the format:
 * 00-{trace_id}-{parent_span_id}-{trace_flags}
 */
public class TraceContextHeaderTransform<R extends ConnectRecord<R>> implements Transformation<R> {
    
    private static final Logger log = LoggerFactory.getLogger(TraceContextHeaderTransform.class);
    
    // Configuration property names
    public static final String TRACE_ID_FIELD_CONFIG = "trace.id.field";
    public static final String SPAN_ID_FIELD_CONFIG = "span.id.field";
    public static final String FLAGS_FIELD_CONFIG = "trace.flags.field";
    public static final String REMOVE_FIELDS_CONFIG = "remove.trace.fields";
    public static final String HEADER_NAME_CONFIG = "header.name";
    
    // Default values
    private static final String DEFAULT_TRACE_ID_FIELD = "trace_id";
    private static final String DEFAULT_SPAN_ID_FIELD = "parent_span_id";
    private static final String DEFAULT_FLAGS_FIELD = "trace_flags";
    private static final String DEFAULT_HEADER_NAME = "traceparent";
    private static final String DEFAULT_TRACE_FLAGS = "01";
    private static final String TRACE_VERSION = "00";
    
    // Configuration
    private String traceIdField;
    private String spanIdField;
    private String flagsField;
    private String headerName;
    private boolean removeTraceFields;
    
    public static final ConfigDef CONFIG_DEF = new ConfigDef()
        .define(TRACE_ID_FIELD_CONFIG,
            ConfigDef.Type.STRING,
            DEFAULT_TRACE_ID_FIELD,
            ConfigDef.Importance.HIGH,
            "Field name containing the trace ID")
        .define(SPAN_ID_FIELD_CONFIG,
            ConfigDef.Type.STRING,
            DEFAULT_SPAN_ID_FIELD,
            ConfigDef.Importance.HIGH,
            "Field name containing the parent span ID")
        .define(FLAGS_FIELD_CONFIG,
            ConfigDef.Type.STRING,
            DEFAULT_FLAGS_FIELD,
            ConfigDef.Importance.MEDIUM,
            "Field name containing the trace flags")
        .define(HEADER_NAME_CONFIG,
            ConfigDef.Type.STRING,
            DEFAULT_HEADER_NAME,
            ConfigDef.Importance.MEDIUM,
            "Name of the header to create")
        .define(REMOVE_FIELDS_CONFIG,
            ConfigDef.Type.BOOLEAN,
            true,
            ConfigDef.Importance.LOW,
            "Whether to remove trace fields from the message after extracting");
    
    @Override
    public void configure(Map<String, ?> configs) {
        SimpleConfig config = new SimpleConfig(CONFIG_DEF, configs);
        this.traceIdField = config.getString(TRACE_ID_FIELD_CONFIG);
        this.spanIdField = config.getString(SPAN_ID_FIELD_CONFIG);
        this.flagsField = config.getString(FLAGS_FIELD_CONFIG);
        this.headerName = config.getString(HEADER_NAME_CONFIG);
        this.removeTraceFields = config.getBoolean(REMOVE_FIELDS_CONFIG);
        
        log.info("Configured TraceContextHeaderTransform with traceIdField={}, spanIdField={}, flagsField={}, headerName={}",
            traceIdField, spanIdField, flagsField, headerName);
    }
    
    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }
        
        // Extract trace context based on value type
        String traceId = null;
        String spanId = null;
        String traceFlags = null;
        Object newValue = record.value();
        
        if (record.value() instanceof Struct) {
            Struct struct = (Struct) record.value();
            traceId = extractFieldAsString(struct, traceIdField);
            spanId = extractFieldAsString(struct, spanIdField);
            traceFlags = extractFieldAsString(struct, flagsField);
            
            // Remove fields if configured
            if (removeTraceFields && (traceId != null || spanId != null)) {
                newValue = removeTraceFields(struct);
            }
        } else if (record.value() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) record.value();
            traceId = extractFromMap(map, traceIdField);
            spanId = extractFromMap(map, spanIdField);
            traceFlags = extractFromMap(map, flagsField);
            
            // Remove fields if configured
            if (removeTraceFields && (traceId != null || spanId != null)) {
                map.remove(traceIdField);
                map.remove(spanIdField);
                map.remove(flagsField);
            }
        }
        
        // Only add header if we have both trace_id and span_id
        if (traceId != null && spanId != null) {
            // Validate and format trace context
            if (!isValidTraceId(traceId)) {
                log.warn("Invalid trace_id format: {}", traceId);
                return record.newRecord(
                    record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
                    record.valueSchema(), newValue, record.timestamp(), record.headers()
                );
            }
            
            if (!isValidSpanId(spanId)) {
                log.warn("Invalid span_id format: {}", spanId);
                return record.newRecord(
                    record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
                    record.valueSchema(), newValue, record.timestamp(), record.headers()
                );
            }
            
            // Use default flags if not provided or invalid
            if (traceFlags == null || !isValidTraceFlags(traceFlags)) {
                traceFlags = DEFAULT_TRACE_FLAGS;
            }
            
            // Build W3C traceparent header
            String traceparent = String.format("%s-%s-%s-%s",
                TRACE_VERSION, traceId.toLowerCase(), spanId.toLowerCase(), traceFlags.toLowerCase());
            
            // Add header to the record
            Headers headers = new ConnectHeaders();
            if (record.headers() != null) {
                record.headers().forEach(header -> 
                    headers.add(header.key(), header.value(), header.schema()));
            }
            headers.addString(headerName, traceparent);
            
            log.debug("Added {} header: {}", headerName, traceparent);
            
            return record.newRecord(
                record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
                record.valueSchema(), newValue, record.timestamp(), headers
            );
        }
        
        // Return record unchanged if no trace context found
        return record.newRecord(
            record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
            record.valueSchema(), newValue, record.timestamp(), record.headers()
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
    
    private String extractFromMap(Map<String, Object> map, String fieldName) {
        Object value = map.get(fieldName);
        return value != null ? value.toString() : null;
    }
    
    private Struct removeTraceFields(Struct original) {
        // Create new struct without trace fields
        Struct newStruct = new Struct(original.schema());
        for (Field field : original.schema().fields()) {
            if (!field.name().equals(traceIdField) && 
                !field.name().equals(spanIdField) && 
                !field.name().equals(flagsField)) {
                newStruct.put(field, original.get(field));
            }
        }
        return newStruct;
    }
    
    private boolean isValidTraceId(String traceId) {
        return traceId != null && traceId.matches("[0-9a-fA-F]{32}");
    }
    
    private boolean isValidSpanId(String spanId) {
        return spanId != null && spanId.matches("[0-9a-fA-F]{16}");
    }
    
    private boolean isValidTraceFlags(String flags) {
        return flags != null && flags.matches("[0-9a-fA-F]{2}");
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