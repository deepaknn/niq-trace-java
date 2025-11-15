# Payload Capture Framework Status

**Last Updated:** 2025-11-15
**Branch:** `claude/payload-capture-analysis-01Y5GcYWDdtWQPxJqCj5AVa7`

---

## Legend

- ✅ **Implemented** - Fully functional payload capture
- ❌ **Not Implemented** - No payload capture
- ⚠️ **Partial** - Only request OR response (not both)
- 🔴 **Complex** - Technically challenging, needs review
- 🟡 **Deferred** - Held for discussion/review
- ⭐ **Low Complexity** - Easy to implement
- ⭐⭐ **Medium Complexity** - Moderate effort
- ⭐⭐⭐ **High Complexity** - Significant effort/risk

---

## Client-Side Frameworks

| Framework | Request | Response | Status | Complexity | Notes |
|-----------|---------|----------|--------|------------|-------|
| **OkHttp 3.0** | ✅ | ✅ | Implemented | ⭐ | Uses Buffer.clone(), full support |
| **OkHttp 2.2** | ✅ | ✅ | Implemented | ⭐ | Same as 3.0, different package |
| **Apache HttpClient 4.x** | ✅ | ✅ | Implemented | ⭐ | Repeatable entities only for request |
| **Apache HttpClient 5.x** | ✅ | ✅ | Implemented | ⭐ | Same pattern as 4.x |
| **Google HTTP Client** | ✅ | ✅ | Implemented | ⭐ | HttpContent.writeTo() for request |
| **Commons HttpClient 2.0** | ✅ | ✅ | Implemented | ⭐ | Legacy, full support |
| **Java 11 HttpClient** | ❌ | ✅ | Partial | ⭐⭐⭐ | Response only - request uses reactive Flow.Publisher |
| **Java URLConnection** | ❌ | ❌ | Not Started | ⭐⭐ | Stream wrapping needed |
| **Jetty HTTP Client** | ❌ | ❌ | Not Started | ⭐⭐ | Uses Request.Content and Response.Listener |
| **AsyncHttpClient (Ning/AHC)** | ❌ | ❌ | Not Started | ⭐⭐ | Uses AsyncHandler callbacks |
| **Play-WS Client** | ❌ | ❌ | Not Started | ⭐⭐ | Scala/Java wrapper over AsyncHttpClient |
| **Spring WebClient (WebFlux)** | ❌ | ❌ | Not Started | ⭐⭐⭐ | Reactive (Mono/Flux), complex |
| **Apache HttpAsyncClient** | ❌ | ❌ | Not Started | ⭐⭐ | Async callbacks |
| **JAX-RS Client (Jersey)** | ❌ | ❌ | Not Started | ⭐⭐ | Entity-based |
| **JAX-RS Client (RESTEasy)** | ❌ | ❌ | Not Started | ⭐⭐ | Entity-based |
| **JAX-RS Client (CXF)** | ❌ | ❌ | Not Started | ⭐⭐ | Entity-based |
| **gRPC Client** | ❌ | ❌ | Not Started | ⭐⭐⭐ | Complex - needs stream instrumentation |

### Client Framework Summary
- **Implemented:** 7 frameworks (6 full, 1 partial)
- **Pending:** 10 frameworks
- **Request Capture:** 6/17 (35%)
- **Response Capture:** 7/17 (41%)

---

## Server-Side Frameworks

| Framework | Request | Response | Status | Complexity | Notes |
|-----------|---------|----------|--------|------------|-------|
| **gRPC Server** | ✅ | ❌ | Partial | ⭐ | Request only - response needs onSendMessage hook |
| **Vert.x Web 3.4** | ✅ | ❌ | Partial | ⭐⭐ | Request only - response needs write() interception |
| **Vert.x Web 3.9** | ✅ | ❌ | Partial | ⭐⭐ | Same as 3.4 |
| **Vert.x Web 4.0** | ✅ | ❌ | Partial | ⭐⭐ | Same as 3.4 |
| **Vert.x Web 5.0** | ✅ | ❌ | Partial | ⭐⭐ | Same as 3.4 |
| **Servlet 2.5** | ❌ | ❌ | Deferred 🟡 | ⭐⭐⭐ | Stream wrapping, AppSec coordination needed |
| **Servlet 3.0** | ❌ | ❌ | Deferred 🟡 | ⭐⭐⭐ | Stream wrapping, AppSec coordination needed |
| **Servlet 3.1** | ❌ | ❌ | Deferred 🟡 | ⭐⭐⭐ | Async support adds complexity |
| **Servlet 5.0 (Jakarta)** | ❌ | ❌ | Deferred 🟡 | ⭐⭐⭐ | Jakarta namespace, async support |
| **Netty HTTP Server** | ❌ | ❌ | Deferred 🟡 | ⭐⭐⭐ | ByteBuf management, performance critical |
| **Undertow Server** | ❌ | ❌ | Not Started | ⭐⭐ | Uses Channel API |
| **Grizzly HTTP Server** | ❌ | ❌ | Not Started | ⭐⭐ | Uses Buffer/Content classes |
| **Spring WebFlux** | ❌ | ❌ | Not Started | ⭐⭐⭐ | Reactive (Mono/Flux), complex |
| **Jetty Server** | ❌ | ❌ | Not Started | ⭐⭐ | Via Servlet or direct handler |
| **Tomcat** | ❌ | ❌ | Not Started | ⭐⭐ | Via Servlet instrumentation |
| **Akka HTTP** | ❌ | ❌ | Not Started | ⭐⭐⭐ | Scala-based, streaming |
| **Pekko HTTP** | ❌ | ❌ | Not Started | ⭐⭐⭐ | Akka HTTP fork |
| **Play Framework** | ❌ | ❌ | Not Started | ⭐⭐ | Body parsers |

### Server Framework Summary
- **Implemented:** 5 frameworks (all partial - request only)
- **Pending:** 13 frameworks
- **Request Capture:** 5/18 (28%)
- **Response Capture:** 0/18 (0%)

---

## Overall Summary

### Implementation Progress
- **Total Frameworks:** 35 (17 clients + 18 servers)
- **Fully Implemented:** 6 clients
- **Partially Implemented:** 1 client + 5 servers
- **Not Started:** 10 clients + 13 servers
- **Deferred (needs review):** 4 servers

### By Capability
- **Request Capture:** 11/35 (31%)
- **Response Capture:** 7/35 (20%)
- **Both Request & Response:** 6/35 (17%)

---

## Priority Categorization

### ✅ Phase 1: Completed (Low Complexity)
All implementations completed successfully.

1. ✅ OkHttp 3.0 - Request + Response
2. ✅ OkHttp 2.2 - Request + Response
3. ✅ Apache HttpClient 4.x - Request + Response
4. ✅ Apache HttpClient 5.x - Request + Response
5. ✅ Google HTTP Client - Request + Response
6. ✅ Commons HttpClient 2.0 - Request + Response
7. ✅ Java 11 HttpClient - Response only (request too complex)
8. ✅ gRPC Server - Request only
9. ✅ Vert.x Web (all versions) - Request only

---

### 🔵 Phase 2: Quick Wins (Medium Complexity)

#### Client Frameworks
1. **Java URLConnection** ⭐⭐
   - Wrap HttpURLConnection input/output streams
   - Request: wrap getOutputStream()
   - Response: wrap getInputStream()
   - **Effort:** 2-3 hours

2. **Jetty HTTP Client** ⭐⭐
   - Override Request.Content and Response.Listener
   - Request: intercept Request.Content
   - Response: hook into Response.Listener callbacks
   - **Effort:** 3-4 hours

3. **AsyncHttpClient** ⭐⭐
   - Wrap AsyncHandler callbacks
   - Request: capture from RequestBuilder
   - Response: capture in AsyncHandler.onBodyPartReceived()
   - **Effort:** 3-4 hours

4. **Play-WS Client** ⭐⭐
   - Wraps AsyncHttpClient, similar approach
   - Request: WSRequest.setBody()
   - Response: WSResponse.getBody()
   - **Effort:** 2-3 hours

#### Server Frameworks
5. **Undertow Server** ⭐⭐
   - Use Channel API and StreamSinkChannel/StreamSourceChannel
   - Request: wrap HttpServerExchange.getRequestChannel()
   - Response: wrap HttpServerExchange.getResponseChannel()
   - **Effort:** 3-4 hours

6. **Grizzly HTTP Server** ⭐⭐
   - Intercept Request.getInputBuffer() and Response.getOutputBuffer()
   - Request: wrap Buffer reading
   - Response: wrap Buffer writing
   - **Effort:** 3-4 hours

---

### 🟡 Phase 3: Deferred (Needs Review/Discussion)

1. **Servlet (all versions)** 🔴 ⭐⭐⭐
   - **Challenge:** Stream wrapping before application access
   - **Challenge:** Coordination with existing AppSec instrumentation
   - **Challenge:** Async servlet support
   - **Effort:** 6-8 hours
   - **Status:** HOLD - needs AppSec team review

2. **Netty HTTP Server** 🔴 ⭐⭐⭐
   - **Challenge:** ByteBuf memory management
   - **Challenge:** Performance-critical path
   - **Challenge:** Multiple codec layers
   - **Effort:** 6-8 hours
   - **Status:** HOLD - needs performance review

3. **gRPC Client** 🔴 ⭐⭐⭐
   - **Challenge:** Complex internal stream handling
   - **Challenge:** No easy access to typed messages
   - **Effort:** 6-8 hours
   - **Status:** HOLD - needs investigation

---

### 🔴 Phase 4: Complex/High Risk (High Complexity)

1. **Spring WebClient (WebFlux)** ⭐⭐⭐
   - Reactive streams (Mono/Flux)
   - Complex Publisher/Subscriber chain
   - **Effort:** 8-10 hours

2. **Spring WebFlux Server** ⭐⭐⭐
   - Reactive request/response bodies
   - DataBuffer management
   - **Effort:** 8-10 hours

3. **Akka HTTP** ⭐⭐⭐
   - Scala-based streaming
   - Akka Streams complexity
   - **Effort:** 10-12 hours

4. **Pekko HTTP** ⭐⭐⭐
   - Fork of Akka HTTP
   - Same complexity
   - **Effort:** 10-12 hours

5. **JAX-RS Clients (Jersey/RESTEasy/CXF)** ⭐⭐
   - Entity providers
   - Multiple implementations
   - **Effort:** 4-6 hours each

---

## Response Capture Gaps

### Server-Side Response Capture Issues

Most server frameworks only have **request capture** implemented. Response capture is missing because:

1. **gRPC Server**
   - Need to intercept `ServerCall.sendMessage()` callbacks
   - Response streaming makes this complex
   - Would need similar approach to request capture

2. **Vert.x Web**
   - Need to intercept `HttpServerResponse.write()` and `end()` methods
   - Multiple code paths for response writing
   - Buffer accumulation needed across multiple writes

3. **Servlet**
   - Need to wrap `ServletOutputStream` in `getOutputStream()`
   - Must wrap BEFORE application accesses it
   - Response committed state handling

4. **Netty**
   - Need to intercept outbound `HttpContent` messages
   - ByteBuf memory management
   - Performance concerns

---

## Recommended Next Steps

### Short Term (Next Implementation Phase)
1. **Java URLConnection** - Quick win, widely used
2. **Jetty HTTP Client** - Popular in microservices
3. **Undertow Server** - Common in WildFly/Quarkus
4. **gRPC Server Response** - Complete existing implementation
5. **Vert.x Web Response** - Complete existing implementation

### Medium Term (After Review)
1. **Servlet (after AppSec discussion)** - Critical for broad coverage
2. **JAX-RS Clients** - Common in enterprise
3. **AsyncHttpClient** - Used by Play and others

### Long Term
1. **Spring WebFlux** (both client/server) - Growing adoption
2. **Netty Server** (after performance review)
3. **gRPC Client** (after investigation)
4. **Akka/Pekko HTTP** - Niche but important for Scala

---

## Missing Coverage Analysis

### High-Impact Missing Coverage
1. **Servlet** (all versions) - Used by 70%+ of Java web apps
2. **Spring WebFlux** - Growing rapidly in microservices
3. **gRPC Client** - Increasingly common in microservices

### Medium-Impact Missing Coverage
1. **Java URLConnection** - Built-in, widely used
2. **JAX-RS Clients** - Common in enterprise REST APIs
3. **Undertow Server** - WildFly, Quarkus

### Low-Impact Missing Coverage
1. **Akka/Pekko HTTP** - Scala-specific
2. **Grizzly** - Less common
3. **AsyncHttpClient** - Mostly replaced by newer clients

---

## Configuration

All implementations respect these settings:

```properties
# Enable payload capture (default: false)
niq.tracer.payload.capture=true

# Maximum payload size in bytes (default: 8192 = 8KB)
niq.tracer.max.payload.size=8192
```

## Span Tags

### HTTP Client/Server
- `http.request.body` - Request payload (truncated to max size)
- `http.response.body` - Response payload (truncated to max size)

### gRPC
- `grpc.request.body` - gRPC request message (JSON-formatted protobuf)
- `grpc.response.body` - gRPC response message (JSON-formatted protobuf)

---

**End of Status Report**
