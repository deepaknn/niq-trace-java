package datadog.trace.instrumentation.niq;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class GrizzlyNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(GrizzlyNiqResponseHeaderInstrumentation.class);

  public GrizzlyNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "grizzly");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.glassfish.grizzly.http.server.Response";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("flush", "finish"),
        GrizzlyNiqResponseHeaderInstrumentation.class.getName() + "$ResponseAdvice");
  }

  public static class ResponseAdvice {
    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final Object response) {
      try {
        if (INJECTED.get()) {
          log.trace("[NIQ-GRIZZLY] Header already injected");
          return;
        }

        // Check if committed
        java.lang.reflect.Method isCommittedMethod = response.getClass().getMethod("isCommitted");
        Boolean committed = (Boolean) isCommittedMethod.invoke(response);
        if (committed) {
          log.trace("[NIQ-GRIZZLY] Response already committed");
          return;
        }

        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-GRIZZLY] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-GRIZZLY] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        java.lang.reflect.Method setHeaderMethod = response.getClass().getMethod("setHeader", String.class, String.class);
        setHeaderMethod.invoke(response, "current-span-id", traceparent);

        INJECTED.set(Boolean.TRUE);
        log.debug("[NIQ-GRIZZLY] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-GRIZZLY] Error injecting header", e);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cleanup() {
      INJECTED.remove();
    }
  }
}
