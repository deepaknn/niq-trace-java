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
public final class ArmeriaNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(ArmeriaNiqResponseHeaderInstrumentation.class);

  public ArmeriaNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "armeria");
  }

  @Override
  public String instrumentedType() {
    return "com.linecorp.armeria.server.ServiceRequestContext";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("mutateAdditionalResponseHeaders"),
        ArmeriaNiqResponseHeaderInstrumentation.class.getName() + "$MutateHeadersAdvice");
  }

  public static class MutateHeadersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final Object context) {
      try {
        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-ARMERIA] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-ARMERIA] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        // Use reflection to add header
        java.lang.reflect.Method addHeaderMethod = context.getClass().getMethod("addAdditionalResponseHeader", CharSequence.class, Object.class);
        addHeaderMethod.invoke(context, "current-span-id", traceparent);

        log.debug("[NIQ-ARMERIA] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-ARMERIA] Error injecting header", e);
      }
    }
  }
}
