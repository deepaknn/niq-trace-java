package datadog.trace.instrumentation.servlet3;

import datadog.context.Context;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class Servlet3Decorator
    extends HttpServerDecorator<
        HttpServletRequest, HttpServletRequest, HttpServletResponse, HttpServletRequest> {
  public static final CharSequence JAVA_WEB_SERVLET = UTF8BytesString.create("java-web-servlet");

  public static final Servlet3Decorator DECORATE = new Servlet3Decorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  // PAYLOAD CAPTURE: Attribute names for storing captured payloads
  public static final String DD_REQUEST_PAYLOAD_ATTRIBUTE = "datadog.request.payload";
  public static final String DD_RESPONSE_PAYLOAD_ATTRIBUTE = "datadog.response.payload";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-3"};
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletRequest> getter() {
    return HttpServletExtractAdapter.Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletResponse> responseGetter() {
    return HttpServletExtractAdapter.Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return SERVLET_REQUEST;
  }

  @Override
  protected String method(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest httpServletRequest) {
    return new ServletRequestURIAdapter(httpServletRequest);
  }

  @Override
  protected String peerHostIP(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemoteAddr();
  }

  @Override
  protected int peerPort(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemotePort();
  }

  @Override
  protected int status(final HttpServletResponse httpServletResponse) {
    return httpServletResponse.getStatus();
  }

  @Override
  protected String requestedSessionId(final HttpServletRequest request) {
    return request.getRequestedSessionId();
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final HttpServletRequest connection,
      final HttpServletRequest request,
      final Context parentContext) {
    assert span != null;
    ClassloaderConfigurationOverrides.maybeEnrichSpan(span);
    if (request != null) {
      String contextPath = request.getContextPath();
      String servletPath = request.getServletPath();

      span.setTag("servlet.context", contextPath);
      span.setTag("servlet.path", servletPath);

      // Used by AsyncContextInstrumentation because the context path may be reset
      // (eg by jetty) by the time the async context is dispatched.
      request.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
      request.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);

      // PAYLOAD CAPTURE: Capture request data
      // For servlets, we capture what's easily available without consuming streams:
      // - Query parameters from GET requests
      // - Form parameters from POST (if already parsed by container)
      // Full request body capture requires stream wrapping (deferred due to AppSec coordination)
      if (Config.get().isNiqTracerPayloadCaptureEnabled()) {
        captureRequestData(span, request);
      }
    }
    return super.onRequest(span, connection, request, parentContext);
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final HttpServletResponse response) {
    AgentSpan result = super.onResponse(span, response);

    // PAYLOAD CAPTURE: Response payload capture for servlets
    // Note: Full servlet payload capture requires stream wrapping instrumentation
    // which must coordinate with existing AppSec/IAST infrastructure.
    // The DD_REQUEST_PAYLOAD_ATTRIBUTE and DD_RESPONSE_PAYLOAD_ATTRIBUTE
    // provide hooks for future stream wrapper implementation.
    //
    // Implementation approach:
    // 1. Wrap ServletInputStream in getInputStream() to capture request body
    // 2. Wrap ServletOutputStream in getOutputStream() to capture response body
    // 3. Store captured data in request attributes
    // 4. Extract from attributes here in onRequest()/onResponse()
    //
    // This requires careful coordination to avoid:
    // - Double-wrapping with existing AppSec/RUM wrappers
    // - Consuming streams before application code
    // - Interfering with async servlet processing

    return result;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable instanceof ServletException && throwable.getCause() != null) {
      super.onError(span, throwable.getCause());
    } else {
      super.onError(span, throwable);
    }
    return span;
  }

  // PAYLOAD CAPTURE: Capture request parameters without consuming input stream
  private void captureRequestData(AgentSpan span, HttpServletRequest request) {
    try {
      String queryString = request.getQueryString();

      // For GET requests, query string is the "payload"
      if ("GET".equalsIgnoreCase(request.getMethod()) && queryString != null && !queryString.isEmpty()) {
        int maxSize = Config.get().getNiqTracerMaxPayloadSize();
        String payload = queryString;
        if (payload.length() > maxSize) {
          payload = payload.substring(0, maxSize);
        }
        span.setTag("http.request.body", payload);
      }
      // For POST/PUT with form data, capture parameters if already parsed
      // Note: Calling getParameter() may consume the input stream on some containers
      // So we only do this for form-encoded content
      else if (("POST".equalsIgnoreCase(request.getMethod()) || "PUT".equalsIgnoreCase(request.getMethod()))) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
          // Parameters are safe to read for form-encoded data
          java.util.Map<String, String[]> paramMap = request.getParameterMap();
          if (paramMap != null && !paramMap.isEmpty()) {
            StringBuilder payload = new StringBuilder();
            int maxSize = Config.get().getNiqTracerMaxPayloadSize();

            for (java.util.Map.Entry<String, String[]> entry : paramMap.entrySet()) {
              String key = entry.getKey();
              String[] values = entry.getValue();
              if (values != null) {
                for (String value : values) {
                  if (payload.length() > 0) {
                    payload.append("&");
                  }
                  payload.append(key).append("=").append(value != null ? value : "");

                  if (payload.length() >= maxSize) {
                    break;
                  }
                }
              }
              if (payload.length() >= maxSize) {
                break;
              }
            }

            String result = payload.toString();
            if (result.length() > maxSize) {
              result = result.substring(0, maxSize);
            }
            if (!result.isEmpty()) {
              span.setTag("http.request.body", result);
            }
          }
        }
        // For JSON/XML and other content types, we would need stream wrappers
        // This is deferred due to complexity with existing AppSec infrastructure
      }
    } catch (Exception e) {
      // Silently ignore - don't fail request due to payload capture
    }
  }
}
