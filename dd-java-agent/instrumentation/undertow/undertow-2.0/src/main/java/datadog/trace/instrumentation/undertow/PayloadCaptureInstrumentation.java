package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.REQUEST_STREAM_KEY;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.RESPONSE_STREAM_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import io.undertow.server.HttpServerExchange;
import java.io.InputStream;
import java.io.OutputStream;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class PayloadCaptureInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PayloadCaptureInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.HttpServerExchange";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Wrap getInputStream() to capture request payload
    transformer.applyAdvice(
        isMethod().and(named("getInputStream")).and(isPublic()).and(takesArguments(0)),
        getClass().getName() + "$GetInputStreamAdvice");

    // Wrap getOutputStream() to capture response payload
    transformer.applyAdvice(
        isMethod().and(named("getOutputStream")).and(isPublic()).and(takesArguments(0)),
        getClass().getName() + "$GetOutputStreamAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.bootstrap.instrumentation.decorator.http.PayloadCapturingInputStream",
      "datadog.trace.bootstrap.instrumentation.decorator.http.PayloadCapturingOutputStream",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response",
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowBlockResponseFunction"
    };
  }

  public static class GetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.This HttpServerExchange exchange,
        @Advice.Return(readOnly = false) InputStream inputStream) {

      if (!Config.get().isNiqTracerPayloadCaptureEnabled() || inputStream == null) {
        return;
      }

      // Check if already wrapped
      if (exchange.getAttachment(REQUEST_STREAM_KEY) != null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      datadog.trace.bootstrap.instrumentation.decorator.http.PayloadCapturingInputStream
          capturingStream =
              new datadog.trace.bootstrap.instrumentation.decorator.http.PayloadCapturingInputStream(
                  inputStream, maxSize);

      exchange.putAttachment(REQUEST_STREAM_KEY, capturingStream);
      inputStream = capturingStream;
    }
  }

  public static class GetOutputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.This HttpServerExchange exchange,
        @Advice.Return(readOnly = false) OutputStream outputStream) {

      if (!Config.get().isNiqTracerPayloadCaptureEnabled() || outputStream == null) {
        return;
      }

      // Check if already wrapped
      if (exchange.getAttachment(RESPONSE_STREAM_KEY) != null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      datadog.trace.bootstrap.instrumentation.decorator.http.PayloadCapturingOutputStream
          capturingStream =
              new datadog.trace.bootstrap.instrumentation.decorator.http.PayloadCapturingOutputStream(
                  outputStream, maxSize);

      exchange.putAttachment(RESPONSE_STREAM_KEY, capturingStream);
      outputStream = capturingStream;
    }
  }
}
