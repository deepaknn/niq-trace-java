package datadog.trace.instrumentation.googlehttpclient;

import static datadog.context.Context.current;
import static datadog.trace.instrumentation.googlehttpclient.HeadersInjectAdapter.SETTER;

import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class GoogleHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  private static final Pattern URL_REPLACEMENT = Pattern.compile("%20");
  public static final CharSequence GOOGLE_HTTP_CLIENT =
      UTF8BytesString.create("google-http-client");
  public static final GoogleHttpClientDecorator DECORATE = new GoogleHttpClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.getRequestMethod();
  }

  @Override
  protected URI url(final HttpRequest httpRequest) throws URISyntaxException {
    // Google uses %20 (space) instead of "+" for spaces in the fragment
    // Add "+" back for consistency with the other http client instrumentations
    final String url = httpRequest.getUrl().build();
    final String fixedUrl = URL_REPLACEMENT.matcher(url).replaceAll("+");
    return URIUtils.safeParse(fixedUrl);
  }

  public AgentSpan prepareSpan(AgentSpan span, HttpRequest request) {
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    DECORATE.injectContext(current().with(span), request, SETTER);
    return span;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getStatusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"google-http-client"};
  }

  @Override
  protected CharSequence component() {
    return GOOGLE_HTTP_CLIENT;
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    return request.getHeaders().getFirstHeaderStringValue(headerName);
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    return response.getHeaders().getFirstHeaderStringValue(headerName);
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final HttpRequest request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request != null) {
      captureRequestPayload(span, request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final HttpResponse response) {
    super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && response != null) {
      captureResponsePayload(span, response);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, HttpRequest request) {
    try {
      HttpContent content = request.getContent();
      if (content == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxSize, 8192));

      // Write content to our buffer
      content.writeTo(baos);

      if (baos.size() > 0) {
        int length = Math.min(baos.size(), maxSize);
        String payload = new String(baos.toByteArray(), 0, length, StandardCharsets.UTF_8);
        span.setTag("http.request.body", payload);
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, HttpResponse response) {
    try {
      InputStream content = response.getContent();
      if (content == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxSize, 8192));

      byte[] buffer = new byte[8192];
      int bytesRead;
      int totalRead = 0;

      while ((bytesRead = content.read(buffer)) != -1 && totalRead < maxSize) {
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
