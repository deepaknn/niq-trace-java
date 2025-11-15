package datadog.trace.instrumentation.niq

import datadog.context.propagation.CarrierSetter
import datadog.trace.api.DDTraceId
import datadog.trace.core.DDSpanContext
import datadog.trace.core.propagation.DatadogHttpCodec
import datadog.trace.core.propagation.W3CHttpCodec
import datadog.trace.core.propagation.B3HttpCodec
import datadog.trace.core.propagation.HaystackHttpCodec
import datadog.trace.core.propagation.XRayHttpCodec
import spock.lang.Specification

/**
 * Tests for niqtid header injection in all propagation codecs
 */
class NiqtidPropagationTest extends Specification {

  def "DatadogHttpCodec should inject niqtid header"() {
    given:
    def injector = DatadogHttpCodec.newInjector([:])
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext()

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.containsKey("niqtid")
    carrier.get("niqtid") != null
    carrier.get("niqtid").endsWith("~niqtid")
    carrier.get("niqtid").contains("-")

    // Verify format: {trace-id-hex}-{span-id-hex}-{parent-span-id-hex}~niqtid
    def parts = carrier.get("niqtid").split("-")
    parts.length == 3
    parts[2].endsWith("~niqtid")
  }

  def "W3CHttpCodec should inject niqtid header"() {
    given:
    def injector = W3CHttpCodec.newInjector([:])
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext()

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.containsKey("niqtid")
    carrier.get("niqtid") != null
    carrier.get("niqtid").endsWith("~niqtid")

    // Should also have traceparent
    carrier.containsKey("traceparent")
  }

  def "B3HttpCodec multi should inject niqtid header"() {
    given:
    def injector = B3HttpCodec.newMultiInjector(false)
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext()

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.containsKey("niqtid")
    carrier.get("niqtid") != null
    carrier.get("niqtid").endsWith("~niqtid")

    // Should also have B3 headers
    carrier.containsKey("X-B3-TraceId")
    carrier.containsKey("X-B3-SpanId")
  }

  def "B3HttpCodec single should inject niqtid header"() {
    given:
    def injector = B3HttpCodec.newSingleInjector(false)
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext()

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.containsKey("niqtid")
    carrier.get("niqtid") != null
    carrier.get("niqtid").endsWith("~niqtid")

    // Should also have b3 single header
    carrier.containsKey("b3")
  }

  def "HaystackHttpCodec should inject niqtid header"() {
    given:
    def injector = HaystackHttpCodec.newInjector([:])
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext()

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.containsKey("niqtid")
    carrier.get("niqtid") != null
    carrier.get("niqtid").endsWith("~niqtid")
  }

  def "XRayHttpCodec should inject niqtid header"() {
    given:
    def injector = XRayHttpCodec.newInjector([:])
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext()

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.containsKey("niqtid")
    carrier.get("niqtid") != null
    carrier.get("niqtid").endsWith("~niqtid")

    // Should also have X-Amzn-Trace-Id
    carrier.containsKey("X-Amzn-Trace-Id")
  }

  def "niqtid header should have correct format with parent span id"() {
    given:
    def injector = DatadogHttpCodec.newInjector([:])
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def parentId = 999L
    def context = createTestContext(parentId)

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.get("niqtid") != null
    def niqtidValue = carrier.get("niqtid")
    niqtidValue.contains(String.format("%016x", parentId))
  }

  def "niqtid header should have parent id as zeros for root span"() {
    given:
    def injector = DatadogHttpCodec.newInjector([:])
    def carrier = new HashMap<String, String>()
    def setter = new MapSetter()
    def context = createTestContext(0L) // root span

    when:
    injector.inject(context, carrier, setter)

    then:
    carrier.get("niqtid") != null
    def niqtidValue = carrier.get("niqtid")
    niqtidValue.contains("0000000000000000")
  }

  // Helper methods
  private DDSpanContext createTestContext(long parentId = 0L) {
    def traceId = DDTraceId.from(123456789L)
    def spanId = 987654321L
    return DDSpanContext.builder()
      .traceId(traceId)
      .spanId(spanId)
      .parentId(parentId)
      .serviceName("test-service")
      .build()
  }

  private static class MapSetter implements CarrierSetter<Map<String, String>> {
    @Override
    void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value)
    }
  }
}
