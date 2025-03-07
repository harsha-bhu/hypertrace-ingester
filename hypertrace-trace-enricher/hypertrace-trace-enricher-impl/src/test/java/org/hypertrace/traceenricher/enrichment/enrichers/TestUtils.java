package org.hypertrace.traceenricher.enrichment.enrichers;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.codec.binary.Base64;
import org.hypertrace.core.datamodel.Attributes;
import org.hypertrace.core.datamodel.Edge;
import org.hypertrace.core.datamodel.EdgeType;
import org.hypertrace.core.datamodel.Event;
import org.hypertrace.core.datamodel.StructuredTrace;
import org.hypertrace.core.datamodel.shared.trace.AttributeValueCreator;
import org.hypertrace.traceenricher.enrichedspan.constants.EnrichedSpanConstants;
import org.hypertrace.traceenricher.enrichedspan.constants.v1.Api;
import org.hypertrace.traceenricher.enrichedspan.constants.v1.BoundaryTypeValue;

public class TestUtils {

  private static final String TEST_CUSTOMER_ID = "testCustomerId";

  private static final Gson gson =
      new GsonBuilder()
          .serializeNulls()
          .registerTypeHierarchyAdapter(ByteBuffer.class, new ByteBufferTypeAdapter())
          .create();

  private TestUtils() {}

  public static void assertTraceDoesNotContainAttribute(
      StructuredTrace trace, String attributeKey) {
    assertFalse(trace.getAttributes().getAttributeMap().containsKey(attributeKey));
  }

  public static Event createUnspecifiedTypeEventWithName(String eventName) {
    Event event = createUnspecifiedTypeEvent();
    event.setEventName(eventName);
    return event;
  }

  public static Event createEntryEventWithName(String eventName) {
    Event event = createEntryEvent();
    event.setEventName(eventName);
    return event;
  }

  public static Event createExitEventName(String eventName) {
    Event event = createExitEvent();
    event.setEventName(eventName);
    return event;
  }

  private static Event createUnspecifiedTypeEvent() {
    return createEventOfBoundaryType(BoundaryTypeValue.BOUNDARY_TYPE_VALUE_UNSPECIFIED);
  }

  private static Event createEventOfBoundaryType(BoundaryTypeValue boundaryTypeValue) {
    Event event = createEvent();
    addEnrichedSpanAttribute(
        event,
        EnrichedSpanConstants.getValue(Api.API_BOUNDARY_TYPE),
        EnrichedSpanConstants.getValue(boundaryTypeValue));
    return event;
  }

  private static void addEnrichedSpanAttribute(
      Event event, String attributeKey, String attributeValue) {
    event
        .getEnrichedAttributes()
        .getAttributeMap()
        .put(attributeKey, AttributeValueCreator.create(attributeValue));
  }

  private static Event createEvent() {
    return Event.newBuilder()
        .setCustomerId(TEST_CUSTOMER_ID)
        .setEventId(ByteBuffer.wrap(UUID.randomUUID().toString().getBytes()))
        .setAttributesBuilder(Attributes.newBuilder().setAttributeMap(new HashMap<>()))
        .setEnrichedAttributesBuilder(Attributes.newBuilder().setAttributeMap(new HashMap<>()))
        .build();
  }

  private static Event createEntryEvent() {
    return createEventOfBoundaryType(BoundaryTypeValue.BOUNDARY_TYPE_VALUE_ENTRY);
  }

  private static Event createExitEvent() {
    return createEventOfBoundaryType(BoundaryTypeValue.BOUNDARY_TYPE_VALUE_EXIT);
  }

  public static StructuredTrace createTraceWithEventsAndEdges(
      Event[] events, Map<Integer, int[]> adjList) {
    StructuredTrace trace = createStructuredTrace(events);
    List<Edge> eventEdgeList = new ArrayList<>();

    adjList.forEach(
        (src, list) -> {
          for (int target : list) {
            eventEdgeList.add(
                Edge.newBuilder()
                    .setSrcIndex(src)
                    .setTgtIndex(target)
                    .setEdgeType(EdgeType.EVENT_EVENT)
                    .build());
          }
        });
    trace.setEventEdgeList(eventEdgeList);
    return trace;
  }

  private static StructuredTrace createStructuredTrace(Event... events) {
    return createStructuredTraceWithEndTime(System.currentTimeMillis(), events);
  }

  private static StructuredTrace createStructuredTraceWithEndTime(
      long endTimeMillis, Event... events) {
    return StructuredTrace.newBuilder()
        .setCustomerId(TEST_CUSTOMER_ID)
        .setTraceId(ByteBuffer.wrap(UUID.randomUUID().toString().getBytes()))
        .setStartTimeMillis(endTimeMillis - 10000)
        .setEndTimeMillis(endTimeMillis)
        .setAttributes(Attributes.newBuilder().setAttributeMap(new HashMap<>()).build())
        .setEntityList(new ArrayList<>())
        .setEntityEdgeList(new ArrayList<>())
        .setEventEdgeList(new ArrayList<>())
        .setEntityEventEdgeList(new ArrayList<>())
        .setEventList(Lists.newArrayList(events))
        .build();
  }

  public static StructuredTrace readJSONStructuredTraceFromClasspath(String fileName)
      throws FileNotFoundException {

    URL resource = Thread.currentThread().getContextClassLoader().getResource("trace.json");

    return gson.fromJson(new FileReader(resource.getPath()), StructuredTrace.class);
  }

  public static class ByteBufferTypeAdapter
      implements JsonDeserializer<ByteBuffer>, JsonSerializer<ByteBuffer> {

    @Override
    public ByteBuffer deserialize(
        JsonElement jsonElement, Type type, JsonDeserializationContext context) {
      return ByteBuffer.wrap(Base64.decodeBase64(jsonElement.getAsString()));
    }

    @Override
    public JsonElement serialize(ByteBuffer src, Type typeOfSrc, JsonSerializationContext context) {
      return new JsonPrimitive(Base64.encodeBase64String(src.array()));
    }
  }
}
