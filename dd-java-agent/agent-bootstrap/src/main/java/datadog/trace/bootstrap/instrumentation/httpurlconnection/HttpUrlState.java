package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.http.PayloadCapturingInputStream;
import datadog.trace.bootstrap.instrumentation.api.http.PayloadCapturingOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

public class HttpUrlState {
  public static final ContextStore.Factory<HttpUrlState> FACTORY = HttpUrlState::new;

  private volatile AgentSpan span = null;
  private volatile boolean finished = false;

  // PAYLOAD CAPTURE: Stream wrappers for capturing request/response payloads
  private volatile PayloadCapturingOutputStream requestStream = null;
  private volatile PayloadCapturingInputStream responseStream = null;

  public AgentSpan start(final HttpURLConnection connection) {
    span = startSpan(DECORATE.operationName());
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, connection);
      return span;
    }
  }

  public boolean hasSpan() {
    return span != null;
  }

  public boolean isFinished() {
    return finished;
  }

  public void finish() {
    finished = true;
  }

  public void finishSpan(
      final HttpURLConnection connection, final int responseCode, final Throwable throwable) {
    try (final AgentScope scope = activateSpan(span)) {
      if (responseCode > 0) {
        // safe to access response data as 'responseCode' is set
        DECORATE.onResponse(span, connection);
      } else {
        // Ignoring the throwable if we have response code
        // to have consistent behavior with other http clients.
        DECORATE.onError(span, throwable);
      }
      // PAYLOAD CAPTURE: Tag span with captured payloads
      tagCapturedPayloads();
      DECORATE.beforeFinish(span);
      span.finish();
      span = null;
      finished = true;
    }
  }

  public void finishSpan(final HttpURLConnection connection, final int responseCode) {
    /*
     * responseCode field is sometimes not populated.
     * We can't call getResponseCode() due to some unwanted side-effects
     * (e.g. breaks getOutputStream).
     */
    if (responseCode > 0) {
      try (final AgentScope scope = activateSpan(span)) {
        // safe to access response data as 'responseCode' is set
        DECORATE.onResponse(span, connection);
        // PAYLOAD CAPTURE: Tag span with captured payloads
        tagCapturedPayloads();
        DECORATE.beforeFinish(span);
        span.finish();
        span = null;
        finished = true;
      }
    }
  }

  // PAYLOAD CAPTURE: Wrap output stream for request body capture
  public OutputStream wrapRequestStream(OutputStream originalStream) {
    if (!Config.get().isNiqTracerPayloadCaptureEnabled() || originalStream == null) {
      return originalStream;
    }

    int maxSize = Config.get().getNiqTracerMaxPayloadSize();
    requestStream = new PayloadCapturingOutputStream(originalStream, maxSize);
    return requestStream;
  }

  // PAYLOAD CAPTURE: Wrap input stream for response body capture
  public InputStream wrapResponseStream(InputStream originalStream) {
    if (!Config.get().isNiqTracerPayloadCaptureEnabled() || originalStream == null) {
      return originalStream;
    }

    int maxSize = Config.get().getNiqTracerMaxPayloadSize();
    responseStream = new PayloadCapturingInputStream(originalStream, maxSize);
    return responseStream;
  }

  // PAYLOAD CAPTURE: Tag span with captured request/response payloads
  private void tagCapturedPayloads() {
    if (span == null) {
      return;
    }

    try {
      if (requestStream != null) {
        String requestPayload = requestStream.getCapturedPayload();
        if (requestPayload != null && !requestPayload.isEmpty()) {
          span.setTag("http.request.body", requestPayload);
        }
      }

      if (responseStream != null) {
        String responsePayload = responseStream.getCapturedPayload();
        if (responsePayload != null && !responsePayload.isEmpty()) {
          span.setTag("http.response.body", responsePayload);
        }
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
