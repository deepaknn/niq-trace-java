package datadog.trace.instrumentation.playws;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import play.shaded.ahc.org.asynchttpclient.Request;
import play.shaded.ahc.org.asynchttpclient.Response;

public class PlayWSClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence PLAY_WS = UTF8BytesString.create("play-ws");
  public static final PlayWSClientDecorator DECORATE = new PlayWSClientDecorator();
  public static final CharSequence PLAY_WS_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String method(final Request request) {
    return request.getMethod();
  }

  @Override
  protected URI url(final Request request) throws URISyntaxException {
    return request.getUri().toJavaNetURI();
  }

  @Override
  protected int status(final Response response) {
    return response.getStatusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"play-ws"};
  }

  @Override
  protected CharSequence component() {
    return PLAY_WS;
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return request.getHeaders().get(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHeaders().get(headerName);
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
      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      String payload = null;

      // Try different ways to get request body (similar to AsyncHttpClient)
      byte[] byteData = request.getByteData();
      if (byteData != null) {
        payload = new String(byteData, StandardCharsets.UTF_8);
      } else {
        String stringData = request.getStringData();
        if (stringData != null) {
          payload = stringData;
        }
      }

      if (payload != null) {
        // Truncate if needed
        if (payload.length() > maxSize) {
          payload = payload.substring(0, maxSize);
        }
        span.setTag("http.request.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, Response response) {
    try {
      String responseBody = response.getResponseBody();
      if (responseBody != null && !responseBody.isEmpty()) {
        int maxSize = Config.get().getNiqTracerMaxPayloadSize();

        // Truncate if needed
        if (responseBody.length() > maxSize) {
          responseBody = responseBody.substring(0, maxSize);
        }

        span.setTag("http.response.body", responseBody);
      }
    } catch (IOException e) {
      // Silently ignore - don't fail request due to payload capture
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
