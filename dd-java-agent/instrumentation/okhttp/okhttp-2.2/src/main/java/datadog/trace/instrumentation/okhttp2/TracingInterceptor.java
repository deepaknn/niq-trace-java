package datadog.trace.instrumentation.okhttp2;

import static datadog.context.Context.current;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator.OKHTTP_REQUEST;
import static datadog.trace.instrumentation.okhttp2.RequestBuilderInjectAdapter.SETTER;

import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okio.Buffer;
import okio.BufferedSource;

public class TracingInterceptor implements Interceptor {
  @Override
  public Response intercept(final Chain chain) throws IOException {
    final AgentSpan span = startSpan("okhttp", OKHTTP_REQUEST);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, chain.request());

      final Request.Builder requestBuilder = chain.request().newBuilder();
      DECORATE.injectContext(current(), requestBuilder, SETTER);

      Request request = requestBuilder.build();

      // PAYLOAD CAPTURE: Request Body
      captureRequestPayload(span, request);

      final Response response;
      try {
        response = chain.proceed(request);
      } catch (final Exception e) {
        DECORATE.onError(span, e);
        throw e;
      }

      DECORATE.onResponse(span, response);

      // PAYLOAD CAPTURE: Response Body
      captureResponsePayload(span, response);

      DECORATE.beforeFinish(span);
      return response;
    } finally {
      span.finish();
    }
  }

  private void captureRequestPayload(AgentSpan span, Request request) {
    if (!Config.get().isNiqTracerPayloadCaptureEnabled()) {
      return;
    }

    RequestBody requestBody = request.body();
    if (requestBody == null) {
      return;
    }

    try {
      Buffer buffer = new Buffer();
      requestBody.writeTo(buffer);

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      long size = Math.min(buffer.size(), maxSize);

      if (size > 0) {
        String payload = buffer.readString(size, StandardCharsets.UTF_8);
        span.setTag("http.request.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, Response response) {
    if (!Config.get().isNiqTracerPayloadCaptureEnabled()) {
      return;
    }

    ResponseBody responseBody = response.body();
    if (responseBody == null) {
      return;
    }

    try {
      BufferedSource source = responseBody.source();
      source.request(Long.MAX_VALUE); // Buffer entire body

      Buffer buffer = source.buffer();
      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      long size = Math.min(buffer.size(), maxSize);

      if (size > 0) {
        // Clone to avoid consuming the original buffer
        String payload = buffer.clone().readString(size, StandardCharsets.UTF_8);
        span.setTag("http.response.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
