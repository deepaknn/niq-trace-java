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
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class SpringWebFlux6NiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(SpringWebFlux6NiqResponseHeaderInstrumentation.class);
  private static final String SPAN_ATTRIBUTE = "datadog.trace.instrumentation.springwebflux.Span";

  public SpringWebFlux6NiqResponseHeaderInstrumentation() {
    super("niq-response-header", "spring-webflux", "spring-webflux-6");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.http.server.reactive.ServerHttpResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("writeWith", "setComplete"),
        SpringWebFlux6NiqResponseHeaderInstrumentation.class.getName() + "$ResponseAdvice");
  }

  public static class ResponseAdvice {
    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final Object response) {
      try {
        if (INJECTED.get()) {
          log.trace("[NIQ-WEBFLUX6] Header already injected");
          return;
        }

        java.lang.reflect.Method getExchangeMethod = response.getClass().getMethod("getExchange");
        Object exchange = getExchangeMethod.invoke(response);

        if (exchange == null) {
          return;
        }

        java.lang.reflect.Method getAttributesMethod = exchange.getClass().getMethod("getAttributes");
        Object attributes = getAttributesMethod.invoke(exchange);
        java.lang.reflect.Method getMethod = attributes.getClass().getMethod("get", Object.class);
        AgentSpan span = (AgentSpan) getMethod.invoke(attributes, SPAN_ATTRIBUTE);

        if (span == null) {
          log.trace("[NIQ-WEBFLUX6] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-WEBFLUX6] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        java.lang.reflect.Method getHeadersMethod = response.getClass().getMethod("getHeaders");
        Object headers = getHeadersMethod.invoke(response);
        java.lang.reflect.Method setMethod = headers.getClass().getMethod("set", String.class, String.class);
        setMethod.invoke(headers, "current-span-id", traceparent);

        INJECTED.set(Boolean.TRUE);
        log.debug("[NIQ-WEBFLUX6] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-WEBFLUX6] Error injecting header", e);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cleanup() {
      INJECTED.remove();
    }
  }
}
