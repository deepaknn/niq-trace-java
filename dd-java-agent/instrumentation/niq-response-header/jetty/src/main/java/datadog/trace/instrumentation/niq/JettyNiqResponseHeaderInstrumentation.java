package datadog.trace.instrumentation.niq;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class JettyNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(JettyNiqResponseHeaderInstrumentation.class);

  public JettyNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Hook the handle method before response is sent
    transformer.applyAdvice(
        named("handle"),
        JettyNiqResponseHeaderInstrumentation.class.getName() + "$HandleAdvice");
  }

  public static class HandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final Object channel) {
      try {
        // Use reflection to get the response from HttpChannel
        java.lang.reflect.Method getResponseMethod = channel.getClass().getMethod("getResponse");
        Object response = getResponseMethod.invoke(channel);

        // Check if response is committed
        java.lang.reflect.Method isCommittedMethod = response.getClass().getMethod("isCommitted");
        Boolean committed = (Boolean) isCommittedMethod.invoke(response);

        if (committed) {
          log.trace("[NIQ-JETTY] Response already committed");
          return;
        }

        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-JETTY] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-JETTY] Invalid span or trace ID");
          return;
        }

        // Build W3C traceparent format: 00-{traceId}-{spanId}-01~ncsd
        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        // Set header using reflection
        java.lang.reflect.Method setHeaderMethod =
            response.getClass().getMethod("setHeader", String.class, String.class);
        setHeaderMethod.invoke(response, "current-span-id", traceparent);

        log.debug("[NIQ-JETTY] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-JETTY] Error injecting header", e);
      }
    }
  }
}
