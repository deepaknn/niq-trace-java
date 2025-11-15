package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;

public class VertxDecorator
    extends HttpServerDecorator<RoutingContext, RoutingContext, HttpServerResponse, Void> {
  static final CharSequence INSTRUMENTATION_NAME = UTF8BytesString.create("vertx.route-handler");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("vertx");

  static final VertxDecorator DECORATE = new VertxDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME.toString()};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Void> getter() {
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServerResponse> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return INSTRUMENTATION_NAME;
  }

  @Override
  protected String method(final RoutingContext routingContext) {
    return routingContext.request().method().name();
  }

  @Override
  protected URIDataAdapter url(final RoutingContext routingContext) {
    return URIDataAdapterBase.fromURI(routingContext.request().uri(), URIDefaultDataAdapter::new);
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final RoutingContext connection,
      final RoutingContext routingContext,
      final Context parentContext) {
    // PAYLOAD CAPTURE: Request Body
    if (Config.get().isNiqTracerPayloadCaptureEnabled() && routingContext != null) {
      captureRequestPayload(span, routingContext);
    }
    return span;
  }

  @Override
  protected String peerHostIP(final RoutingContext routingContext) {
    return routingContext.request().connection().remoteAddress().host();
  }

  @Override
  protected int peerPort(final RoutingContext routingContext) {
    return routingContext.request().connection().remoteAddress().port();
  }

  @Override
  protected int status(final HttpServerResponse httpServerResponse) {
    return httpServerResponse.getStatusCode();
  }

  private void captureRequestPayload(AgentSpan span, RoutingContext routingContext) {
    try {
      Buffer body = routingContext.body();
      if (body == null || body.length() == 0) {
        return;
      }

      int maxSize = Config.get().getNiqTracerMaxPayloadSize();
      int length = Math.min(body.length(), maxSize);

      // Get bytes and convert to string
      byte[] bytes = body.getBytes(0, length);
      String payload = new String(bytes, StandardCharsets.UTF_8);
      span.setTag("http.request.body", payload);
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
