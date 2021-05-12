package org.hypertrace.core.rawspansgrouper;

import static org.hypertrace.core.rawspansgrouper.RawSpanGrouperConstants.SPANS_PER_TRACE_METRIC;
import static org.hypertrace.core.rawspansgrouper.RawSpanGrouperConstants.TRACE_CREATION_TIME;

import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.Cancellable;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.Punctuator;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.glassfish.jersey.internal.guava.Sets;
import org.hypertrace.core.datamodel.RawSpan;
import org.hypertrace.core.datamodel.StructuredTrace;
import org.hypertrace.core.datamodel.TimestampRecord;
import org.hypertrace.core.datamodel.Timestamps;
import org.hypertrace.core.datamodel.shared.DataflowMetricUtils;
import org.hypertrace.core.datamodel.shared.HexUtils;
import org.hypertrace.core.datamodel.shared.trace.StructuredTraceBuilder;
import org.hypertrace.core.serviceframework.metrics.PlatformMetricsRegistry;
import org.hypertrace.core.spannormalizer.SpanIdentity;
import org.hypertrace.core.spannormalizer.TraceIdentity;
import org.hypertrace.core.spannormalizer.TraceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks if a trace can be finalized and emitted based on inactivity period of {@link
 * RawSpansProcessor#groupingWindowTimeoutMs}
 */
class PeriodicPunctuator implements Punctuator {

  private static final Logger logger = LoggerFactory.getLogger(TraceEmitPunctuator.class);

  private static final Timer spansGrouperArrivalLagTimer =
      PlatformMetricsRegistry.registerTimer(DataflowMetricUtils.ARRIVAL_LAG, new HashMap<>());
  private static final Object mutex = new Object();
  private static final String PUNCTUATE_LATENCY_TIMER =
      "hypertrace.rawspansgrouper.punctuate.latency";
  private static final ConcurrentMap<String, Timer> tenantToPunctuateLatencyTimer =
      new ConcurrentHashMap<>();

  private final double dataflowSamplingPercent;
  private final ProcessorContext context;
  private final WindowStore<SpanIdentity, RawSpan> spanWindowStore;
  private final KeyValueStore<TraceIdentity, TraceState> traceStateStore;
  private final To outputTopicProducer;
  private final long groupingWindowTimeoutMs;
  private Cancellable cancellable;

  private static final String TRACES_EMITTER_COUNTER = "hypertrace.emitted.traces";
  private static final ConcurrentMap<String, Counter> tenantToTraceEmittedCounter =
      new ConcurrentHashMap<>();

  PeriodicPunctuator(
      ProcessorContext context,
      WindowStore<SpanIdentity, RawSpan> spanWindowStore,
      KeyValueStore<TraceIdentity, TraceState> traceStateStore,
      To outputTopicProducer,
      long groupingWindowTimeoutMs,
      double dataflowSamplingPercent) {
    this.context = context;
    this.spanWindowStore = spanWindowStore;
    this.traceStateStore = traceStateStore;
    this.outputTopicProducer = outputTopicProducer;
    this.groupingWindowTimeoutMs = groupingWindowTimeoutMs;
    this.dataflowSamplingPercent = dataflowSamplingPercent;
  }

  public void setCancellable(Cancellable cancellable) {
    this.cancellable = cancellable;
  }

  /** @param timestamp correspond to current system time */
  @Override
  public void punctuate(long timestamp) {
    Instant startTime = Instant.now();

    Map<TraceIdentity, TraceState> map = findEmittableKeys(timestamp);

    System.out.println(map);

    if (!map.isEmpty()) {

      TreeSet<SpanIdentity> spanIdsSet =
          new TreeSet<>(
              (spanId1, spanId2) -> {
                // the sort order for this set is determined by comparing the corresponding {@code
                // SpanIdentifier}
                Serde<SpanIdentity> serde = (Serde<SpanIdentity>) context.keySerde();
                byte[] spanIdentityBytes1 = serde.serializer().serialize("", spanId1);
                byte[] spanIdentityBytes2 = serde.serializer().serialize("", spanId2);
                return Bytes.BYTES_LEXICO_COMPARATOR.compare(
                    spanIdentityBytes1, spanIdentityBytes2);
              });

      long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
      Set<ByteBuffer> traceIds = Sets.newHashSet();
      for (Map.Entry<TraceIdentity, TraceState> entry : map.entrySet()) {
        TraceState traceState = entry.getValue();
        traceState
            .getSpanIds()
            .forEach(
                v ->
                    spanIdsSet.add(
                        new SpanIdentity(traceState.getTenantId(), traceState.getTraceId(), v)));
        minTs = Math.min(minTs, traceState.getTraceStartTimestamp());
        maxTs = Math.max(maxTs, traceState.getTraceEndTimestamp());
        traceIds.add(traceState.getTraceId());
      }

      Map<ByteBuffer, List<RawSpan>> rawSpanMap = new HashMap<>();
      try (KeyValueIterator<Windowed<SpanIdentity>, RawSpan> iterator =
          spanWindowStore.fetch(
              spanIdsSet.first(),
              spanIdsSet.last(),
              Instant.ofEpochMilli(minTs),
              Instant.ofEpochMilli(maxTs))) {
        iterator.forEachRemaining(
            keyValue -> {
              // range search could return extra span ids as well, filter them
              // one scenario when this could occur is when some spanIdentifier for another trace
              // has byte value which is within the {@code spanIdsSet.first()} & {@code
              // spanIdsSet.last()}
              // and was received in the same time range
              if (traceIds.contains(keyValue.value.getTraceId())) {
                rawSpanMap
                    .computeIfAbsent(keyValue.value.getTraceId(), v -> new ArrayList<>())
                    .add(keyValue.value);
              }
            });
      }
      for (List<RawSpan> rawSpanList : rawSpanMap.values()) {
        String tenantId = rawSpanList.get(0).getCustomerId();
        ByteBuffer traceId = rawSpanList.get(0).getTraceId();
        recordSpansPerTrace(rawSpanList.size(), List.of(Tag.of("tenant_id", tenantId)));
        Timestamps timestamps = trackEndToEndLatencyTimestamps(timestamp, timestamp);
        StructuredTrace trace =
            StructuredTraceBuilder.buildStructuredTraceFromRawSpans(
                rawSpanList, traceId, tenantId, timestamps);

        if (logger.isDebugEnabled()) {
          logger.debug(
              "Emit tenant_id=[{}], trace_id=[{}], spans_count=[{}]",
              tenantId,
              HexUtils.getHex(traceId),
              rawSpanList.size());
        }

        tenantToTraceEmittedCounter
            .computeIfAbsent(
                tenantId,
                k ->
                    PlatformMetricsRegistry.registerCounter(
                        TRACES_EMITTER_COUNTER, Map.of("tenantId", k)))
            .increment();

        tenantToPunctuateLatencyTimer
            .computeIfAbsent(
                tenantId,
                k ->
                    PlatformMetricsRegistry.registerTimer(
                        PUNCTUATE_LATENCY_TIMER, Map.of("tenantId", k)))
            .record(Duration.between(startTime, Instant.now()).toMillis(), TimeUnit.MILLISECONDS);
        // delete from state store
        traceStateStore.delete(new TraceIdentity(tenantId, traceId));
        context.forward(null, trace, outputTopicProducer);
      }
    }
    /**
     * else { // implies spans for the trace have arrived within the last 'sessionTimeoutMs'
     * interval // so the session inactivity window is extended from the last timestamp if
     * (logger.isDebugEnabled()) { logger.debug( "Re-scheduling emit trigger for tenant_id=[{}],
     * trace_id=[{}] to [{}]", key.getTenantId(), HexUtils.getHex(key.getTraceId()),
     * Instant.ofEpochMilli(emitTs + groupingWindowTimeoutMs)); } long newEmitTs = emitTs +
     * groupingWindowTimeoutMs; // if current timestamp is ahead of newEmitTs then just add a grace
     * of 100ms and fire it long duration = Math.max(100, newEmitTs - timestamp); cancellable =
     * context.schedule(Duration.ofMillis(duration), PunctuationType.WALL_CLOCK_TIME, this); }
     */
  }

  Map<TraceIdentity, TraceState> findEmittableKeys(long timestamp) {
    Map<TraceIdentity, TraceState> map = Maps.newHashMap();
    try (KeyValueIterator<TraceIdentity, TraceState> it = traceStateStore.all()) {
      KeyValue<TraceIdentity, TraceState> kv = it.next();
      System.out.println(kv);
      TraceState traceState = kv.value;
      if (null == traceState
          || null == traceState.getSpanIds()
          || traceState.getSpanIds().isEmpty()) {
        /*
         todo - debug why this happens .
         Typically seen when punctuators are created via {@link RawSpansGroupingTransformer.restorePunctuators}
        */
        logger.warn(
            "TraceState for tenant_id=[{}], trace_id=[{}] is missing.",
            kv.key.getTenantId(),
            HexUtils.getHex(kv.key.getTraceId()));
      } else {
        long emitTs = traceState.getEmitTs();
        if (emitTs <= timestamp) {
          map.put(kv.key, traceState);
        }
      }
    }
    return map;
  }

  private Timestamps trackEndToEndLatencyTimestamps(
      long currentTimestamp, long firstSpanTimestamp) {
    Timestamps timestamps = null;
    if (!(Math.random() * 100 <= dataflowSamplingPercent)) {
      spansGrouperArrivalLagTimer.record(
          currentTimestamp - firstSpanTimestamp, TimeUnit.MILLISECONDS);
      Map<String, TimestampRecord> records = new HashMap<>();
      records.put(
          DataflowMetricUtils.SPAN_ARRIVAL_TIME,
          new TimestampRecord(DataflowMetricUtils.SPAN_ARRIVAL_TIME, firstSpanTimestamp));
      records.put(TRACE_CREATION_TIME, new TimestampRecord(TRACE_CREATION_TIME, currentTimestamp));
      timestamps = new Timestamps(records);
    }
    return timestamps;
  }

  private void recordSpansPerTrace(double count, Iterable<Tag> tags) {
    DistributionSummary summary =
        DistributionSummary.builder(SPANS_PER_TRACE_METRIC)
            .tags(tags)
            .publishPercentiles(.5, .90, .99)
            .register(PlatformMetricsRegistry.getMeterRegistry());
    // For a given name + tags the same Meter object is used and will be shared across StreamThreads
    synchronized (mutex) {
      summary.record(count);
    }
  }
}
