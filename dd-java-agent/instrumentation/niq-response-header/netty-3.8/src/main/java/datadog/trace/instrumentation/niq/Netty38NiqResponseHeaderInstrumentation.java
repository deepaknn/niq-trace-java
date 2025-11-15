package datadog.trace.instrumentation.niq;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class Netty38NiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(Netty38NiqResponseHeaderInstrumentation.class);

  public Netty38NiqResponseHeaderInstrumentation() {
    super("niq-response-header", "netty", "netty-3.8");
  }

  @Override
  public String instrumentedType() {
    return "datadog.trace.instrumentation.netty38.server.HttpServerResponseTracingHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("writeRequested"),
        Netty38NiqResponseHeaderInstrumentation.class.getName() + "$WriteRequestedAdvice");
  }

  public static class WriteRequestedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectHeader(@Advice.Argument(1) final Object msg) {
      try {
        // Use reflection to handle org.jboss.netty.channel.MessageEvent
        if (msg == null) {
          return;
        }

        // Get message from MessageEvent using reflection
        java.lang.reflect.Method getMessageMethod = msg.getClass().getMethod("getMessage");
        Object message = getMessageMethod.invoke(msg);

        // Check if it's HttpResponse
        if (message == null || !message.getClass().getName().equals("org.jboss.netty.handler.codec.http.HttpResponse")) {
          return;
        }

        AgentSpan span = datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-NETTY38] No active span found");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-NETTY38] Invalid span or trace ID");
          return;
        }

        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        // Use reflection to set header: response.headers().set("current-span-id", traceparent)
        java.lang.reflect.Method headersMethod = message.getClass().getMethod("headers");
        Object headers = headersMethod.invoke(message);
        java.lang.reflect.Method setMethod = headers.getClass().getMethod("set", String.class, Object.class);
        setMethod.invoke(headers, "current-span-id", traceparent);

        log.debug("[NIQ-NETTY38] Successfully injected current-span-id header: {}", traceparent);
      } catch (Exception e) {
        log.warn("[NIQ-NETTY38] Error injecting header", e);
      }
    }
  }
}
