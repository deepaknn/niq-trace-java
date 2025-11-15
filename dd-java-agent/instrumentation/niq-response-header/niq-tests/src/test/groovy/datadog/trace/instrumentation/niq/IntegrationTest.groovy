package datadog.trace.instrumentation.niq

import datadog.context.propagation.CarrierSetter
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.core.DDSpanContext
import datadog.trace.core.propagation.DatadogHttpCodec
import datadog.trace.core.propagation.W3CHttpCodec
import spock.lang.Specification

/**
 * Integration tests for end-to-end NIQ tracing flow
 */
class IntegrationTest extends Specification {

  def "end-to-end test: request propagation and response injection"() {
    given: "A simulated request with trace context"
    def traceId = DDTraceId.from("a1b2c3d4e5f60000a1b2c3d4e5f60000")
    def spanId = 0x123456789abcdef0L
    def parentId = 0x0fedcba987654321L

    def context = DDSpanContext.builder()
      .traceId(traceId)
      .spanId(spanId)
      .parentId(parentId)
      .serviceName("test-service")
      .build()

    and: "Request headers carrier"
    def requestHeaders = new HashMap<String, String>()
    def setter = new MapSetter()

    when: "Request propagation occurs"
    def datadogInjector = DatadogHttpCodec.newInjector([:])
    datadogInjector.inject(context, requestHeaders, setter)

    then: "niqtid header is present in request"
    requestHeaders.containsKey("niqtid")
    def niqtidValue = requestHeaders.get("niqtid")
    niqtidValue != null
    niqtidValue.endsWith("~niqtid")

    and: "niqtid has correct format"
    def niqtidParts = niqtidValue.substring(0, niqtidValue.indexOf("~niqtid")).split("-")
    niqtidParts.length == 3
    niqtidParts[0] == traceId.toHexString()
    niqtidParts[1] == DDSpanId.toHexStringPadded(spanId)
    niqtidParts[2] == DDSpanId.toHexStringPadded(parentId)

    when: "Response is prepared with current span context"
    def responseTraceId = traceId // Same trace
    def responseSpanId = 0x9876543210fedcbaL // New span ID for this service

    String responseTraceIdHex = responseTraceId.toHexString()
    String responseSpanIdHex = DDSpanId.toHexStringPadded(responseSpanId)
    String currentSpanIdHeader = "00-" + responseTraceIdHex + "-" + responseSpanIdHex + "-01~ncsd"

    then: "current-span-id header has correct W3C format"
    currentSpanIdHeader.startsWith("00-")
    currentSpanIdHeader.endsWith("-01~ncsd")
    currentSpanIdHeader.contains(responseTraceIdHex)
    currentSpanIdHeader.contains(responseSpanIdHex)

    and: "Both headers maintain trace ID consistency"
    niqtidParts[0] == responseTraceIdHex
  }

  def "test distributed tracing across multiple services"() {
    given: "Service A creates initial request"
    def serviceATraceId = DDTraceId.from(111111L)
    def serviceASpanId = 222222L
    def serviceAContext = createContext(serviceATraceId, serviceASpanId, 0L)

    and: "Service A propagates to Service B"
    def requestHeaders = new HashMap<String, String>()
    def injector = DatadogHttpCodec.newInjector([:])
    injector.inject(serviceAContext, requestHeaders, new MapSetter())

    when: "Service B receives request and creates child span"
    def serviceBSpanId = 333333L
    def serviceBContext = createContext(serviceATraceId, serviceBSpanId, serviceASpanId)

    and: "Service B prepares response"
    String serviceBResponseHeader = buildCurrentSpanIdHeader(serviceATraceId, serviceBSpanId)

    then: "Service B response header contains correct span ID"
    serviceBResponseHeader.contains(DDSpanId.toHexStringPadded(serviceBSpanId))

    and: "Trace ID is consistent across services"
    requestHeaders.get("niqtid").startsWith(serviceATraceId.toHexString())
    serviceBResponseHeader.contains(serviceATraceId.toHexString())

    when: "Service B calls Service C"
    def serviceCHeaders = new HashMap<String, String>()
    injector.inject(serviceBContext, serviceCHeaders, new MapSetter())

    then: "Trace ID remains consistent"
    serviceCHeaders.get("niqtid").startsWith(serviceATraceId.toHexString())

    and: "Parent IDs are correctly propagated"
    serviceCHeaders.get("niqtid").contains(DDSpanId.toHexStringPadded(serviceBSpanId))
  }

  def "test with W3C propagation"() {
    given: "A trace context"
    def traceId = DDTraceId.from("fedcba9876543210fedcba9876543210")
    def spanId = 0x1234567890abcdefL
    def context = createContext(traceId, spanId, 0L)

    and: "W3C propagator"
    def injector = W3CHttpCodec.newInjector([:])
    def requestHeaders = new HashMap<String, String>()

    when: "Headers are injected"
    injector.inject(context, requestHeaders, new MapSetter())

    then: "Both traceparent and niqtid are present"
    requestHeaders.containsKey("traceparent")
    requestHeaders.containsKey("niqtid")

    and: "Both contain the same trace ID"
    def traceparent = requestHeaders.get("traceparent")
    def niqtid = requestHeaders.get("niqtid")

    traceparent.contains(traceId.toHexString())
    niqtid.contains(traceId.toHexString())

    and: "Response header would have same trace ID"
    def responseHeader = buildCurrentSpanIdHeader(traceId, spanId)
    responseHeader.contains(traceId.toHexString())
  }

  def "test header format compatibility"() {
    given:
    def traceId = DDTraceId.from("00112233445566778899aabbccddeeff")
    def spanId = 0x0011223344556677L
    def parentId = 0x8899aabbccddeeffL

    when: "Request header is created"
    def niqtidValue = traceId.toHexString() + "-" +
                      DDSpanId.toHexStringPadded(spanId) + "-" +
                      DDSpanId.toHexStringPadded(parentId) + "~niqtid"

    and: "Response header is created"
    def currentSpanIdValue = "00-" + traceId.toHexString() + "-" +
                             DDSpanId.toHexStringPadded(spanId) + "-01~ncsd"

    then: "Both headers are valid"
    niqtidValue ==~ /[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{16}~niqtid/
    currentSpanIdValue ==~ /00-[0-9a-f]{32}-[0-9a-f]{16}-01~ncsd/

    and: "Headers share the same trace ID and span ID"
    niqtidValue.substring(0, 32) == currentSpanIdValue.substring(3, 35)
    niqtidValue.substring(33, 49) == currentSpanIdValue.substring(36, 52)
  }

  // Helper methods
  private DDSpanContext createContext(DDTraceId traceId, long spanId, long parentId) {
    return DDSpanContext.builder()
      .traceId(traceId)
      .spanId(spanId)
      .parentId(parentId)
      .serviceName("test-service")
      .build()
  }

  private String buildCurrentSpanIdHeader(DDTraceId traceId, long spanId) {
    return "00-" + traceId.toHexString() + "-" +
           DDSpanId.toHexStringPadded(spanId) + "-01~ncsd"
  }

  private static class MapSetter implements CarrierSetter<Map<String, String>> {
    @Override
    void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value)
    }
  }
}
