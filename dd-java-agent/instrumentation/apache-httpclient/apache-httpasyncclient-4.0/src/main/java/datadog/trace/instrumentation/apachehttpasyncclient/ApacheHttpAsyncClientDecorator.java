package datadog.trace.instrumentation.apachehttpasyncclient;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.util.EntityUtils;

public class ApacheHttpAsyncClientDecorator
    extends HttpClientDecorator<HttpUriRequest, HttpContext> {

  public static final CharSequence APACHE_HTTPASYNCCLIENT =
      UTF8BytesString.create("apache-httpasyncclient");

  public static final ApacheHttpAsyncClientDecorator DECORATE =
      new ApacheHttpAsyncClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"httpasyncclient", "apache-httpasyncclient"};
  }

  @Override
  protected CharSequence component() {
    return APACHE_HTTPASYNCCLIENT;
  }

  @Override
  protected String method(final HttpUriRequest request) {
    return request.getMethod();
  }

  @Override
  protected URI url(final HttpUriRequest request) throws URISyntaxException {
    return request.getURI();
  }

  @Override
  protected URI sourceUrl(final HttpUriRequest request) {
    return request.getURI();
  }

  @Override
  protected int status(final HttpContext context) {
    final Object responseObject = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
    if (responseObject instanceof HttpResponse) {
      final StatusLine statusLine = ((HttpResponse) responseObject).getStatusLine();
      if (statusLine != null) {
        return statusLine.getStatusCode();
      }
    }
    return 0;
  }

  @Override
  protected String getRequestHeader(HttpUriRequest request, String headerName) {
    Header header = request.getFirstHeader(headerName);
    if (header != null) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpContext context, String headerName) {
    final Object responseObject = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
    if (responseObject instanceof HttpResponse) {
      Header header = ((HttpResponse) responseObject).getFirstHeader(headerName);
      if (header != null) {
        return header.getValue();
      }
    }
    return null;
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final HttpUriRequest request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request != null) {
      captureRequestPayload(span, request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final HttpContext context) {
    super.onResponse(span, context);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && context != null) {
      captureResponsePayload(span, context);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, HttpUriRequest request) {
    try {
      // Only certain request types have entities (POST, PUT, PATCH, etc.)
      if (request instanceof HttpEntityEnclosingRequest) {
        HttpEntityEnclosingRequest entityRequest = (HttpEntityEnclosingRequest) request;
        HttpEntity entity = entityRequest.getEntity();

        if (entity != null && entity.isRepeatable()) {
          // Only capture if entity is repeatable (won't consume the stream)
          int maxSize = Config.get().getNiqTracerMaxPayloadSize();
          String payload = EntityUtils.toString(entity, StandardCharsets.UTF_8);

          if (payload != null && !payload.isEmpty()) {
            // Truncate if needed
            if (payload.length() > maxSize) {
              payload = payload.substring(0, maxSize);
            }
            span.setTag("http.request.body", payload);
          }
        }
      }
    } catch (IOException e) {
      // Silently ignore - don't fail request due to payload capture
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, HttpContext context) {
    try {
      final Object responseObject = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
      if (!(responseObject instanceof HttpResponse)) {
        return;
      }

      HttpResponse response = (HttpResponse) responseObject;
      HttpEntity entity = response.getEntity();

      if (entity != null) {
        int maxSize = Config.get().getNiqTracerMaxPayloadSize();

        // Use EntityUtils to safely read the entity
        // Note: This consumes the entity, so we need to be careful
        // EntityUtils.toString will buffer the content
        String payload = EntityUtils.toString(entity, StandardCharsets.UTF_8);

        if (payload != null && !payload.isEmpty()) {
          // Truncate if needed
          if (payload.length() > maxSize) {
            payload = payload.substring(0, maxSize);
          }
          span.setTag("http.response.body", payload);
        }
      }
    } catch (IOException e) {
      // Silently ignore - don't fail request due to payload capture
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
