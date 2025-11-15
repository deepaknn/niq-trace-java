# gRPC Message Payload Handling in niq-trace-java Codebase

## Overview
This codebase contains comprehensive gRPC instrumentation that intercepts both client and server calls, with existing infrastructure for message payload capture and processing.

## 1. KEY INSTRUMENTATION FILES

### Server-Side Instrumentation
- `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java`
  - Main ServerInterceptor implementation for gRPC server
  - Wraps ServerCall and ServerCall.Listener to intercept messages
  - Calls gateway callbacks for message processing

- `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/GrpcServerBuilderInstrumentation.java`
  - Automatically registers TracingServerInterceptor during server.build()
  - Intercepts ServerBuilder.build() method to inject the interceptor

- `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/GrpcServerDecorator.java`
  - Decorator for server-side span creation and status handling
  - Handles error status codes and gRPC-specific error types

### Client-Side Instrumentation
- `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/ClientCallImplInstrumentation.java`
  - Instruments io.grpc.internal.ClientCallImpl
  - Hooks into sendMessage() method for request interception
  - Manages span lifecycle for client calls

- `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/ClientStreamListenerImplInstrumentation.java`
  - Instruments ClientCallImpl.ClientStreamListenerImpl
  - Hooks into messageRead() and messagesAvailable() for response handling
  - Captures response headers and exceptions

- `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/MessagesAvailableInstrumentation.java`
  - Creates message spans for client-side responses
  - Instruments MessagesAvailable and MessageRead runnable tasks

### Message Payload Handling
- `/home/user/niq-trace-java/dd-java-agent/agent-iast/src/main/java/com/datadog/iast/GrpcRequestMessageHandler.java`
  - Handles gRPC protobuf message tainting for IAST
  - Extracts and taints nested objects in GeneratedMessage types
  - Processes MapField objects and collections within messages

## 2. HOW gRPC MESSAGES ARE INTERCEPTED

### Server-Side Message Interception

The `TracingServerInterceptor` implements the `io.grpc.ServerInterceptor` interface:

```java
public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
    final ServerCall<ReqT, RespT> call,
    final Metadata headers,
    final ServerCallHandler<ReqT, RespT> next) {
  // ... span creation and context extraction ...
  
  // Wraps the ServerCall for response status tracking
  final TracingServerCall<ReqT, RespT> tracingServerCall = 
      new TracingServerCall<>(span, call);
  
  // Calls the next interceptor/handler in the chain
  result = next.startCall(tracingServerCall, headers);
  
  // Wraps the ServerCall.Listener to intercept incoming messages
  return new TracingServerCallListener<>(span, result);
}
```

### Key Interception Points:

1. **ServerCall.Listener.onMessage(final ReqT message)**
   - Called when a request message arrives on the server
   - Creates a child "grpc.message" span
   - Calls gateway callback: `EVENTS.grpcServerRequestMessage()`
   - Message object is passed directly to callbacks

2. **ServerCall.Listener.onHalfClose()**
   - Called when the client finishes sending messages

3. **ServerCall.Listener.onComplete() / onCancel()**
   - Called when the request completes or is cancelled

4. **ServerCall.close(Status status, Metadata trailers)**
   - Wrapped to track response status codes

### Client-Side Message Interception

The `ClientCallImplInstrumentation` hooks into:

1. **ClientCall.sendMessage()**
   - Located in ClientCallImplInstrumentation.SendMessage
   - Currently only activates the span scope
   - No direct message capture (would require access to the message parameter)

2. **ClientStreamListener.messageRead() / messagesAvailable()**
   - Instrumented by ClientStreamListenerImplInstrumentation
   - Creates message spans for received responses
   - Located in MessagesAvailableInstrumentation

## 3. MESSAGE SERIALIZATION/DESERIALIZATION POINTS

### Where Messages are Marshalled:

The codebase doesn't directly instrument protobuf marshalling, but works with:

**MethodDescriptor.Marshaller**:
- Used to get message types
- Referenced in `GrpcClientDecorator.messageType()`
- Accessed via `method.getRequestMarshaller()` and `method.getResponseMarshaller()`

```java
// From GrpcClientDecorator.java
public UTF8BytesString requestMessageType(MethodDescriptor<?, ?> method) {
  return messageType(method.getRequestMarshaller());
}

private UTF8BytesString messageType(MethodDescriptor.Marshaller<?> marshaller) {
  return marshaller instanceof MethodDescriptor.ReflectableMarshaller
      ? MESSAGE_TYPES.get(
          ((MethodDescriptor.ReflectableMarshaller<?>) marshaller).getMessageClass())
      : null;
}
```

### Message Objects Passed Through:

Messages are protobuf GeneratedMessage objects:
- `com.google.protobuf.GeneratedMessage`
- `com.google.protobuf.GeneratedMessageV3`
- `com.google.protobuf.GeneratedMessageLite`

These are available as plain Java objects with getter methods by the time they reach listeners.

## 4. EXISTING MESSAGE CAPTURE INSTRUMENTATION

### Gateway Callback System:

The codebase uses a gateway callback mechanism (similar to Datadog's application gateway):

**Event: `EVENTS.grpcServerRequestMessage()`**
- Type: `BiFunction<RequestContext, Object, Flow<Void>>`
- Triggered in: `TracingServerInterceptor.TracingServerCallListener.onMessage()`
- Message object passed as second parameter

```java
// From TracingServerInterceptor.java (line 144-165)
@Override
public void onMessage(final ReqT message) {
  final AgentSpan msgSpan =
      startSpan(GRPC_MESSAGE, this.span.context())
          .setTag("message.type", message.getClass().getName());
  DECORATE.afterStart(msgSpan);
  try (AgentScope scope = activateSpan(msgSpan)) {
    callIGCallbackGrpcMessage(msgSpan, message);  // <-- CALLBACK HERE
    delegate().onMessage(message);
  } catch (final Throwable e) {
    // ... error handling ...
  } finally {
    DECORATE.beforeFinish(msgSpan);
    msgSpan.finish();
  }
}

private static void callIGCallbackGrpcMessage(@Nonnull final AgentSpan span, Object obj) {
  if (obj == null) {
    return;
  }
  RequestContext requestContext = span.getRequestContext();
  if (requestContext == null) {
    return;
  }

  if (cbpAppsec != null) {
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbpAppsec.getCallback(EVENTS.grpcServerRequestMessage());
    if (callback != null) {
      callback.apply(requestContext, obj);  // <-- Message passed here
    }
  }
  
  if (cbpIast != null) {
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbpIast.getCallback(EVENTS.grpcServerRequestMessage());
    if (callback != null) {
      callback.apply(requestContext, obj);  // <-- Message passed here
    }
  }
}
```

### IAST Message Handler:

**GrpcRequestMessageHandler** processes captured messages:

```java
// From GrpcRequestMessageHandler.java
@Override
public Flow<Void> apply(final RequestContext ctx, final Object o) {
  final PropagationModule module = InstrumentationBridge.PROPAGATION;
  if (module != null && o != null) {
    final IastContext iastCtx = ctx.getData(RequestContextSlot.IAST);
    final byte source = SourceTypes.GRPC_BODY;  // <-- Source type for messages
    final int tainted =
        module.taintObjectDeeply(
            iastCtx, o, source, GrpcRequestMessageHandler::visitProtobufArtifact);
    if (tainted > 0) {
      IastMetricCollector.add(IastMetric.EXECUTED_SOURCE, source, tainted, iastCtx);
    }
  }
  return Flow.ResultFlow.empty();
}

static boolean visitProtobufArtifact(@Nonnull final Class<?> kls) {
  final Class<?> superClass = kls.getSuperclass();
  if (superClass != null && superClass.getName().startsWith(GENERATED_MESSAGE)) {
    return true; // GRPC custom messages
  }
  if (MAP_FIELD.equals(kls.getName())) {
    return true; // a map that does not implement the map interface
  }
  // nested collections are safe in GRPC
  return kls.isArray() || Iterable.class.isAssignableFrom(kls) || 
         Map.class.isAssignableFrom(kls);
}
```

### Other Gateway Events for gRPC:

1. **EVENTS.grpcServerMethod()** - Gets the full method name
2. **EVENTS.requestHeader()** - Gets metadata headers
3. **EVENTS.requestClientSocketAddress()** - Gets remote address

## 5. METADATA (HEADERS) HANDLING

### Header Extraction (Server):

Located in `TracingServerInterceptor.callIGCallbackHeaders()`:

```java
private static void callIGCallbackHeaders(
    CallbackProvider cbp, RequestContext reqCtx, Metadata metadata) {
  TriConsumer<RequestContext, String, String> headerCb = 
      cbp.getCallback(EVENTS.requestHeader());
  Function<RequestContext, Flow<Void>> headerEndCb = 
      cbp.getCallback(EVENTS.requestHeaderDone());
  
  if (headerCb == null || headerEndCb == null) {
    return;
  }
  
  for (String key : metadata.keys()) {
    if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      Metadata.Key<String> mdKey = 
          Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
      for (String value : metadata.getAll(mdKey)) {
        headerCb.accept(reqCtx, key, value);  // <-- Each header passed
      }
    }
  }
  
  headerEndCb.apply(reqCtx);  // <-- Signal headers are done
}
```

### Header Injection (Client):

Located in `GrpcInjectAdapter`:

```java
// From GrpcInjectAdapter.java
@Override
public void set(final Metadata carrier, final String key, final String value) {
  Metadata.Key<String> metadataKey = 
      Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
  if (carrier.containsKey(metadataKey)) {
    carrier.removeAll(metadataKey);
  }
  carrier.put(metadataKey, value);  // <-- Context propagation injection
}
```

Used in `ClientCallImplInstrumentation.Start`:

```java
public static final class Start {
  @Advice.OnMethodEnter
  public static AgentScope before(
      @Advice.This ClientCall<?, ?> call,
      @Advice.Argument(1) Metadata headers,
      @Advice.Local("$$ddSpan") AgentSpan span) {
    span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
    if (null != span) {
      DECORATE.injectContext(span, headers, SETTER);  // <-- Inject trace context
      return activateSpan(span);
    }
    return null;
  }
  // ...
}
```

## 6. LISTENER/INTERCEPTOR PATTERNS

### ServerCall.Listener Wrapping Pattern:

```
ServerInterceptor.interceptCall()
    ↓
  Creates: TracingServerCall (wraps ServerCall)
    ↓
  Calls: next.startCall(tracingServerCall, headers) → returns ServerCall.Listener
    ↓
  Wraps: TracingServerCallListener (extends SimpleForwardingServerCallListener)
    ↓
  Listener methods: onMessage(), onHalfClose(), onComplete(), onCancel(), onReady()
```

### Forwarding Classes Used:

```java
// From TracingServerInterceptor.java

static final class TracingServerCall<ReqT, RespT>
    extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
  // Wraps ServerCall to intercept close()
  @Override
  public void close(final Status status, final Metadata trailers) {
    DECORATE.onClose(span, status);
    // ... spans status tracking ...
    delegate().close(status, trailers);
  }
}

static final class TracingServerCallListener<ReqT>
    extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
  // Wraps ServerCall.Listener to intercept onMessage()
  @Override
  public void onMessage(final ReqT message) {
    // ... create message span and call gateway ...
    callIGCallbackGrpcMessage(msgSpan, message);
    delegate().onMessage(message);
  }
  
  // ... other listener methods ...
}
```

### ClientCall.Listener Wrapping (Bytecode-based):

Client-side listeners are wrapped at bytecode level:
- `ClientCallImplInstrumentation` instruments `ClientCallImpl` constructor
- `ClientStreamListenerImplInstrumentation` instruments `ClientCallImpl$ClientStreamListenerImpl`
- Span is stored in InstrumentationContext for retrieval in listener methods

## 7. KEY INTERCEPTION POINTS FOR PAYLOAD CAPTURE

### Server-Side Request Message Capture:
**Location**: `TracingServerInterceptor.TracingServerCallListener.onMessage(ReqT message)`
- The `message` parameter is the deserialized protobuf object
- **Type**: `Object` (actual type is GeneratedMessage subclass)
- **Available**: Full Java object with all fields accessible
- **Serialization State**: Already deserialized

### Client-Side Request Message:
**Location**: `ClientCallImplInstrumentation.SendMessage`
- **Current Limitation**: Message not captured in current instrumentation
- The `sendMessage()` method signature: `sendMessage(ReqT message)`
- Could be captured here if bytecode advice accesses the method argument

### Client-Side Response Message Capture:
**Location**: `MessagesAvailableInstrumentation.ReceiveMessages`
- Creates spans for received messages but doesn't capture payload
- Could be enhanced to capture response message content

### Message Serialization Details:
- **Protobuf Marshalling**: Happens in gRPC's internal `BinaryLog` or transport layer
- **Not instrumented**: The actual `toByteArray()` or `writeDelimitedTo()` calls
- **Available at listener**: Messages are already Java objects with field accessors

## 8. MESSAGE BODY SERIALIZATION CAPABILITY

### Direct Access Points:

1. **In onMessage() callback**: Message is plain Java object
   - Can call `toString()` for string representation
   - Can access public getter methods on protobuf message
   - Can use reflection to access all fields

2. **For protobuf messages**:
   - Call `message.toByteArray()` for serialized form
   - Call `message.toString()` for text format
   - Access individual fields via generated getters

Example from test:
```java
// From GrpcTest.groovy
collectedAppSecReqMsgs.first().name == name
// Direct field access on protobuf message object
```

### No Direct Serialization Hooks:

The codebase doesn't instrument:
- `Marshaller.parse()`
- `Marshaller.stream()`
- protobuf's `parseFrom()` methods
- gRPC's internal framing/serialization

All message handling happens at the Java object level after deserialization.

## SUMMARY TABLE

| Component | Purpose | Key Method | Parameters | Current Capability |
|-----------|---------|-----------|-----------|------------------|
| TracingServerInterceptor | Server request interception | interceptCall() | ServerCall, Metadata | Headers + Message content |
| TracingServerCallListener | Server message capture | onMessage(ReqT) | Deserialized message | Full object access |
| ClientCallImplInstrumentation | Client request tracing | sendMessage() | Message object | Span creation only |
| ClientStreamListenerImplInstrumentation | Client response handling | messageRead() | Not captured | Response availability |
| GrpcRequestMessageHandler | IAST message processing | apply(RequestContext, Object) | Message object | Deep taint propagation |
| GrpcExtractAdapter | Header extraction | forEachKey() | Metadata | All string headers |
| GrpcInjectAdapter | Header injection | set(Metadata, key, value) | Key-value pairs | Trace context injection |

