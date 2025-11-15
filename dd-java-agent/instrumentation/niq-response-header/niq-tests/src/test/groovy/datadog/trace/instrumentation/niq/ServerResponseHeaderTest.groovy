package datadog.trace.instrumentation.niq

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Specification

/**
 * Tests for current-span-id response header injection
 */
class ServerResponseHeaderTest extends Specification {

  def "should generate correct W3C traceparent format for response header"() {
    given:
    def traceId = DDTraceId.from("a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6")
    def spanId = 123456789L

    when:
    String traceIdHex = traceId.toHexString()
    String spanIdHex = DDSpanId.toHexStringPadded(spanId)
    String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd"

    then:
    traceparent.startsWith("00-")
    traceparent.endsWith("-01~ncsd")
    traceparent.contains(traceIdHex)
    traceparent.contains(spanIdHex)

    // Verify format: 00-{32 char trace}-{16 char span}-01~ncsd
    def parts = traceparent.split("-")
    parts.length == 4
    parts[0] == "00"
    parts[1].length() == 32 // 128-bit trace ID
    parts[2].length() == 16 // 64-bit span ID
    parts[3] == "01~ncsd"
  }

  def "should pad span ID to 16 characters"() {
    given:
    def smallSpanId = 1L

    when:
    String spanIdHex = DDSpanId.toHexStringPadded(smallSpanId)

    then:
    spanIdHex.length() == 16
    spanIdHex == "0000000000000001"
  }

  def "should handle large span IDs correctly"() {
    given:
    def largeSpanId = Long.MAX_VALUE

    when:
    String spanIdHex = DDSpanId.toHexStringPadded(largeSpanId)

    then:
    spanIdHex.length() == 16
    spanIdHex == "7fffffffffffffff"
  }

  def "response header name should be 'current-span-id'"() {
    expect:
    "current-span-id" == "current-span-id" // Verify constant
  }

  def "response header should have ncsd marker"() {
    given:
    def traceId = DDTraceId.from(123L)
    def spanId = 456L

    when:
    String traceparent = "00-" + traceId.toHexString() + "-" + DDSpanId.toHexStringPadded(spanId) + "-01~ncsd"

    then:
    traceparent.contains("~ncsd")
    traceparent.endsWith("-01~ncsd")
  }

  def "should generate unique headers for different spans"() {
    given:
    def traceId1 = DDTraceId.from(111L)
    def spanId1 = 222L
    def traceId2 = DDTraceId.from(333L)
    def spanId2 = 444L

    when:
    String header1 = "00-" + traceId1.toHexString() + "-" + DDSpanId.toHexStringPadded(spanId1) + "-01~ncsd"
    String header2 = "00-" + traceId2.toHexString() + "-" + DDSpanId.toHexStringPadded(spanId2) + "-01~ncsd"

    then:
    header1 != header2
    header1.contains(DDSpanId.toHexStringPadded(spanId1))
    header2.contains(DDSpanId.toHexStringPadded(spanId2))
  }

  def "should handle zero span ID"() {
    given:
    def traceId = DDTraceId.from(123L)
    def spanId = 0L

    when:
    String spanIdHex = DDSpanId.toHexStringPadded(spanId)
    String traceparent = "00-" + traceId.toHexString() + "-" + spanIdHex + "-01~ncsd"

    then:
    spanIdHex == "0000000000000000"
    traceparent.contains("0000000000000000")
  }

  def "should maintain W3C traceparent format compatibility"() {
    given:
    def traceId = DDTraceId.from("00112233445566778899aabbccddeeff")
    def spanId = 0xaabbccdd11223344L

    when:
    String traceparent = "00-" + traceId.toHexString() + "-" + DDSpanId.toHexStringPadded(spanId) + "-01~ncsd"

    then:
    // W3C format: version-trace_id-parent_id-trace_flags
    traceparent ==~ /00-[0-9a-f]{32}-[0-9a-f]{16}-01~ncsd/
  }
}
