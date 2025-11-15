package datadog.trace.instrumentation.niq;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.handler.codec.http.HttpResponse;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class Netty41NiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(Netty41NiqResponseHeaderInstrumentation.class);

  public Netty41NiqResponseHeaderInstrumentation() {
    super("niq-response-header", "netty", "netty-4.1");
  }

  @Override
  public String instrumentedType() {
    return "datadog.trace.instrumentation.netty41.server.HttpServerResponseTracingHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("write"),
        Netty41NiqResponseHeaderInstrumentation.class.getName() + "$WriteAdvice");
  }

  public static class WriteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(
        @Advice.Argument(1) final Object msg,
        @Advice.Local("niqInjected") boolean niqInjected) {
      try {
        if (!(msg instanceof HttpResponse)) {
          return;
        }

        HttpResponse response = (HttpResponse) msg;

        // Get active span using AgentTracer
        AgentSpan span = datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-NETTY41] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-NETTY41] Invalid span or trace ID");
          return;
        }

        // Build W3C traceparent format: 00-{traceId}-{spanId}-01~ncsd
        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        // Inject the header
        response.headers().set("current-span-id", traceparent);
        niqInjected = true;

        log.debug("[NIQ-NETTY41] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-NETTY41] Error injecting header", e);
      }
    }
  }
}
