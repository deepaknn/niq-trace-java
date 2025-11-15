# gRPC Message Payload Handling - Complete Analysis Index

## Overview
This directory contains a comprehensive analysis of how the niq-trace-java codebase intercepts and processes gRPC messages for payload capture, including specific code locations and implementation examples.

## Key Findings

**The Best Interception Point for Payload Capture:**
- **File**: `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java`
- **Method**: `TracingServerCallListener.onMessage(ReqT message)`
- **Lines**: 144-165
- **State**: Message fully deserialized to Java object with complete field access
- **Callback**: Already integrated with gateway system

## Documents in This Analysis

### 1. **FINDINGS_SUMMARY.md** (18 KB)
**Most Important - Start Here**

Complete executive summary covering:
- 8 major sections with detailed explanations
- All interception points (both client and server)
- Message serialization/deserialization architecture
- Existing message capture infrastructure (IAST handler)
- Metadata handling (headers extraction and injection)
- Listener/interceptor patterns used
- Key interception points for payload capture
- File reference with line numbers
- How to implement payload capture (4 options)
- Summary table of interception capabilities
- Critical code snippets ready to use

**Read this for**: Complete understanding of the architecture and capabilities

### 2. **grpc_analysis.md** (16 KB)
**Technical Deep Dive**

Comprehensive technical documentation including:
- Overview of the instrumentation framework
- 8 detailed sections on how gRPC messages are intercepted
- Message serialization/deserialization points
- Existing message capture instrumentation
- Gateway callback system details
- Metadata handling mechanisms
- Listener/interceptor patterns
- Summary table comparing components
- Reference guide to all instrumentation files

**Read this for**: Deep technical understanding and architecture details

### 3. **payload_capture_examples.md** (14 KB)
**Code Examples - Ready to Use**

Practical code examples showing:
1. Server-side request payload capture (easiest approach)
2. Client-side request payload capture (with @Advice.Argument)
3. Client-side response payload capture (challenges)
4. Metadata/header capture
5. Complete payload capture pipeline
6. Context store pattern for message access
7. Testing payload capture
8. Summary table of best interception points

**Read this for**: Ready-to-use code snippets and implementation patterns

### 4. **grpc_architecture.txt** (12 KB)
**Visual Architecture & Data Flow**

ASCII diagrams and visual representations of:
- Client-side message flow
- Server-side message flow with all interception points
- Message object availability at key points
- Gateway callback chain
- Message capture pipeline with callback routing
- Key instrumentation file locations with line numbers
- Protobuf message capabilities
- Context store pattern for cross-method data

**Read this for**: Visual understanding of message flow and architecture

## Quick Start: Finding What You Need

### I want to understand how messages are intercepted:
1. Read: **grpc_architecture.txt** (5 min - visual overview)
2. Then: **FINDINGS_SUMMARY.md** Part 1 & 2 (10 min)

### I want to implement payload capture:
1. Read: **payload_capture_examples.md** Section 5 (Complete Pipeline)
2. Then: **FINDINGS_SUMMARY.md** Part 8 (Options)
3. Copy code from: **payload_capture_examples.md**

### I want to understand the existing IAST handler:
1. Read: **FINDINGS_SUMMARY.md** Part 3 (Message Capture Infrastructure)
2. Check: `/dd-java-agent/agent-iast/src/main/java/com/datadog/iast/GrpcRequestMessageHandler.java`

### I want to see all interception points:
1. Read: **FINDINGS_SUMMARY.md** Part 6 (Key Interception Points)
2. Then: **grpc_architecture.txt** (Architecture section)

### I want the exact file locations:
1. Check: **FINDINGS_SUMMARY.md** Part 7 (Complete File Reference)
2. Or: **payload_capture_examples.md** Code examples (have full paths)

## Critical Files Referenced

### Core Instrumentation
```
dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/
├── server/
│   ├── TracingServerInterceptor.java (MAIN - payload capture point)
│   ├── GrpcServerBuilderInstrumentation.java
│   ├── GrpcServerDecorator.java
│   ├── GrpcExtractAdapter.java
│   └── MethodHandlersInstrumentation.java
├── client/
│   ├── ClientCallImplInstrumentation.java
│   ├── ClientStreamListenerImplInstrumentation.java
│   ├── MessagesAvailableInstrumentation.java
│   ├── AbstractClientStreamInstrumentation.java
│   ├── GrpcClientDecorator.java
│   └── GrpcInjectAdapter.java
└── QueuedCommandInstrumentation.java
```

### Message Handling
```
dd-java-agent/agent-iast/src/main/java/com/datadog/iast/
└── GrpcRequestMessageHandler.java (IAST payload processing)
```

### Events & Gateway
```
internal-api/src/main/java/datadog/trace/api/gateway/
└── Events.java (Event type definitions)

dd-java-agent/appsec/src/main/java/com/datadog/appsec/gateway/
└── GatewayBridge.java (Callback registration)
```

## Key Code Locations (Line Numbers)

### Server-Side Message Capture (Best Option)
- **TracingServerInterceptor.interceptCall()**: Lines 57-61
- **TracingServerCallListener.onMessage()**: Lines 144-165 ← **PRIMARY CAPTURE POINT**
- **callIGCallbackGrpcMessage()**: Lines 331-360

### Client-Side Message Capture (Enhancement Needed)
- **ClientCallImplInstrumentation.SendMessage**: Lines 135-152
  - Currently: Only activates span
  - Enhancement: Add `@Advice.Argument(0) Object message` for payload

### Headers Handling
- **callIGCallbackHeaders()**: Lines 287-304 (extraction)
- **GrpcInjectAdapter.set()**: Lines 12-19 (injection)

### IAST Message Handler
- **GrpcRequestMessageHandler.apply()**: Lines 33-46
- **visitProtobufArtifact()**: Lines 48-58

## Implementation Options (from Most to Least Recommended)

### Option 1: Register Custom Callback ⭐ EASIEST
- Location: Gateway callback registration
- Effort: 1 hour
- Risk: Minimal
- See: **payload_capture_examples.md** Section 5 Step 1

### Option 2: Extend TracingServerInterceptor
- Location: Subclass existing interceptor
- Effort: 2-3 hours
- Risk: Low
- Benefit: Fully integrated

### Option 3: Custom Bytecode Instrumentation
- Location: New InstrumenterModule
- Effort: 3-4 hours
- Risk: Medium (bytecode manipulation)
- Benefit: Full client-side capture

### Option 4: Enhance IAST Handler
- Location: Extend GrpcRequestMessageHandler
- Effort: 2 hours
- Risk: Low
- Benefit: Integrated with existing taint tracking

## Message Serialization Details

### Messages are Java Objects, Not Bytes
```
At TracingServerCallListener.onMessage():
- message is NOT a byte array
- message is a deserialized protobuf GeneratedMessage
- All fields are accessible via generated getters
- Can be re-serialized with message.toByteArray()
- Can be converted to string with message.toString()
```

### Protobuf Message Capabilities
```java
// All of these work:
byte[] bytes = message.toByteArray();
String text = message.toString();
String name = ((Request) message).getName();  // Direct field access
// Use reflection for unknown types
```

## Testing References

See examples in: `/dd-java-agent/instrumentation/grpc-1.5/src/test/groovy/GrpcTest.groovy`

- **Line 102-105**: Message callback example
- **Line 242**: Direct message field access test
- **Line 116-250**: Complete test scenario

## Gateway Callback Events

Available events for message processing:
1. `requestStarted()` - Supplier<Flow<Object>>
2. `requestHeader()` - TriConsumer<RequestContext, String, String>
3. `requestHeaderDone()` - Function<RequestContext, Flow<Void>>
4. `grpcServerMethod()` - BiFunction<RequestContext, String, Flow<Void>>
5. `grpcServerRequestMessage()` - BiFunction<RequestContext, Object, Flow<Void>> ← **USE THIS**
6. `requestEnded()` - BiFunction<RequestContext, IGSpanInfo, Flow<Void>>

## Next Steps

1. **Understand the Architecture**: Read grpc_architecture.txt (5 min)
2. **Get Technical Details**: Read FINDINGS_SUMMARY.md Parts 1-6 (20 min)
3. **Choose Implementation**: FINDINGS_SUMMARY.md Part 8 (5 min)
4. **Write Code**: Use payload_capture_examples.md as template (30-120 min depending on option)
5. **Test**: Use GrpcTest.groovy as reference for test patterns

## Document Statistics

| Document | Size | Read Time | Best For |
|----------|------|-----------|----------|
| FINDINGS_SUMMARY.md | 18 KB | 30 min | Complete understanding |
| grpc_analysis.md | 16 KB | 25 min | Technical deep dive |
| payload_capture_examples.md | 14 KB | 20 min | Code examples |
| grpc_architecture.txt | 12 KB | 10 min | Visual understanding |
| **Total** | **60 KB** | **95 min** | Complete mastery |

## Questions Answered

✓ How are gRPC messages intercepted?
✓ Where can I access message payloads?
✓ What message serialization points exist?
✓ How is IAST already capturing messages?
✓ How are headers extracted and injected?
✓ What listener/interceptor patterns are used?
✓ What are the best places to capture payloads?
✓ How do I implement payload capture?
✓ What code examples do you have?
✓ What are the file paths and line numbers?

---

**Generated**: 2025-11-15
**Repository**: niq-trace-java
**Branch**: claude/payload-capture-analysis-01Y5GcYWDdtWQPxJqCj5AVa7
**Analysis Scope**: Complete gRPC instrumentation framework

For questions about specific code, refer to the absolute file paths provided throughout these documents.
