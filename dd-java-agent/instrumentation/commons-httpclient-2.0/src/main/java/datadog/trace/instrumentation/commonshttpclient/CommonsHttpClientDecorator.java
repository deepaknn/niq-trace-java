package datadog.trace.instrumentation.commonshttpclient;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.StatusLine;
import org.apache.commons.httpclient.URIException;

public class CommonsHttpClientDecorator extends HttpClientDecorator<HttpMethod, HttpMethod> {
  public static final CharSequence COMMONS_HTTP_CLIENT =
      UTF8BytesString.create("commons-http-client");
  public static final CommonsHttpClientDecorator DECORATE = new CommonsHttpClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"commons-http-client"};
  }

  @Override
  protected CharSequence component() {
    return COMMONS_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpMethod httpMethod) {
    return httpMethod.getName();
  }

  @Override
  protected URI url(final HttpMethod httpMethod) throws URISyntaxException {
    try {
      //  org.apache.commons.httpclient.URI -> java.net.URI
      return new URI(httpMethod.getURI().toString());
    } catch (final URIException e) {
      throw new URISyntaxException("", e.getMessage());
    }
  }

  @Override
  protected HttpMethod sourceUrl(final HttpMethod httpMethod) {
    return httpMethod;
  }

  @Override
  protected int status(final HttpMethod httpMethod) {
    final StatusLine statusLine = httpMethod.getStatusLine();
    return statusLine == null ? 0 : statusLine.getStatusCode();
  }

  @Override
  protected String getRequestHeader(HttpMethod request, String headerName) {
    Header header = request.getRequestHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpMethod response, String headerName) {
    Header header = response.getResponseHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final HttpMethod request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request != null) {
      captureRequestPayload(span, request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final HttpMethod response) {
    super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && response != null) {
      captureResponsePayload(span, response);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, HttpMethod request) {
    try {
      InputStream requestBody = request.getRequestBodyAsStream();
      if (requestBody == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxSize, 8192));

      byte[] buffer = new byte[8192];
      int bytesRead;
      int totalRead = 0;

      while ((bytesRead = requestBody.read(buffer)) != -1 && totalRead < maxSize) {
        int toWrite = Math.min(bytesRead, maxSize - totalRead);
        baos.write(buffer, 0, toWrite);
        totalRead += toWrite;
      }

      if (baos.size() > 0) {
        String payload = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        span.setTag("http.request.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, HttpMethod response) {
    try {
      InputStream responseBody = response.getResponseBodyAsStream();
      if (responseBody == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxSize, 8192));

      byte[] buffer = new byte[8192];
      int bytesRead;
      int totalRead = 0;

      while ((bytesRead = responseBody.read(buffer)) != -1 && totalRead < maxSize) {
        int toWrite = Math.min(bytesRead, maxSize - totalRead);
        baos.write(buffer, 0, toWrite);
        totalRead += toWrite;
      }

      if (baos.size() > 0) {
        String payload = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        span.setTag("http.response.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
