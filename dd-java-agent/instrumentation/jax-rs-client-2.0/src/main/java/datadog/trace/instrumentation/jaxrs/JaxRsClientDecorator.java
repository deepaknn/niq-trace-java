package datadog.trace.instrumentation.jaxrs;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientResponseContext;

public class JaxRsClientDecorator
    extends HttpClientDecorator<ClientRequestContext, ClientResponseContext> {
  public static final CharSequence JAX_RS_CLIENT = UTF8BytesString.create("jax-rs.client");
  public static final JaxRsClientDecorator DECORATE = new JaxRsClientDecorator();

  public static final CharSequence JAX_RS_CLIENT_CALL =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jax-rs", "jaxrs", "jax-rs-client"};
  }

  @Override
  protected CharSequence component() {
    return JAX_RS_CLIENT;
  }

  @Override
  protected String method(final ClientRequestContext httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final ClientRequestContext httpRequest) {
    return httpRequest.getUri();
  }

  @Override
  protected int status(final ClientResponseContext httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(ClientRequestContext request, String headerName) {
    return request.getHeaderString(headerName);
  }

  @Override
  protected String getResponseHeader(ClientResponseContext response, String headerName) {
    return response.getHeaderString(headerName);
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final ClientRequestContext request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request != null) {
      captureRequestPayload(span, request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final ClientResponseContext response) {
    super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && response != null) {
      captureResponsePayload(span, response);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, ClientRequestContext request) {
    try {
      Object entity = request.getEntity();
      if (entity == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      String payload;

      // Handle common entity types
      if (entity instanceof String) {
        payload = (String) entity;
      } else if (entity instanceof byte[]) {
        payload = new String((byte[]) entity, StandardCharsets.UTF_8);
      } else {
        // For other types, use toString()
        payload = entity.toString();
      }

      // Truncate if needed
      if (payload.length() > maxSize) {
        payload = payload.substring(0, maxSize);
      }

      span.setTag("http.request.body", payload);
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }

  private void captureResponsePayload(AgentSpan span, ClientResponseContext response) {
    try {
      InputStream entityStream = response.getEntityStream();
      if (entityStream == null) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxSize, 8192));

      byte[] buffer = new byte[8192];
      int bytesRead;
      int totalRead = 0;

      while ((bytesRead = entityStream.read(buffer)) != -1 && totalRead < maxSize) {
        int toWrite = Math.min(bytesRead, maxSize - totalRead);
        baos.write(buffer, 0, toWrite);
        totalRead += toWrite;
      }

      if (baos.size() > 0) {
        String payload = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        span.setTag("http.response.body", payload);

        // IMPORTANT: Reset the stream so the application can still read it
        // Create a new ByteArrayInputStream with the captured data
        byte[] allData = baos.toByteArray();
        // If we didn't read everything, we need to append the rest
        if (bytesRead != -1) {
          ByteArrayOutputStream fullStream = new ByteArrayOutputStream();
          fullStream.write(allData);
          while ((bytesRead = entityStream.read(buffer)) != -1) {
            fullStream.write(buffer, 0, bytesRead);
          }
          allData = fullStream.toByteArray();
        }
        response.setEntityStream(new ByteArrayInputStream(allData));
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
