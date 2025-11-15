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
import jakarta.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class JakartaNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(JakartaNiqResponseHeaderInstrumentation.class);

  public JakartaNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "servlet-5", "servlet-response");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Hook methods that are called before response is sent
    transformer.applyAdvice(
        namedOneOf("getWriter", "getOutputStream"),
        JakartaNiqResponseHeaderInstrumentation.class.getName() + "$CurrentSpanIdAdvice");
    // Also hook commit methods as backup
    transformer.applyAdvice(
        namedOneOf("flushBuffer", "sendError", "sendRedirect"),
        JakartaNiqResponseHeaderInstrumentation.class.getName() + "$CurrentSpanIdAdvice");
  }

  public static class CurrentSpanIdAdvice {
    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectCurrentSpanId(@Advice.This final HttpServletResponse response) {
      try {
        if (INJECTED.get()) {
          log.trace("[NIQ-JAKARTA] Header already injected for this response, skipping");
          return;
        }

        if (response.isCommitted()) {
          log.trace("[NIQ-JAKARTA] Response already committed, cannot inject header");
          return;
        }

        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-JAKARTA] No active span found, skipping header injection");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-JAKARTA] Invalid span or trace ID, skipping header injection");
          return;
        }

        // Build W3C traceparent format: 00-{traceId}-{spanId}-01~ncsd
        String traceIdHex = traceId.toHexString(); // 32 chars
        String spanIdHex = DDSpanId.toHexStringPadded(spanId); // 16 chars
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        if (!response.isCommitted()) {
          response.setHeader("current-span-id", traceparent);
          INJECTED.set(Boolean.TRUE);
          log.debug("[NIQ-JAKARTA] Successfully injected current-span-id header: {}", traceparent);
        }
      } catch (IllegalStateException e) {
        log.warn("[NIQ-JAKARTA] Response committed during injection: {}", e.getMessage());
      } catch (Exception e) {
        log.warn("[NIQ-JAKARTA] Error injecting header", e);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cleanup() {
      INJECTED.remove();
    }
  }
}
