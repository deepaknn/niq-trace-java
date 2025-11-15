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
public final class Netty40NiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(Netty40NiqResponseHeaderInstrumentation.class);

  public Netty40NiqResponseHeaderInstrumentation() {
    super("niq-response-header", "netty", "netty-4.0");
  }

  @Override
  public String instrumentedType() {
    return "datadog.trace.instrumentation.netty40.server.HttpServerResponseTracingHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("write"),
        Netty40NiqResponseHeaderInstrumentation.class.getName() + "$WriteAdvice");
  }

  public static class WriteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.Argument(1) final Object msg) {
      try {
        if (!(msg instanceof HttpResponse)) {
          return;
        }

        HttpResponse response = (HttpResponse) msg;

        AgentSpan span = datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-NETTY40] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-NETTY40] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        response.headers().set("current-span-id", traceparent);

        log.debug("[NIQ-NETTY40] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-NETTY40] Error injecting header", e);
      }
    }
  }
}
