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
public final class GrpcNiqResponseHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(GrpcNiqResponseHeaderInstrumentation.class);

  public GrpcNiqResponseHeaderInstrumentation() {
    super("niq-response-header", "grpc", "grpc-server");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.grpc.internal.ServerCallImpl",
      "io.grpc.ServerCall"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("sendHeaders"),
        GrpcNiqResponseHeaderInstrumentation.class.getName() + "$SendHeadersAdvice");
  }

  public static class SendHeadersAdvice {
    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectCurrentSpanId(@Advice.Argument(0) final Object headers) {
      try {
        if (INJECTED.get()) {
          log.trace("[NIQ-GRPC] Header already injected for this response, skipping");
          return;
        }

        AgentSpan span = AgentTracer.activeSpan();
        if (span == null) {
          log.trace("[NIQ-GRPC] No active span found, skipping header injection");
          return;
        }

        DDTraceId traceId = span.getTraceId();
        long spanId = span.getSpanId();

        if (spanId == 0 || traceId == null || traceId == DDTraceId.ZERO) {
          log.trace("[NIQ-GRPC] Invalid span or trace ID, skipping header injection");
          return;
        }

        // Build W3C traceparent format: 00-{traceId}-{spanId}-01~ncsd
        String traceIdHex = traceId.toHexString();
        String spanIdHex = DDSpanId.toHexStringPadded(spanId);
        String traceparent = "00-" + traceIdHex + "-" + spanIdHex + "-01~ncsd";

        // Inject the header using reflection to support multiple grpc versions
        try {
          // io.grpc.Metadata
          Class<?> metadataClass = headers.getClass();
          Class<?> keyClass = Class.forName("io.grpc.Metadata$Key");

          // Get the static method Metadata.Key.of(String name, Metadata.AsciiMarshaller<T> marshaller)
          java.lang.reflect.Method ofMethod = keyClass.getMethod("of", String.class, Class.forName("io.grpc.Metadata$AsciiMarshaller"));

          // Get ASCII_STRING_MARSHALLER
          Object marshaller = keyClass.getField("ASCII_STRING_MARSHALLER").get(null);

          // Create the key for "current-span-id"
          Object key = ofMethod.invoke(null, "current-span-id", marshaller);

          // Put the header value
          java.lang.reflect.Method putMethod = metadataClass.getMethod("put", keyClass, Object.class);
          putMethod.invoke(headers, key, traceparent);

          INJECTED.set(Boolean.TRUE);
          log.debug("[NIQ-GRPC] Successfully injected current-span-id header: {}", traceparent);
        } catch (Exception e) {
          log.warn("[NIQ-GRPC] Error injecting header using reflection", e);
        }
      } catch (Exception e) {
        log.warn("[NIQ-GRPC] Error injecting header", e);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cleanup() {
      INJECTED.remove();
    }
  }
}
