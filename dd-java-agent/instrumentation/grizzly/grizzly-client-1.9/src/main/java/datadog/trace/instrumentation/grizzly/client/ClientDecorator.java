package datadog.trace.instrumentation.grizzly.client;

import com.ning.http.client.Request;
import com.ning.http.client.Response;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class ClientDecorator extends HttpClientDecorator<Request, Response> {

  private static final CharSequence GRIZZLY_HTTP_ASYNC_CLIENT =
      UTF8BytesString.create("grizzly-http-async-client");
  public static final ClientDecorator DECORATE = new ClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly-client", "ning"};
  }

  @Override
  protected CharSequence component() {
    return GRIZZLY_HTTP_ASYNC_CLIENT;
  }

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
  protected String getRequestHeader(Request request, String headerName) {
    return request.getHeaders().getFirstValue(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHeader(headerName);
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

      // Try different ways to get request body
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
