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
public final class PlayNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(PlayNiqResponseHeaderInstrumentation.class);

  public PlayNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "play");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "play.api.mvc.Result",
      "play.mvc.Result"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("toScala").or(named("asScala")).or(named("asJava")),
        PlayNiqResponseHeaderInstrumentation.class.getName() + "$ResultAdvice");
  }

  public static class ResultAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.This final Object result) {
      try {
        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-PLAY] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-PLAY] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        // Use reflection to add header to result
        java.lang.reflect.Method withHeaderMethod = result.getClass().getMethod("withHeader", String.class, String.class);
        withHeaderMethod.invoke(result, "current-span-id", traceparent);

        log.debug("[NIQ-PLAY] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-PLAY] Error injecting header", e);
      }
    }
  }
}
