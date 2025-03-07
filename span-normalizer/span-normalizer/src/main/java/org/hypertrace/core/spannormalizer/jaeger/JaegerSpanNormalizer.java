package org.hypertrace.core.spannormalizer.jaeger;

import static org.hypertrace.core.datamodel.shared.AvroBuilderCache.fastNewBuilder;

import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.util.Timestamps;
import com.typesafe.config.Config;
import io.jaegertracing.api_v2.JaegerSpanInternalModel;
import io.jaegertracing.api_v2.JaegerSpanInternalModel.KeyValue;
import io.jaegertracing.api_v2.JaegerSpanInternalModel.Span;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.avro.Schema;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.commons.lang3.StringUtils;
import org.hypertrace.core.datamodel.AttributeValue;
import org.hypertrace.core.datamodel.Attributes;
import org.hypertrace.core.datamodel.Event;
import org.hypertrace.core.datamodel.EventRef;
import org.hypertrace.core.datamodel.EventRefType;
import org.hypertrace.core.datamodel.MetricValue;
import org.hypertrace.core.datamodel.Metrics;
import org.hypertrace.core.datamodel.RawSpan;
import org.hypertrace.core.datamodel.RawSpan.Builder;
import org.hypertrace.core.datamodel.eventfields.jaeger.JaegerFields;
import org.hypertrace.core.datamodel.shared.trace.AttributeValueCreator;
import org.hypertrace.core.serviceframework.metrics.PlatformMetricsRegistry;
import org.hypertrace.core.span.constants.RawSpanConstants;
import org.hypertrace.core.span.constants.v1.JaegerAttribute;
import org.hypertrace.core.spannormalizer.constants.SpanNormalizerConstants;
import org.hypertrace.core.spannormalizer.redaction.PIIPCIField;
import org.hypertrace.core.spannormalizer.util.JaegerHTTagsConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JaegerSpanNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(JaegerSpanNormalizer.class);

  /** Service name can be sent against this key as well */
  public static final String OLD_JAEGER_SERVICENAME_KEY = "jaeger.servicename";

  private static final String SPAN_NORMALIZATION_TIME_METRIC = "span.normalization.time";

  private static JaegerSpanNormalizer INSTANCE;
  private final ConcurrentMap<String, Timer> tenantToSpanNormalizationTimer =
      new ConcurrentHashMap<>();

  private final JaegerResourceNormalizer resourceNormalizer = new JaegerResourceNormalizer();
  private final TenantIdHandler tenantIdHandler;
  private final List<PIIPCIField> PIIPCIFields = new ArrayList<>();

  public static JaegerSpanNormalizer get(Config config) {
    if (INSTANCE == null) {
      synchronized (JaegerSpanNormalizer.class) {
        if (INSTANCE == null) {
          INSTANCE = new JaegerSpanNormalizer(config);
        }
      }
    }
    return INSTANCE;
  }

  public JaegerSpanNormalizer(Config config) {
    try {
      if (config.hasPath(SpanNormalizerConstants.SPAN_REDACTION_CONFIG_KEY)) {
        config.getConfigList(SpanNormalizerConstants.PII_PCI_CONFIG_KEY).stream()
            .map(
                conf ->
                    new PIIPCIField(
                        conf.getString("name"),
                        conf.getString("regexString"),
                        conf.getStringList("keys"),
                        conf.getString("type")))
            .forEach(PIIPCIFields::add);
      }
    } catch (Exception e) {
      LOG.error("An exception occurred while loading redaction configs: ", e);
    }
    this.tenantIdHandler = new TenantIdHandler(config);
  }

  public Timer getSpanNormalizationTimer(String tenantId) {
    return tenantToSpanNormalizationTimer.get(tenantId);
  }

  @Nullable
  public RawSpan convert(String tenantId, Span jaegerSpan) throws Exception {
    Map<String, KeyValue> tags =
        jaegerSpan.getTagsList().stream()
            .collect(Collectors.toMap(t -> t.getKey().toLowerCase(), t -> t, (v1, v2) -> v2));

    // Record the time taken for converting the span, along with the tenant id tag.
    return tenantToSpanNormalizationTimer
        .computeIfAbsent(
            tenantId,
            tenant ->
                PlatformMetricsRegistry.registerTimer(
                    SPAN_NORMALIZATION_TIME_METRIC, Map.of("tenantId", tenant)))
        .recordCallable(getRawSpanNormalizerCallable(jaegerSpan, tags, tenantId));
  }

  @Nonnull
  private Callable<RawSpan> getRawSpanNormalizerCallable(
      Span jaegerSpan, Map<String, KeyValue> spanTags, String tenantId) {
    return () -> {
      Builder rawSpanBuilder = fastNewBuilder(RawSpan.Builder.class);
      rawSpanBuilder.setCustomerId(tenantId);
      rawSpanBuilder.setTraceId(jaegerSpan.getTraceId().asReadOnlyByteBuffer());
      // Build Event
      Event event =
          buildEvent(
              tenantId,
              jaegerSpan,
              spanTags,
              tenantIdHandler.getTenantIdProvider().getTenantIdTagKey());
      rawSpanBuilder.setEvent(event);
      rawSpanBuilder.setReceivedTimeMillis(System.currentTimeMillis());
      resourceNormalizer
          .normalize(jaegerSpan, tenantIdHandler.getTenantIdProvider().getTenantIdTagKey())
          .ifPresent(rawSpanBuilder::setResource);

      if (!PIIPCIFields.isEmpty()) {
        redactSpanAttributes(rawSpanBuilder);
      }

      // build raw span
      RawSpan rawSpan = rawSpanBuilder.build();
      if (LOG.isDebugEnabled()) {
        logSpanConversion(jaegerSpan, rawSpan);
      }

      return rawSpan;
    };
  }

  // redact PII tags, tag comparisons are case-insensitive (Resource tags are skipped)
  private void redactSpanAttributes(Builder rawSpanBuilder) {
    try {
      var attributeMap = rawSpanBuilder.getEvent().getAttributes().getAttributeMap();

      int piiFieldsCount = 0;
      int pciFieldsCount = 0;

      Set<String> tagKeys = attributeMap.keySet();
      for (PIIPCIField piiPciField : PIIPCIFields) {
        Matcher matcher = null;
        if (piiPciField.getRegexInfo().isPresent()) {
          matcher = piiPciField.getRegexInfo().get().getRegexPattern().matcher("");
        }
        PIIPCIField.PIIPCIFieldType piiPciFieldType = piiPciField.getPiiPciFieldType();
        for (String tagKey : tagKeys) {
          /* A Simple regex like PAN no. can have false positives.
             Hence, we only redact tag val when tagKey is present in possible list of regexTagKeySet.
             If regexTagKeySet is empty, we redact the tag val if it matches against regex pattern.
          */
          if (skipRedactionForTagKey(tagKey, piiPciField.getTagKeySet())) {
            continue;
          }

          boolean containsSensitiveData = false;
          if (piiPciField.getRegexInfo().isPresent()) {
            assert matcher != null;
            matcher.reset(attributeMap.get(tagKey).getValue());
            if (matcher.find()) {
              containsSensitiveData = true;
            }
          } else {
            // this condition implies that match type of PII/PCI field is KEY based.
            containsSensitiveData = true;
          }

          if (containsSensitiveData) {
            if (piiPciFieldType == PIIPCIField.PIIPCIFieldType.PII) {
              piiFieldsCount += 1;
            } else {
              pciFieldsCount += 1;
            }
            attributeMap.put(tagKey, piiPciField.getReplacementValue());
          }
        }
      }
      // if the trace contains PII field, add a field to indicate this. We can later slice-and-dice
      // based on this tag
      if (piiFieldsCount > 0) {
        attributeMap.put(
            SpanNormalizerConstants.REDACTED_PII_TAGS_KEY,
            AttributeValue.newBuilder().setValue(String.valueOf(piiFieldsCount)).build());
      }

      if (pciFieldsCount > 0) {
        attributeMap.put(
            SpanNormalizerConstants.REDACTED_PCI_TAGS_KEY,
            AttributeValue.newBuilder().setValue(String.valueOf(pciFieldsCount)).build());
      }

    } catch (Exception e) {
      LOG.error("An exception occurred while performing span redaction: ", e);
    }
  }

  private boolean skipRedactionForTagKey(String tagKey, Set<String> tagKeySet) {
    return !tagKeySet.isEmpty() && !tagKeySet.contains(tagKey);
  }

  /**
   * Builds the event object from the jaeger span. Note: tagsMap should contain keys that have
   * already been converted to lowercase by the caller.
   */
  private Event buildEvent(
      String tenantId,
      Span jaegerSpan,
      @Nonnull Map<String, KeyValue> tagsMap,
      Optional<String> tenantIdKey) {
    Event.Builder eventBuilder = fastNewBuilder(Event.Builder.class);
    eventBuilder.setCustomerId(tenantId);
    eventBuilder.setEventId(jaegerSpan.getSpanId().asReadOnlyByteBuffer());
    eventBuilder.setEventName(jaegerSpan.getOperationName());

    // time related stuff
    long startTimeMillis = Timestamps.toMillis(jaegerSpan.getStartTime());
    eventBuilder.setStartTimeMillis(startTimeMillis);
    long endTimeMillis =
        Timestamps.toMillis(Timestamps.add(jaegerSpan.getStartTime(), jaegerSpan.getDuration()));
    eventBuilder.setEndTimeMillis(endTimeMillis);

    // SPAN REFS
    List<JaegerSpanInternalModel.SpanRef> referencesList = jaegerSpan.getReferencesList();
    if (referencesList.size() > 0) {
      eventBuilder.setEventRefList(new ArrayList<>());
      // Convert the reflist to a set to remove duplicate references. This has been observed in the
      // field.
      Set<JaegerSpanInternalModel.SpanRef> referencesSet = new HashSet<>(referencesList);
      for (JaegerSpanInternalModel.SpanRef spanRef : referencesSet) {
        EventRef.Builder builder = fastNewBuilder(EventRef.Builder.class);
        builder.setTraceId(spanRef.getTraceId().asReadOnlyByteBuffer());
        builder.setEventId(spanRef.getSpanId().asReadOnlyByteBuffer());
        builder.setRefType(EventRefType.valueOf(spanRef.getRefType().toString()));
        eventBuilder.getEventRefList().add(builder.build());
      }
    }

    // span attributes to event attributes
    Map<String, AttributeValue> attributeFieldMap = new HashMap<>();
    eventBuilder.setAttributesBuilder(
        fastNewBuilder(Attributes.Builder.class).setAttributeMap(attributeFieldMap));

    List<KeyValue> tagsList = jaegerSpan.getTagsList();
    // Stop populating first class fields for - grpc, rpc, http, and sql.
    // see more details:
    // https://github.com/hypertrace/hypertrace/issues/244
    // https://github.com/hypertrace/hypertrace/issues/245
    for (KeyValue keyValue : tagsList) {
      // Convert all attributes to lower case so that we don't have to
      // deal with the case sensitivity across different layers in the
      // platform.
      String key = keyValue.getKey().toLowerCase();
      // Do not add the tenant id to the tags.
      if ((tenantIdKey.isPresent() && key.equals(tenantIdKey.get()))) {
        continue;
      }
      attributeFieldMap.put(key, JaegerHTTagsConverter.createFromJaegerKeyValue(keyValue));
    }

    // Jaeger Fields - flags, warnings, logs, jaeger service name in the Process
    JaegerFields.Builder jaegerFieldsBuilder = eventBuilder.getJaegerFieldsBuilder();
    // FLAGS
    jaegerFieldsBuilder.setFlags(jaegerSpan.getFlags());

    // WARNINGS
    ProtocolStringList warningsList = jaegerSpan.getWarningsList();
    if (warningsList.size() > 0) {
      jaegerFieldsBuilder.setWarnings(warningsList);
    }

    // Jaeger service name can come from either first class field in Span or the tag
    // `jaeger.servicename`
    String serviceName =
        !StringUtils.isEmpty(jaegerSpan.getProcess().getServiceName())
            ? jaegerSpan.getProcess().getServiceName()
            : attributeFieldMap.containsKey(OLD_JAEGER_SERVICENAME_KEY)
                ? attributeFieldMap.get(OLD_JAEGER_SERVICENAME_KEY).getValue()
                : StringUtils.EMPTY;

    if (!StringUtils.isEmpty(serviceName)) {
      eventBuilder.setServiceName(serviceName);
      // in case `jaeger.servicename` is present in the map, remove it
      attributeFieldMap.remove(OLD_JAEGER_SERVICENAME_KEY);
      attributeFieldMap.put(
          RawSpanConstants.getValue(JaegerAttribute.JAEGER_ATTRIBUTE_SERVICE_NAME),
          AttributeValueCreator.create(serviceName));
    }

    // EVENT METRICS
    Map<String, MetricValue> metricMap = new HashMap<>();
    MetricValue durationMetric =
        fastNewBuilder(MetricValue.Builder.class)
            .setValue((double) (endTimeMillis - startTimeMillis))
            .build();
    metricMap.put("Duration", durationMetric);

    eventBuilder.setMetrics(fastNewBuilder(Metrics.Builder.class).setMetricMap(metricMap).build());

    return eventBuilder.build();
  }

  // Check if debug log is enabled before calling this method
  private void logSpanConversion(Span jaegerSpan, RawSpan rawSpan) {
    try {
      LOG.debug(
          "Converted Jaeger span: {} to rawSpan: {} ",
          jaegerSpan,
          convertToJsonString(rawSpan, rawSpan.getSchema()));
    } catch (IOException e) {
      LOG.warn("An exception occurred while converting avro to JSON string", e);
    }
  }

  // We should have a small library for useful short methods like this one such as this.
  public static <T extends SpecificRecordBase> String convertToJsonString(T object, Schema schema)
      throws IOException {
    JsonEncoder encoder;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      DatumWriter<T> writer = new SpecificDatumWriter<>(schema);
      encoder = EncoderFactory.get().jsonEncoder(schema, output, false);
      writer.write(object, encoder);
      encoder.flush();
      output.flush();
      return output.toString();
    }
  }
}
