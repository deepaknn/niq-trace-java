# Code Examples: Leveraging gRPC Message Interception Points

## 1. SERVER-SIDE REQUEST PAYLOAD CAPTURE (EASIEST APPROACH)

### Primary Interception Point:
**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java`

**Code Location** (Line 144-165):
```java
@Override
public void onMessage(final ReqT message) {
  final AgentSpan msgSpan =
      startSpan(GRPC_MESSAGE, this.span.context())
          .setTag("message.type", message.getClass().getName());
  DECORATE.afterStart(msgSpan);
  try (AgentScope scope = activateSpan(msgSpan)) {
    // PERFECT INTERCEPTION POINT: Full message object available
    callIGCallbackGrpcMessage(msgSpan, message);  // <-- message is deserialized here
    delegate().onMessage(message);
  } catch (final Throwable e) {
    // ... error handling ...
  } finally {
    DECORATE.beforeFinish(msgSpan);
    msgSpan.finish();
  }
}
```

### How to Access Message Content:

```java
// Option 1: Access protobuf message fields directly
if (message instanceof com.google.protobuf.Message) {
  com.google.protobuf.Message pbMessage = (com.google.protobuf.Message) message;
  
  // Get serialized bytes
  byte[] messageBytes = pbMessage.toByteArray();
  
  // Get text representation
  String messageText = pbMessage.toString();
  
  // For specific fields, cast to actual message type
  // example.Helloworld.Request request = (example.Helloworld.Request) message;
  // String name = request.getName();
}

// Option 2: Reflection-based approach
Class<?> msgClass = message.getClass();
for (Method method : msgClass.getMethods()) {
  if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
    try {
      Object fieldValue = method.invoke(message);
      // Process fieldValue
    } catch (Exception e) {
      // Handle reflection error
    }
  }
}
```

### Gateway Callback Processing (IAST Example):

**File**: `/home/user/niq-trace-java/dd-java-agent/agent-iast/src/main/java/com/datadog/iast/GrpcRequestMessageHandler.java`

```java
public Flow<Void> apply(final RequestContext ctx, final Object o) {
  final PropagationModule module = InstrumentationBridge.PROPAGATION;
  if (module != null && o != null) {
    final IastContext iastCtx = ctx.getData(RequestContextSlot.IAST);
    final byte source = SourceTypes.GRPC_BODY;
    
    // Deep tainting of protobuf message and all nested objects
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

### Payload Capture Implementation Pattern:

```java
// Pseudo-code for payload capture in callback
public Flow<Void> onGrpcServerRequestMessage(
    final RequestContext ctx, final Object message) {
  
  // 1. Serialize message to bytes
  if (message instanceof com.google.protobuf.Message) {
    byte[] payload = ((com.google.protobuf.Message) message).toByteArray();
    
    // 2. Store in request context
    ctx.setData(RequestContextSlot.BODY, payload);
    
    // 3. Or send to external payload capture service
    PayloadCaptureService.capturePayload(
      ctx.getTraceId(),
      message.getClass().getName(),
      payload);
  }
  
  return Flow.ResultFlow.empty();
}
```

## 2. CLIENT-SIDE REQUEST PAYLOAD CAPTURE (SENDMESSAGE)

### Primary Interception Point:
**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/ClientCallImplInstrumentation.java`

**Current Code** (Line 135-152):
```java
public static final class SendMessage {
  @Advice.OnMethodEnter
  public static AgentScope before(@Advice.This ClientCall<?, ?> call) {
    // could create a message span here for the request
    AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
    if (span != null) {
      return activateSpan(span);
    }
    return null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void after(@Advice.Enter AgentScope scope) {
    if (null != scope) {
      scope.close();
    }
  }
}
```

### Enhanced Version to Capture Message:

```java
public static final class SendMessage {
  @Advice.OnMethodEnter
  public static AgentScope before(
      @Advice.This ClientCall<?, ?> call,
      @Advice.Argument(0) Object message) {  // <-- Access the message parameter
    
    AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
    if (span != null) {
      // PAYLOAD CAPTURE HERE
      if (message instanceof com.google.protobuf.Message) {
        byte[] payload = ((com.google.protobuf.Message) message).toByteArray();
        // Store or process payload
        span.setTag("grpc.request.payload.size", payload.length);
        
        // Could store in context
        // RequestContext ctx = span.getRequestContext();
        // if (ctx != null) {
        //   ctx.setData(RequestContextSlot.BODY, payload);
        // }
      }
      return activateSpan(span);
    }
    return null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void after(@Advice.Enter AgentScope scope) {
    if (null != scope) {
      scope.close();
    }
  }
}
```

## 3. CLIENT-SIDE RESPONSE PAYLOAD CAPTURE

### Interception Point:
**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/MessagesAvailableInstrumentation.java`

**Current Code** (Line 71-91):
```java
public static final class ReceiveMessages {
  @Advice.OnMethodEnter
  public static AgentScope before() {
    AgentSpan clientSpan = activeSpan();
    if (clientSpan != null && OPERATION_NAME.equals(clientSpan.getOperationName())) {
      AgentSpan messageSpan =
          startSpan(GRPC_MESSAGE).setTag("message.type", clientSpan.getTag("response.type"));
      DECORATE.afterStart(messageSpan);
      return activateSpan(messageSpan);
    }
    return null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void after(@Advice.Enter AgentScope scope) {
    if (null != scope) {
      scope.span().finish();
      scope.close();
    }
  }
}
```

**Problem**: The message object is not directly available in this runnable execution context.
It would need to be accessed through a different hook point.

### Alternative Approach - Hook Into ClientStreamListener.messageRead():

The `ClientStreamListenerImplInstrumentation` instruments:
```java
transformer.applyAdvice(
    namedOneOf("messageRead", "messagesAvailable"), 
    getClass().getName() + "$RecordActivity");
```

To capture response messages, you'd need to:
1. Instrument a lower-level deserialization method
2. Or wrap the ClientStreamListener to intercept message delivery

## 4. METADATA/HEADER CAPTURE

### Header Extraction (Server-Side):
**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java`

**Code** (Line 287-304):
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
        headerCb.accept(reqCtx, key, value);  // <-- Headers available here
      }
    }
  }
}
```

### Direct Header Access in Callback:

```java
public Flow<Void> onRequestHeader(
    final RequestContext ctx, final String key, final String value) {
  
  // Store all headers in context
  Map<String, String> headers = ctx.getData(RequestContextSlot.BODY);
  if (headers == null) {
    headers = new HashMap<>();
    ctx.setData(RequestContextSlot.BODY, headers);
  }
  headers.put(key, value);
  
  return Flow.ResultFlow.empty();
}
```

### Header Injection (Client-Side):
**File**: `/home/user/niq-trace-java/dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/GrpcInjectAdapter.java`

```java
@Override
public void set(final Metadata carrier, final String key, final String value) {
  Metadata.Key<String> metadataKey = 
      Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
  if (carrier.containsKey(metadataKey)) {
    carrier.removeAll(metadataKey);
  }
  carrier.put(metadataKey, value);  // <-- Context injection happens here
}
```

## 5. COMPLETE PAYLOAD CAPTURE PIPELINE

### Step 1: Register Custom Callback in APPSec Gateway

```java
// In GatewayBridge or similar
CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.CUSTOM);
cbp.registerCallback(
    EVENTS.grpcServerRequestMessage(),
    new MyGrpcPayloadCaptureHandler());
```

### Step 2: Implement Custom Handler

```java
public class MyGrpcPayloadCaptureHandler 
    implements BiFunction<RequestContext, Object, Flow<Void>> {
  
  private static final int MAX_PAYLOAD_SIZE = 10 * 1024 * 1024; // 10MB
  
  @Override
  public Flow<Void> apply(final RequestContext ctx, final Object message) {
    if (message == null) {
      return Flow.ResultFlow.empty();
    }
    
    try {
      // 1. Serialize message
      byte[] payload = serializeMessage(message);
      
      if (payload.length > MAX_PAYLOAD_SIZE) {
        // Truncate if too large
        payload = Arrays.copyOf(payload, MAX_PAYLOAD_SIZE);
      }
      
      // 2. Store in request context
      PayloadMetadata metadata = new PayloadMetadata(
          message.getClass().getName(),
          payload.length,
          System.currentTimeMillis(),
          ctx.getTraceId());
      
      List<PayloadMetadata> payloads = ctx.getData(RequestContextSlot.BODY);
      if (payloads == null) {
        payloads = new ArrayList<>();
        ctx.setData(RequestContextSlot.BODY, payloads);
      }
      payloads.add(metadata);
      
      // 3. Send to external service (async)
      PayloadCaptureService.sendAsync(metadata, payload);
      
    } catch (Exception e) {
      // Log but don't fail the request
      log.debug("Failed to capture gRPC payload", e);
    }
    
    return Flow.ResultFlow.empty();
  }
  
  private byte[] serializeMessage(Object message) throws Exception {
    if (message instanceof com.google.protobuf.Message) {
      return ((com.google.protobuf.Message) message).toByteArray();
    }
    
    // Fallback: use toString
    return message.toString().getBytes(StandardCharsets.UTF_8);
  }
}
```

### Step 3: Store Payload for Later Retrieval

```java
// In your payload handler
public void storePayload(AgentSpan span, byte[] payload, String messageType) {
  String payloadId = UUID.randomUUID().toString();
  
  // Store in distributed cache
  PayloadCache.put(payloadId, new PayloadInfo(
      span.getTraceId(),
      span.getSpanId(),
      messageType,
      payload,
      System.currentTimeMillis()));
  
  // Tag span with payload reference
  span.setTag("grpc.payload.id", payloadId);
  span.setTag("grpc.payload.size", payload.length);
  span.setTag("grpc.payload.type", messageType);
}
```

## 6. CONTEXT STORE PATTERN FOR MESSAGE ACCESS

### For Client-Side Capture:

```java
@Override
public Map<String, String> contextStore() {
  Map<String, String> stores = new HashMap<>();
  stores.put("io.grpc.ClientCall", "com.example.PayloadContext");
  return stores;
}

public static class Capture {
  @Advice.OnMethodExit
  public static void capture(
      @Advice.This io.grpc.ClientCall<?, ?> call) {
    AgentSpan span = activeSpan();
    if (span != null) {
      PayloadContext ctx = new PayloadContext(span.getTraceId());
      InstrumentationContext.get(ClientCall.class, PayloadContext.class)
          .put(call, ctx);
    }
  }
}

public static class SendMessage {
  @Advice.OnMethodEnter
  public static void before(
      @Advice.This ClientCall<?, ?> call,
      @Advice.Argument(0) Object message) {
    
    PayloadContext ctx = InstrumentationContext.get(
        ClientCall.class, PayloadContext.class).get(call);
    if (ctx != null && message != null) {
      ctx.recordRequestPayload(message);
    }
  }
}
```

## 7. TESTING PAYLOAD CAPTURE

### Test Pattern (from GrpcTest.groovy):

```groovy
def collectedReqPayloads = []

def setup() {
  ig.registerCallback(EVENTS.grpcServerRequestMessage(), 
    { reqCtx, obj ->
      // Capture message
      collectedReqPayloads << obj
      if (obj instanceof com.google.protobuf.Message) {
        byte[] bytes = ((com.google.protobuf.Message) obj).toByteArray()
        // Verify payload was captured
        assert bytes.length > 0
      }
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, Object, Flow<Void>>)
}

def "test payload capture"() {
  when:
  client.sayHello(request)
  
  then:
  collectedReqPayloads.size() == 1
  collectedReqPayloads.first().name == "test"
}
```

## SUMMARY: BEST INTERCEPTION POINTS FOR PAYLOAD CAPTURE

| Phase | Best Location | Method | Advantages | Limitations |
|-------|---------------|--------|-----------|------------|
| **Server Request** | TracingServerInterceptor.onMessage() | Direct callback | Message fully deserialized, gateway integrated | Server-side only |
| **Client Request** | ClientCallImplInstrumentation.SendMessage | Bytecode @Advice | Synchronous, direct access | Requires bytecode modification |
| **Server Response** | Would need custom instrumentation | TBD | Could capture response payload | Response path not currently instrumented |
| **Client Response** | ClientStreamListener deserialization point | Bytecode hook | Captures at reception | Complex due to async nature |
| **Headers** | TracingServerInterceptor.callIGCallbackHeaders() | Direct callback | Already implemented | String headers only |

