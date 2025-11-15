package datadog.trace.instrumentation.apachehttpclient5;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;

public class ApacheHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {

  public static final CharSequence APACHE_HTTP_CLIENT =
      UTF8BytesString.create("apache-httpclient5");
  public static final ApacheHttpClientDecorator DECORATE = new ApacheHttpClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {
      "httpclient5",
      "apache-httpclient5",
      "apache-http-client5",
      "httpclient",
      "apache-httpclient",
      "apache-http-client"
    };
  }

  @Override
  protected CharSequence component() {
    return APACHE_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final HttpRequest request) throws URISyntaxException {
    return request.getUri();
  }

  @Override
  protected HttpRequest sourceUrl(final HttpRequest request) {
    return request;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getCode();
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    Header header = request.getFirstHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    Header header = response.getFirstHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final HttpRequest request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request instanceof ClassicHttpRequest) {
      captureRequestPayload(span, (ClassicHttpRequest) request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final HttpResponse response) {
    super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && response instanceof ClassicHttpResponse) {
      captureResponsePayload(span, (ClassicHttpResponse) response);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, ClassicHttpRequest request) {
    try {
      HttpEntity entity = request.getEntity();
      if (entity == null || !entity.isRepeatable()) {
        // Skip if entity is null or not repeatable (avoid consuming the stream)
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxSize, 8192));

      try (InputStream content = entity.getContent()) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        int totalRead = 0;

        while ((bytesRead = content.read(buffer)) != -1 && totalRead < maxSize) {
          int toWrite = Math.min(bytesRead, maxSize - totalRead);
          baos.write(buffer, 0, toWrite);
          totalRead += toWrite;
        }
      }

      if (baos.size() > 0) {
        String payload = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        span.setTag("http.request.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, ClassicHttpResponse response) {
    try {
      HttpEntity entity = response.getEntity();
      if (entity == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();

      // Use EntityUtils to safely consume and convert the entity
      // This works for both repeatable and non-repeatable entities
      byte[] content = EntityUtils.toByteArray(entity);

      if (content != null && content.length > 0) {
        int length = Math.min(content.length, maxSize);
        String payload = new String(content, 0, length, StandardCharsets.UTF_8);
        span.setTag("http.response.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
