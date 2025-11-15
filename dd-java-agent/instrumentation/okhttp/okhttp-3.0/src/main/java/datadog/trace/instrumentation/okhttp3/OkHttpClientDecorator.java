package datadog.trace.instrumentation.okhttp3;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.IOException;
import java.net.URI;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public class OkHttpClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence OKHTTP = UTF8BytesString.create("okhttp");
  public static final OkHttpClientDecorator DECORATE = new OkHttpClientDecorator();

  public static final CharSequence OKHTTP_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"okhttp", "okhttp-3"};
  }

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return OKHTTP;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URI url(final Request httpRequest) {
    return httpRequest.url().uri();
  }

  @Override
  protected HttpUrl sourceUrl(final Request httpRequest) {
    return httpRequest.url();
  }

  @Override
  protected int status(final Response httpResponse) {
    return httpResponse.code();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return request.header(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.header(headerName);
  }

  /** Overridden by {@link AppSecInterceptor} */
  @Override
  protected void onHttpClientRequest(AgentSpan span, String url) {
    // do nothing
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final Request request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request != null) {
      captureRequestPayload(span, request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final Response response) {
    super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && response != null) {
      captureResponsePayload(span, response);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, Request request) {
    try {
      RequestBody body = request.body();
      if (body == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();

      // Use Buffer to capture the request body
      Buffer buffer = new Buffer();
      body.writeTo(buffer);

      // Read the payload from buffer
      String payload = buffer.readUtf8();

      if (payload != null && !payload.isEmpty()) {
        // Truncate if needed
        if (payload.length() > maxSize) {
          payload = payload.substring(0, maxSize);
        }
        span.setTag("http.request.body", payload);
      }
    } catch (IOException e) {
      // Silently ignore - don't fail request due to payload capture
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, Response response) {
    try {
      ResponseBody body = response.body();
      if (body == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();

      // Use peekBody to read without consuming the original body
      // peekBody creates a copy of the body up to the specified byte count
      ResponseBody peekedBody = response.peekBody(maxSize);

      if (peekedBody != null) {
        String payload = peekedBody.string();

        if (payload != null && !payload.isEmpty()) {
          // Already limited by peekBody, but truncate if needed
          if (payload.length() > maxSize) {
            payload = payload.substring(0, maxSize);
          }
          span.setTag("http.response.body", payload);
        }

        // Close the peeked body
        peekedBody.close();
      }
    } catch (IOException e) {
      // Silently ignore - don't fail request due to payload capture
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
