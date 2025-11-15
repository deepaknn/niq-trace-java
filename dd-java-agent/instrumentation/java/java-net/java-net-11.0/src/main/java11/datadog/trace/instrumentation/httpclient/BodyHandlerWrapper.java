package datadog.trace.instrumentation.httpclient;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public class BodyHandlerWrapper<T> implements BodyHandler<T> {
  private final BodyHandler<T> delegate;
  private final AgentScope.Continuation continuation;

  public BodyHandlerWrapper(BodyHandler<T> delegate, AgentScope.Continuation context) {
    this.delegate = delegate;
    this.continuation = context;
  }

  @Override
  public BodySubscriber<T> apply(ResponseInfo responseInfo) {
    BodySubscriber<T> subscriber = delegate.apply(responseInfo);
    if (subscriber instanceof BodySubscriberWrapper) {
      return subscriber;
    }
    return new BodySubscriberWrapper<>(subscriber, continuation);
  }

  static class BodySubscriberWrapper<T> implements BodySubscriber<T> {
    private final BodySubscriber<T> delegate;
    private final AgentScope.Continuation continuation;

    // PAYLOAD CAPTURE: Response body accumulation
    private final ByteArrayOutputStream capturedPayload;
    private final int maxPayloadSize;
    private int totalCaptured = 0;
    private boolean limitReached = false;

    public BodySubscriberWrapper(BodySubscriber<T> delegate, AgentScope.Continuation continuation) {
      this.delegate = delegate;
      this.continuation = continuation;

      // Initialize payload capture if enabled
      if (Config.get().isNiqTracerPayloadCaptureEnabled()) {
        this.maxPayloadSize = Config.get().getNiqTracerMaxPayloadSize();
        this.capturedPayload = new ByteArrayOutputStream(Math.min(maxPayloadSize, 8192));
      } else {
        this.maxPayloadSize = 0;
        this.capturedPayload = null;
      }
    }

    public BodySubscriber<T> getDelegate() {
      return delegate;
    }

    @Override
    public CompletionStage<T> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      // PAYLOAD CAPTURE: Capture ByteBuffer chunks transparently
      if (capturedPayload != null && !limitReached && item != null) {
        for (ByteBuffer buffer : item) {
          if (limitReached) {
            break;
          }

          // Get readable bytes from the buffer without consuming it
          int position = buffer.position();
          int remaining = buffer.remaining();

          if (remaining > 0) {
            int toCapture = Math.min(remaining, maxPayloadSize - totalCaptured);
            if (toCapture > 0) {
              byte[] chunk = new byte[toCapture];
              // Read from buffer without changing its position
              buffer.get(chunk, 0, toCapture);
              // Reset position so the delegate gets the same data
              buffer.position(position);

              capturedPayload.write(chunk, 0, toCapture);
              totalCaptured += toCapture;

              if (totalCaptured >= maxPayloadSize) {
                limitReached = true;
              }
            }
          }
        }
      }

      try (AgentScope ignore = continuation.activate()) {
        delegate.onNext(item);
      }
    }

    @Override
    public void onError(Throwable throwable) {
      try (AgentScope ignore = continuation.activate()) {
        delegate.onError(throwable);
      }
    }

    @Override
    public void onComplete() {
      // PAYLOAD CAPTURE: Tag span with captured response body
      if (capturedPayload != null && totalCaptured > 0) {
        try {
          AgentSpan span = AgentTracer.activeSpan();
          if (span != null) {
            String payload = new String(capturedPayload.toByteArray(), StandardCharsets.UTF_8);
            span.setTag("http.response.body", payload);
          }
        } catch (Exception e) {
          // Silently ignore - don't fail request due to payload capture
        }
      }

      try (AgentScope ignore = continuation.activate()) {
        delegate.onComplete();
      }
    }
  }
}
