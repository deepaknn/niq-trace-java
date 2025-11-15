# gRPC Message Payload Handling - Comprehensive Analysis
## niq-trace-java Codebase Exploration

---

## EXECUTIVE SUMMARY

The niq-trace-java codebase contains **sophisticated gRPC instrumentation** that already captures and processes message payloads at key points. The framework:

1. **Intercepts ALL gRPC messages** on both client and server sides
2. **Provides gateway callbacks** for custom message processing
3. **Already implements IAST-level** deep message tainting
4. **Uses protobuf GeneratedMessage objects** with full field accessibility
5. **Supports custom callback registration** for payload capture

### Best Interception Point for Payload Capture:
**`TracingServerInterceptor.TracingServerCallListener.onMessage(ReqT message)`**
- **Location**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java:144-165`
- **State**: Message fully deserialized to Java object
- **Access**: Direct method parameter `message`
- **Gateway Integration**: Already calls `callIGCallbackGrpcMessage()`
- **Example Fields**: Direct access via protobuf generated getters

---

## PART 1: HOW gRPC MESSAGES ARE INTERCEPTED

### Server-Side Interception (Complete)

**Entry Point**: `ServerInterceptor.interceptCall()`
```java
public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
    final ServerCall<ReqT, RespT> call,
    final Metadata headers,
    final ServerCallHandler<ReqT, RespT> next)
```

**Key Steps**:
1. **Header Extraction** (Line 59): Metadata object contains all request headers
2. **Span Creation** (Line 70): Creates request span for tracing
3. **Listener Wrapping** (Line 104): Returns `TracingServerCallListener`

**Key Interception Points**:
- `ServerCall.Listener.onMessage(ReqT message)` - **REQUEST MESSAGE CAPTURED HERE**
- `ServerCall.Listener.onHalfClose()` - Client finished sending
- `ServerCall.Listener.onComplete()` - Request completed successfully
- `ServerCall.Listener.onCancel()` - Request cancelled
- `ServerCall.close(Status, Metadata)` - Response status tracked

### Client-Side Interception (Partial)

**Entry Points**:
- `ClientCallImpl.sendMessage()` - **Could capture request here**
- `ClientStreamListenerImpl.messageRead()` - Response received
- `ClientStreamListenerImpl.messagesAvailable()` - Response available

**Current Limitations**:
- Client request payloads not currently captured
- Response payloads available but not extracted
- Both could be enhanced with bytecode advice

---

## PART 2: MESSAGE SERIALIZATION/DESERIALIZATION POINTS

### Serialization Architecture

**Timeline**:
```
User Code
  |
  v
sendMessage(Request request)  <- Application provides Java object
  |
  v
ClientCallImpl (instrumented)  <- sendMessage() hook
  |
  v
Protobuf Marshalling (NOT instrumented)
  | Converts Java object -> byte array via protobuf marshalling
  |
  v
HTTP/2 Transport
  |
  v
gRPC Server receives bytes
  |
  v
Protobuf Deserialization (NOT instrumented)
  | Converts byte array -> Java object via protobuf unmarshalling
  |
  v
TracingServerCallListener.onMessage(Object message) <- **MESSAGE AVAILABLE AS JAVA OBJECT**
```

### Key Finding: Messages are Java Objects, Not Bytes

By the time messages reach listeners:
- **Not bytes**: Already fully deserialized
- **Not strings**: Java protobuf GeneratedMessage objects
- **Full access**: All fields accessible via generated getters
- **Direct usage**: Can call `toByteArray()` to re-serialize if needed

**Example Message Types**:
- `com.google.protobuf.GeneratedMessage`
- `com.google.protobuf.GeneratedMessageV3`
- `com.google.protobuf.GeneratedMessageLite`

---

## PART 3: EXISTING MESSAGE CAPTURE INFRASTRUCTURE

### Gateway Callback System

The codebase implements a **subscription-based callback system** for message processing:

#### Event: `EVENTS.grpcServerRequestMessage()`

**Type**: `BiFunction<RequestContext, Object, Flow<Void>>`

**Triggered From**: `TracingServerInterceptor.callIGCallbackGrpcMessage()`

**Current Handlers**:
1. **APPSEC Gateway** - Application security policies
2. **IAST Handler** - `GrpcRequestMessageHandler` (deep message tainting)
3. **Custom** - Any third-party handlers (extensible)

### Implementation: GrpcRequestMessageHandler (IAST)

```java
@Override
public Flow<Void> apply(final RequestContext ctx, final Object o) {
  final PropagationModule module = InstrumentationBridge.PROPAGATION;
  if (module != null && o != null) {
    final IastContext iastCtx = ctx.getData(RequestContextSlot.IAST);
    final byte source = SourceTypes.GRPC_BODY;
    
    // Deep tainting of all nested objects
    final int tainted = module.taintObjectDeeply(
        iastCtx, o, source, 
        GrpcRequestMessageHandler::visitProtobufArtifact);
    
    if (tainted > 0) {
      IastMetricCollector.add(
          IastMetric.EXECUTED_SOURCE, source, tainted, iastCtx);
    }
  }
  return Flow.ResultFlow.empty();
}
```

**Handles**:
- GeneratedMessage types (custom gRPC messages)
- MapField objects (protobuf maps)
- Nested collections (arrays, iterables, maps)
- Deep field extraction via reflection

### Callback Chain in Action

```
Message received
  |
  v
onMessage(message)
  |
  +---> callIGCallbackGrpcMessage(span, message)
        |
        +---> APPSEC Callbacks
        |     `grpcServerRequestMessage()` handler(s)
        |
        +---> IAST Callbacks
        |     GrpcRequestMessageHandler.apply()
        |       - Taint propagation
        |       - Deep object extraction
        |       - Metrics collection
        |
        +---> Custom Callbacks
              (Could be payload capture, logging, etc.)
```

---

## PART 4: METADATA (HEADERS) HANDLING

### Header Extraction (Server-Side)

**Location**: `TracingServerInterceptor.callIGCallbackHeaders()` (Lines 287-304)

```java
private static void callIGCallbackHeaders(
    CallbackProvider cbp, RequestContext reqCtx, Metadata metadata) {
  TriConsumer<RequestContext, String, String> headerCb = 
      cbp.getCallback(EVENTS.requestHeader());
  
  for (String key : metadata.keys()) {
    if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      Metadata.Key<String> mdKey = 
          Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
      for (String value : metadata.getAll(mdKey)) {
        headerCb.accept(reqCtx, key, value);  // <-- Each header passed
      }
    }
  }
}
```

**Features**:
- Iterates all metadata keys
- Skips binary headers (maintains separation)
- Calls callback for each header with key and value
- Event: `EVENTS.requestHeader()` - type `TriConsumer<RequestContext, String, String>`
- Event: `EVENTS.requestHeaderDone()` - signals completion

### Header Injection (Client-Side)

**Location**: `GrpcInjectAdapter` (Lines 12-19)

```java
@Override
public void set(final Metadata carrier, final String key, final String value) {
  Metadata.Key<String> metadataKey = 
      Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
  if (carrier.containsKey(metadataKey)) {
    carrier.removeAll(metadataKey);
  }
  carrier.put(metadataKey, value);
}
```

**Used in**: `ClientCallImplInstrumentation.Start` (Line 93)
- Injects trace context headers
- Used by: `DECORATE.injectContext(span, headers, SETTER)`
- Enables distributed trace propagation

---

## PART 5: LISTENER/INTERCEPTOR PATTERNS

### Pattern 1: ServerCall Wrapping

```
ServerInterceptor.interceptCall()
    |
    v (creates)
TracingServerCall extends ForwardingServerCall
    |
    | (wraps for method override)
    v
Methods: close(Status status, Metadata trailers)
    - Tracks response status
    - Records error information
    - Finishes span
```

### Pattern 2: ServerCall.Listener Wrapping

```
next.startCall(tracingServerCall, headers)
    |
    v (returns)
ServerCall.Listener<ReqT>
    |
    v (wrapped in)
TracingServerCallListener extends ForwardingServerCallListener
    |
    | (implements)
    +-- onMessage(ReqT message)
    +-- onHalfClose()
    +-- onComplete()
    +-- onCancel()
    +-- onReady()
```

### Pattern 3: Bytecode Instrumentation (Client-Side)

**Uses ByteBuddy Advice pattern**:
```java
public static final class SendMessage {
  @Advice.OnMethodEnter
  public static AgentScope before(@Advice.This ClientCall<?, ?> call) {
    // Access span from InstrumentationContext
    AgentSpan span = InstrumentationContext.get(
        ClientCall.class, AgentSpan.class).get(call);
  }
  
  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void after(@Advice.Enter AgentScope scope) {
    if (null != scope) scope.close();
  }
}
```

**Enhanced for Payload Capture**:
```java
@Advice.OnMethodEnter
public static AgentScope before(
    @Advice.This ClientCall<?, ?> call,
    @Advice.Argument(0) Object message) {  // <-- Access message
  
  if (message instanceof com.google.protobuf.Message) {
    byte[] payload = ((com.google.protobuf.Message) message).toByteArray();
    // Process payload
  }
}
```

---

## PART 6: KEY INTERCEPTION POINTS FOR PAYLOAD CAPTURE

### PRIMARY: Server-Side Request Message

**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java`

**Lines**: 144-165

**Method**: `TracingServerCallListener.onMessage(final ReqT message)`

**Advantages**:
- Message fully deserialized and available
- Direct method parameter (generic type ReqT)
- Gateway callbacks already in place
- No bytecode hacking required
- Can capture synchronously

**How to Access**:
```java
@Override
public void onMessage(final ReqT message) {
  // message is the deserialized protobuf object
  
  if (message instanceof com.google.protobuf.Message) {
    byte[] serialized = ((com.google.protobuf.Message) message).toByteArray();
    String text = message.toString();
    // Access fields
  }
  
  callIGCallbackGrpcMessage(msgSpan, message);  // Already done
  delegate().onMessage(message);
}
```

### SECONDARY: Server-Side Metadata

**File**: `TracingServerInterceptor.java`

**Lines**: 287-304

**Method**: `callIGCallbackHeaders()`

**Message Type**: Headers as key-value pairs

**Already Implemented**: Yes, via `EVENTS.requestHeader()` callback

### TERTIARY: Client-Side Request Message

**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/ClientCallImplInstrumentation.java`

**Lines**: 59, 135-152

**Method**: `ClientCall.sendMessage()` - hook via bytecode

**Current State**: Only activates span, doesn't capture message

**Enhancement**: Add `@Advice.Argument(0)` to capture Object message

```java
@Advice.OnMethodEnter
public static AgentScope before(
    @Advice.This ClientCall<?, ?> call,
    @Advice.Argument(0) Object message) {  // <-- NEW
  
  AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
  if (span != null && message instanceof com.google.protobuf.Message) {
    byte[] payload = ((com.google.protobuf.Message) message).toByteArray();
    // Capture
  }
  return activateSpan(span);
}
```

### QUATERNARY: Client-Side Response Message

**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/ClientStreamListenerImplInstrumentation.java` or `MessagesAvailableInstrumentation.java`

**Current State**: Message span created but payload not captured

**Challenge**: Message not directly available in runnable context

**Solution**: Need to instrument deserialization or wrap listener

---

## PART 7: COMPLETE FILE REFERENCE

### Core Instrumentation Files

| File | Lines | Purpose |
|------|-------|---------|
| TracingServerInterceptor.java | 45-361 | Main server-side gRPC interceptor |
| ClientCallImplInstrumentation.java | 30-189 | Client call instrumentation |
| ClientStreamListenerImplInstrumentation.java | 25-141 | Client response handling |
| MessagesAvailableInstrumentation.java | 26-92 | Client message span creation |
| GrpcServerBuilderInstrumentation.java | 24-103 | Server builder injection |
| GrpcClientDecorator.java | 29-126 | Client-side span decoration |
| GrpcServerDecorator.java | 23-123 | Server-side span decoration |
| GrpcExtractAdapter.java | 6-21 | Header extraction from metadata |
| GrpcInjectAdapter.java | 8-20 | Header injection into metadata |
| GrpcRequestMessageHandler.java | 16-59 | IAST message processing |
| QueuedCommandInstrumentation.java | 26-75+ | Async command queueing |
| MethodHandlersInstrumentation.java | 19-86 | gRPC method handler instrumentation |

### Supporting Files

| File | Purpose |
|------|---------|
| `/home/user/niq-trace-java/internal-api/src/main/java/datadog/trace/api/gateway/Events.java` | Event type definitions including `grpcServerRequestMessage()` |
| `/home/user/niq-trace-java/dd-java-agent/appsec/src/main/java/com/datadog/appsec/gateway/GatewayBridge.java` | Gateway callback registration |
| `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/test/groovy/GrpcTest.groovy` | Comprehensive test with callback examples |

---

## PART 8: HOW TO IMPLEMENT PAYLOAD CAPTURE

### Option 1: Register Custom Callback (EASIEST)

```java
// In gateway initialization
CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.CUSTOM);
cbp.registerCallback(
    EVENTS.grpcServerRequestMessage(),
    new GrpcPayloadCaptureHandler());

public class GrpcPayloadCaptureHandler 
    implements BiFunction<RequestContext, Object, Flow<Void>> {
  
  @Override
  public Flow<Void> apply(RequestContext ctx, Object message) {
    if (message instanceof com.google.protobuf.Message) {
      byte[] payload = ((com.google.protobuf.Message) message).toByteArray();
      // Store or process payload
      PayloadStore.store(ctx.getTraceId(), payload);
    }
    return Flow.ResultFlow.empty();
  }
}
```

### Option 2: Extend TracingServerInterceptor (MOST INTEGRATED)

Subclass `TracingServerInterceptor` and override `onMessage()` to capture before calling delegate.

### Option 3: Custom Bytecode Instrumentation (MOST CONTROL)

Create new InstrumenterModule to hook into `sendMessage()` with `@Advice.Argument(0)` for client-side.

### Option 4: Enhance IAST Handler (INTEGRATED WITH EXISTING)

Extend `GrpcRequestMessageHandler` to also capture serialized payloads to external storage while doing taint propagation.

---

## SUMMARY TABLE: INTERCEPTION CAPABILITIES

| Component | Location | What's Available | Current Use | Enhancement Potential |
|-----------|----------|------------------|-------------|----------------------|
| **ServerCall.Listener.onMessage()** | TracingServerInterceptor:144 | Deserialized message object | Message span + gateway callbacks | Add payload serialization |
| **ServerCall.close()** | TracingServerInterceptor:107 | Status + Metadata | Response status tracking | Add response body if available |
| **ClientCall.sendMessage()** | ClientCallImplInstrumentation:59 | Message object (not captured) | Span lifecycle only | Add @Advice.Argument(0) |
| **ClientStreamListener.messageRead()** | ClientStreamListenerImplInstrumentation:61 | Message received signal | Availability tracking | Wrap listener to capture |
| **Metadata extraction** | TracingServerInterceptor:287 | All headers as string pairs | Gateway callbacks | Already optimal |
| **Metadata injection** | GrpcInjectAdapter:12 | Context propagation headers | Trace distribution | Already optimal |

---

## CRITICAL CODE SNIPPETS

### Access Message in Callback:
```java
// From test (GrpcTest.groovy:102-105)
ig.registerCallback(EVENTS.grpcServerRequestMessage(), { reqCtx, obj ->
  collectedAppSecReqMsgs << obj
  if (obj instanceof com.google.protobuf.Message) {
    byte[] bytes = ((com.google.protobuf.Message) obj).toByteArray()
  }
  Flow.ResultFlow.empty()
} as BiFunction<RequestContext, Object, Flow<Void>>)
```

### Protobuf Message Capabilities:
```java
// Serialize to bytes
byte[] payload = message.toByteArray();

// Get string representation
String text = message.toString();

// Access fields (example: request.getName())
String value = ((example.Helloworld.Request) message).getName();

// Reflection-based access for unknown types
for (Method m : message.getClass().getMethods()) {
  if (m.getName().startsWith("get") && m.getParameterCount() == 0) {
    Object fieldValue = m.invoke(message);
  }
}

// Check if implements protobuf Message
if (message instanceof com.google.protobuf.Message) {
  // Can safely call toByteArray()
}
```

---

## RECOMMENDED NEXT STEPS

1. **For Payload Capture**: Register custom callback handler in `callIGCallbackGrpcMessage()` flow
2. **For Client Requests**: Enhance `ClientCallImplInstrumentation.SendMessage` with `@Advice.Argument(0)`
3. **For Client Responses**: Wrap `ClientStreamListener` to intercept message delivery
4. **For Metadata**: Already working - just register header callback handlers
5. **For Storage**: Leverage existing `RequestContextSlot` pattern to store payloads with request context

---

## CODEBASE STATISTICS

- **Total gRPC instrumentation files**: 12 core files
- **Lines of instrumentation code**: ~1500+ lines
- **Tested scenarios**: 9+ test cases in GrpcTest.groovy
- **Callback event types**: 5+ (requestStarted, requestHeader, grpcServerRequestMessage, etc.)
- **Message capture already implemented**: IAST handler (GrpcRequestMessageHandler)

---

Generated: 2025-11-15
Repository: niq-trace-java
Branch: claude/payload-capture-analysis-01Y5GcYWDdtWQPxJqCj5AVa7

