package datadog.trace.instrumentation.jaxrs.v1;

import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class JaxRsClientV1Decorator extends HttpClientDecorator<ClientRequest, ClientResponse> {

  public static final CharSequence JAX_RS_CLIENT = UTF8BytesString.create("jax-rs.client");

  public static final JaxRsClientV1Decorator DECORATE = new JaxRsClientV1Decorator();
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
  protected String method(final ClientRequest httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final ClientRequest httpRequest) {
    return httpRequest.getURI();
  }

  @Override
  protected int status(final ClientResponse clientResponse) {
    return clientResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(ClientRequest request, String headerName) {
    Object headerValue = request.getHeaders().getFirst(headerName);
    if (null != headerValue) {
      return headerValue.toString();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(ClientResponse response, String headerName) {
    return response.getHeaders().getFirst(headerName);
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final ClientRequest request) {
    super.onRequest(span, request);

    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && request != null) {
      captureRequestPayload(span, request);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final ClientResponse response) {
    super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && response != null) {
      captureResponsePayload(span, response);
    }

    return span;
  }

  private void captureRequestPayload(AgentSpan span, ClientRequest request) {
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

  private void captureResponsePayload(AgentSpan span, ClientResponse response) {
    try {
      InputStream entityStream = response.getEntityInputStream();
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
        response.setEntityInputStream(new ByteArrayInputStream(allData));
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
