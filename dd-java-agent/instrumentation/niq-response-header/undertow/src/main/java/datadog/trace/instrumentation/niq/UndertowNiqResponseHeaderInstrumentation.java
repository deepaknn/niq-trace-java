package datadog.trace.instrumentation.niq;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class UndertowNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(UndertowNiqResponseHeaderInstrumentation.class);

  public UndertowNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "undertow");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.HttpServerExchange";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getResponseChannel"),
        UndertowNiqResponseHeaderInstrumentation.class.getName() + "$GetResponseChannelAdvice");
  }

  public static class GetResponseChannelAdvice {
    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final HttpServerExchange exchange) {
      try {
        if (INJECTED.get()) {
          log.trace("[NIQ-UNDERTOW] Header already injected");
          return;
        }

        if (exchange.isResponseStarted()) {
          log.trace("[NIQ-UNDERTOW] Response already started");
          return;
        }

        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-UNDERTOW] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-UNDERTOW] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("current-span-id"), traceparent);

        INJECTED.set(Boolean.TRUE);
        log.debug("[NIQ-UNDERTOW] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-UNDERTOW] Error injecting header", e);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cleanup() {
      INJECTED.remove();
    }
  }
}
