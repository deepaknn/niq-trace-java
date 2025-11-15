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
public final class Vertx50NiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(Vertx50NiqResponseHeaderInstrumentation.class);

  public Vertx50NiqResponseHeaderInstrumentation() {
    super("niq-response-header", "vertx", "vertx-5.0");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.vertx.core.http.HttpServerResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("write", "end", "sendFile"),
        Vertx50NiqResponseHeaderInstrumentation.class.getName() + "$ResponseAdvice");
  }

  public static class ResponseAdvice {
    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final Object response) {
      try {
        if (INJECTED.get()) {
          log.trace("[NIQ-VERTX50] Header already injected");
          return;
        }

        java.lang.reflect.Method headersEndedMethod = response.getClass().getMethod("headersEnded");
        Boolean headersEnded = (Boolean) headersEndedMethod.invoke(response);
        if (headersEnded) {
          log.trace("[NIQ-VERTX50] Headers already sent");
          return;
        }

        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-VERTX50] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-VERTX50] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        java.lang.reflect.Method putHeaderMethod = response.getClass().getMethod("putHeader", String.class, String.class);
        putHeaderMethod.invoke(response, "current-span-id", traceparent);

        INJECTED.set(Boolean.TRUE);
        log.debug("[NIQ-VERTX50] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-VERTX50] Error injecting header", e);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cleanup() {
      INJECTED.remove();
    }
  }
}
