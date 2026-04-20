# MK3 — Architecture Document

## Table of Contents
1. [Overview](#1-overview)
2. [Folder Structure](#2-folder-structure)
3. [Technologies & Techniques](#3-technologies--techniques)
4. [Component Reference](#4-component-reference)
5. [Event Catalog](#5-event-catalog)
6. [Happy Path Sequences](#6-happy-path-sequences)

---

## 1. Overview

MK3 is a **microkernel-based LLM chat system** built on Unix Domain Sockets (UDS). The kernel is a pure event bus with zero knowledge of business logic. All behaviour is implemented in isolated plugins and tools that declare their own routing rules at connection time.

**Core design principles applied:**
- **KISS** — each component does one thing. The kernel only routes. Plugins only process their domain. Tools are pure functions.
- **DRY** — one shared `Event` record, one `ObjectMapper`, one UDS connection helper, one `plugin.json` pattern.
- **Open/Closed** — adding a new plugin or tool requires zero changes to the kernel, zero changes to Start.java logic, and zero changes to existing plugins.

---

## 2. Folder Structure

```
MK3/
├── Start.java                        # System entry point — process orchestrator
├── shared/
│   └── Event.java                    # Shared event record, MAPPER, UDS boilerplate
├── kernel/
│   └── Kernel.java                   # Event bus — plugin-agnostic router
├── plugins/
│   ├── console/
│   │   ├── ConsolePlugin.java        # User-facing interactive terminal
│   │   ├── TestConsolePlugin.java    # Automated smoke-test client
│   │   └── plugin.json               # Routing declaration for console-plugin
│   ├── llm/
│   │   ├── LLMPlugin.java            # LLM integration (LangChain4j / Groq)
│   │   └── plugin.json               # Routing declaration for llm-plugin
│   ├── logger/
│   │   ├── LoggerPlugin.java         # Audit log writer
│   │   └── plugin.json               # Routing declaration for logger-plugin
│   └── toolrunner/
│       ├── ToolRunner.java           # Tool & workflow orchestrator plugin
│       └── plugin.json               # Routing declaration for tool-runner
├── tools/
│   └── WeatherTool.java              # Pure tool script (no kernel dependency)
└── logs/
    ├── kernel.log                    # Kernel routing output
    ├── llm.log                       # LLM plugin output
    ├── toolrunner.log                # ToolRunner orchestration output
    └── system.log                    # All events (written by LoggerPlugin)
```

**Design rules per directory:**

| Directory | Rules |
|-----------|-------|
| `shared/` | No JBang header. No kernel dependency. Included via `//SOURCES`. |
| `kernel/` | No business logic. No hardcoded event types. |
| `plugins/*/` | Must have `plugin.json`. Must use `Event.connectAndRun()`. |
| `tools/` | No `plugin.json`. No UDS. No kernel knowledge. Pure `stdin → stdout`. |
| `logs/` | Runtime-generated only. Never committed to VCS. |

---

## 3. Technologies & Techniques

### Runtime & Build
| Technology | Version | Role |
|-----------|---------|------|
| **JBang** | latest | Single-file Java execution. No Maven/Gradle. Each `.java` is directly executable. Dependencies declared as `//DEPS` comments. |
| **Java** | 21+ | Language level. Records, virtual threads, pattern matching (switch), UDS support. |

### Libraries
| Library | Version | Role |
|---------|---------|------|
| `jackson-databind` | 2.17.2 | JSON serialization/deserialization of `Event` records. |
| `jackson-datatype-jsr310` | 2.17.2 | `LocalDateTime` support in Jackson. |
| `langchain4j-core` | 0.36.2 | Chat message abstraction (`UserMessage`, `AiMessage`, `ChatLanguageModel`). |
| `langchain4j-open-ai` | 0.36.2 | OpenAI-compatible client used to call Groq API. |
| Java `HttpClient` | JDK 21 | HTTP calls in `WeatherTool` (no extra dependency). |

### Techniques

**Unix Domain Sockets (UDS)**
Local IPC via `java.nio.channels.SocketChannel` with `StandardProtocolFamily.UNIX`. Zero network overhead. All communication is `\n`-delimited JSON lines over the socket stream.

**Pub/Sub with dynamic routing**
The kernel maintains `Map<eventType, List<BufferedWriter>>`. Each plugin declares its subscriptions in `plugin.register`. The kernel routes events exclusively to declared subscribers — no broadcast.

**Virtual Threads (Project Loom)**
`Executors.newVirtualThreadPerTaskExecutor()` is used everywhere. Each plugin connection, each event processing task, and each parallel tool execution runs on a virtual thread. Blocking I/O is safe and cheap.

**Correlation ID pattern**
Used for request/response over a pub/sub bus. ToolRunner generates a UUID `correlationId`, stores a `CompletableFuture` keyed by it, and blocks the virtual thread. The LLMPlugin copies the `correlationId` into its reply. The kernel routes the reply back to ToolRunner, which resolves the future.

**Self-registration**
Plugins are responsible for declaring their own routing rules. `Event.connectAndRun()` automatically sends a `plugin.register` event with the full `PluginConfig` as payload. The kernel learns routing at runtime — zero boot-time configuration.

**Session isolation**
`ConsolePlugin` generates a `UUID sessionId` at startup and attaches it to every `chat.prompt` event. `LLMPlugin` maintains separate conversation histories keyed by `sessionId`, enabling multiple simultaneous users without context bleed.

**Workflow orchestration via CompletablFuture + virtual threads**
`ToolRunner` runs each workflow step (tool subprocess or LLM call) sequentially inside a virtual thread. Blocking on `future.get()` is safe because the main read loop (a separate virtual thread) resolves the future when `llm.result` arrives.

---

## 4. Component Reference

---

### `Start.java`
**Location:** `MK3/Start.java`
**Role:** Process orchestrator. Single entry point for the entire system.

**Responsibilities:**
- Spawns all components as independent JVM processes via `ProcessBuilder`
- Sets each process's working directory correctly (critical for relative socket paths)
- Redirects background process output to `logs/`
- Waits for `kernel.sock` to exist before spawning plugins (readiness gate)
- Attaches `inheritIO()` to `ConsolePlugin` so the user interacts with the terminal
- Registers a shutdown hook that destroys all child processes on exit

**Key methods:**

| Method | Description |
|--------|-------------|
| `main()` | Orchestration sequence: kernel → plugins → tools → console |
| `spawn(script, relDir, logFile)` | Starts a JBang process with correct working directory and log redirection |
| `waitForFile(path, timeoutMs)` | Polls until a file appears (used for `kernel.sock` readiness) |

**Usage:**
```bash
cd MK3/
export GROQ_API_KEY=your_key
jbang Start.java
```

**Startup sequence:**
```
1. Kernel.java       started (kernel/)
2. kernel.sock       appears → kernel is ready
3. LLMPlugin.java    started (plugins/llm/)
4. LoggerPlugin.java started (plugins/logger/)
5. ToolRunner.java   started (plugins/toolrunner/)
6. sleep 3s          → all plugins connect and register
7. ConsolePlugin.java started (plugins/console/) — terminal handed to user
```

---

### `shared/Event.java`
**Location:** `MK3/shared/Event.java`
**Role:** The single shared contract of the entire system. Included by all components via JBang `//SOURCES`.

**The record:**
```java
public record Event(
    String        id,            // UUID — always present
    String        type,          // event type  (e.g. "chat.prompt")
    String        payload,       // content     (plain string or JSON string)
    LocalDateTime timestamp,     // creation time
    String        source,        // creator id  (e.g. "console-plugin")
    String        correlationId, // nullable — links a request to its response
    String        sessionId,     // nullable — isolates user conversations
    String        workflowId,    // nullable — groups all events of a workflow
    String        replyTo        // nullable — expected response event type
)
```

Null fields are omitted from JSON (`NON_NULL`), preserving backward compatibility.

**Factory methods:**

| Method | When to use | Example |
|--------|-------------|---------|
| `Event.of(type, payload, source)` | Simple internal events, plugin.register | `Event.of("kernel.ping", "", "kernel")` |
| `Event.withSession(type, payload, source, sessionId)` | Chat messages from a user console | `Event.withSession("chat.prompt", "Hello", "console-plugin", sessionId)` |
| `Event.request(type, payload, source, corrId, workflowId, replyTo)` | ToolRunner → LLM calls | `Event.request("llm.invoke", prompt, "tool-runner", corrId, wfId, "llm.result")` |
| `Event.reply(origin, type, payload, source)` | LLMPlugin responding to any request | `Event.reply(ev, "chat.response", text, "llm-plugin")` — auto-copies correlationId, sessionId, workflowId |

**Nested types:**

| Type | Description |
|------|-------------|
| `PluginConfig` | Record representing `plugin.json`: `id`, `subscribes[]`, `publishes[]` |
| `PluginLogic` | `@FunctionalInterface` — `void run(BufferedReader in, BufferedWriter out)` |

**Static utilities:**

| Method | Description |
|--------|-------------|
| `MAPPER` | Shared `ObjectMapper` (Jackson + JavaTimeModule + NON_NULL) |
| `loadConfig(path)` | Deserializes `plugin.json` into `PluginConfig` |
| `connectAndRun(socketPath, config, logic)` | Opens UDS socket, sends `plugin.register`, runs the plugin logic lambda |

---

### `kernel/Kernel.java`
**Location:** `MK3/kernel/Kernel.java`
**Role:** Plugin-agnostic event bus. Routes events between plugins based on runtime subscriptions.

**Key design:** The kernel has **zero hardcoded event types**. It learns routing exclusively from `plugin.register` events received at runtime.

**Internal data structures:**

| Field | Type | Purpose |
|-------|------|---------|
| `eventQueue` | `LinkedTransferQueue<String>` | FIFO queue of JSON event lines awaiting processing |
| `subscriptions` | `Map<String, CopyOnWriteArrayList<BufferedWriter>>` | eventType → list of writers to deliver to |
| `writerIndex` | `Map<BufferedWriter, List<String>>` | writer → its subscribed types (for cleanup on disconnect) |
| `executor` | `VirtualThreadPerTaskExecutor` | Runs socket server, event processor, and per-subscriber deliveries |

**Key methods:**

| Method | Description |
|--------|-------------|
| `startSocketServer()` | Accepts UDS connections in a loop, dispatches each to `handleClient()` |
| `handleClient(channel)` | Reads lines from a plugin. Routes `plugin.register` inline; other events go to `eventQueue` |
| `register(event, writer)` | Parses `PluginConfig` from payload; adds writer to `subscriptions` for each subscribed type |
| `unregister(writer)` | On disconnect: removes writer from all subscription lists using `writerIndex` |
| `startEventProcessor()` | Takes events from `eventQueue` sequentially; dispatches to subscribers in parallel virtual threads |
| `sendTo(writer, message)` | `synchronized(writer)` write — prevents stream corruption when parallel dispatch hits same subscriber |

**Routing rules (implicit):**
- `plugin.register` → handled inline in `handleClient` (not queued)
- All other event types → queued → routed to all writers subscribed to that type
- Unknown types (no subscribers) → logged and dropped

---

### `plugins/console/ConsolePlugin.java`
**Location:** `MK3/plugins/console/ConsolePlugin.java`
**Role:** Interactive terminal interface for the user.

**Subscribes to:** `chat.response`, `chat.prompt`, `tool.result`
**Publishes:** `chat.prompt`, `tool.invoke`

**Session management:** Generates a `UUID sessionId` at startup. Every outbound event carries this `sessionId`, enabling the LLMPlugin to maintain isolated conversation history per user.

**Input commands:**

| Input | Action | Event sent |
|-------|--------|------------|
| `Hello, how are you?` | Chat with LLM | `chat.prompt` with `sessionId` |
| `!WeatherTool London` | Invoke a tool | `tool.invoke` with JSON payload `{"tool":"WeatherTool","input":"London"}` |
| `exit` or `quit` | Quit | Process ends, shutdown hook fires |

**Concurrency model:** Two concurrent flows share the same socket:
- **Main thread:** reads user `stdin` via `Scanner`, writes events to kernel
- **Receiver virtual thread:** reads events from kernel, prints responses to terminal

**Usage example:**
```
You: What is the capital of France?
[AI] The capital of France is Paris.
You: !WeatherTool Paris
[TOOL] Paris: ⛅ +18°C
You: exit
```

---

### `plugins/console/TestConsolePlugin.java`
**Location:** `MK3/plugins/console/TestConsolePlugin.java`
**Role:** Automated smoke-test client. Sends one event and exits.

**Subscribes to:** nothing (publishes only)
**Publishes:** `chat.prompt`

Uses an **inline `PluginConfig`** (no `plugin.json` file) since it is a test utility that shares the `console/` directory with `ConsolePlugin`.

**Usage:**
```bash
cd MK3/plugins/console
jbang TestConsolePlugin.java
# Sends: {"type":"chat.prompt","payload":"/directory","source":"test-plugin",...}
# Waits 1 second, disconnects
```

---

### `plugins/llm/LLMPlugin.java`
**Location:** `MK3/plugins/llm/LLMPlugin.java`
**Role:** LLM integration. Processes prompts from users and tool workflows.

**Subscribes to:** `chat.prompt`, `llm.invoke`
**Publishes:** `chat.response`, `llm.result`

**History management:**
Maintains `Map<String, LinkedList<ChatMessage>> histories` (max 10 messages per key).

| Event type | History key | Response type |
|-----------|-------------|---------------|
| `chat.prompt` | `event.sessionId()` or `"global"` | `chat.response` |
| `llm.invoke` | `event.workflowId()` or `"global"` | `event.replyTo()` or `"llm.result"` |

This design isolates conversation context: each user session has its own history, and each workflow execution has its own context.

**Key methods:**

| Method | Description |
|--------|-------------|
| `processEvent(eventJson, out)` | Dispatches by `event.type()`: chat.prompt or llm.invoke |
| `generate(prompt, historyKey)` | Calls `model.generate(history)`, updates history, returns text |
| `addToHistory(key, msg)` | Thread-safe circular buffer (max `MAX_HIST` = 10) per key |
| `publish(event, out)` | `synchronized(out)` write — safe for parallel virtual thread calls |

**Environment requirement:**
```bash
export GROQ_API_KEY=your_groq_api_key
```

---

### `plugins/logger/LoggerPlugin.java`
**Location:** `MK3/plugins/logger/LoggerPlugin.java`
**Role:** Passive audit logger. Writes every received event to `logs/system.log`.

**Subscribes to:** `chat.prompt`, `chat.response`, `plugin.register`
**Publishes:** nothing

**Log format:**
```
[2025-04-19 14:23:01.456] {"id":"...","type":"chat.prompt","payload":"Hello","source":"console-plugin",...}
```

No processing, no response, no side effects. Pure observer.

---

### `plugins/toolrunner/ToolRunner.java`
**Location:** `MK3/plugins/toolrunner/ToolRunner.java`
**Role:** Orchestrates tool execution and LLM-assisted workflows.

**Subscribes to:** `tool.invoke`, `llm.result`
**Publishes:** `tool.result`, `llm.invoke`

**Invocation formats (payload of `tool.invoke`):**

Single tool:
```json
{ "tool": "WeatherTool", "input": "London" }
```

Sequential pipeline with LLM step:
```json
{ "pipeline": [
    { "tool": "WeatherTool", "input": "London" },
    { "llm": true, "prompt": "Write a travel tip based on: {result}" }
]}
```

Parallel execution:
```json
{ "parallel": "WeatherTool", "inputs": ["London", "Tokyo", "New York"] }
```

**Concurrency model:**
- **Main read loop** (virtual thread): receives `tool.invoke` and `llm.result`
- `tool.invoke` → dispatched to a new virtual thread via `executor.submit()`
- `llm.result` → resolves `CompletableFuture` in `pendingLLM` map
- The workflow virtual thread blocks cheaply on `future.get()` while waiting for LLM

**Key methods:**

| Method | Description |
|--------|-------------|
| `handleInvoke(ev, out)` | Parses payload JSON, delegates to the right execution strategy |
| `runPipeline(steps, origin, workflowId, out)` | Iterates steps sequentially; `{result}` template substitution between steps |
| `runParallel(toolName, inputs)` | Fans out to N virtual threads, joins results |
| `runTool(toolName, input)` | Spawns `jbang ToolName.java` (CWD = `tools/`), sends input via stdin, captures stdout+stderr; throws on non-zero exit |
| `callLLM(prompt, origin, workflowId, out)` | Publishes `llm.invoke` with `correlationId`, blocks virtual thread until `llm.result` arrives |
| `publish(event, out)` | `synchronized(out)` write |

**LLM call flow (Path B):**
```
1. Generate correlationId (UUID)
2. Register CompletableFuture in pendingLLM map
3. Publish llm.invoke → kernel → LLMPlugin
4. Block virtual thread on future.get(60s)
5. Kernel routes llm.result back → main loop resolves future
6. Virtual thread unblocks, continues workflow
```

---

### `tools/WeatherTool.java`
**Location:** `MK3/tools/WeatherTool.java`
**Role:** Pure tool — fetches current weather for a city. No kernel dependency.

**Input:** City name via `stdin` (from ToolRunner) or command-line args
**Output:** One-line weather summary to `stdout`
**API:** `https://wttr.in/{city}?format=3` — no API key required

**Usage (standalone):**
```bash
jbang WeatherTool.java London
# Output: London: ⛅ +15°C

echo "Tokyo" | jbang WeatherTool.java
# Output: Tokyo: 🌧 +22°C
```

**Usage (via ToolRunner from ConsolePlugin):**
```
You: !WeatherTool London
[TOOL] London: ⛅ +15°C
```

---

## 5. Event Catalog

| Event type | Producer | Consumers | Payload | Context fields |
|------------|----------|-----------|---------|----------------|
| `plugin.register` | All plugins (via `connectAndRun`) | Kernel (inline), LoggerPlugin | JSON `PluginConfig` | — |
| `chat.prompt` | ConsolePlugin, TestConsolePlugin | LLMPlugin, ConsolePlugin (other users), LoggerPlugin | User text | `sessionId` |
| `chat.response` | LLMPlugin | ConsolePlugin, LoggerPlugin | LLM-generated text | `sessionId` (copied from request) |
| `tool.invoke` | ConsolePlugin | ToolRunner | JSON invocation descriptor | `sessionId`, `workflowId` (optional) |
| `tool.result` | ToolRunner | ConsolePlugin | Tool output string | `sessionId`, `workflowId` (copied) |
| `llm.invoke` | ToolRunner | LLMPlugin | Prompt string | `correlationId`, `workflowId`, `replyTo` |
| `llm.result` | LLMPlugin | ToolRunner | LLM-generated text | `correlationId`, `workflowId` (copied) |

---

## 6. Happy Path Sequences

### Scenario A — User chat with LLM

```
User types: "What is the capital of France?"
```

```
ConsolePlugin
  │  Event.withSession("chat.prompt", "What is...", "console-plugin", sessionId)
  │
  ▼ [UDS write]
Kernel
  │  eventQueue.put(line)
  │  eventProcessor: subscriptions.get("chat.prompt") → [llmWriter, loggerWriter, consoleWriter]
  │  executor.submit(() → sendTo(llmWriter, json))
  │  executor.submit(() → sendTo(loggerWriter, json))
  │  executor.submit(() → sendTo(consoleWriter, json))   ← ConsolePlugin filters: source == self, ignores
  │
  ├─▶ LLMPlugin
  │     processEvent: type == "chat.prompt"
  │     historyKey = sessionId
  │     generate("What is...", sessionId)
  │       addToHistory(sessionId, UserMessage)
  │       model.generate(history)  ──▶  Groq API call
  │       addToHistory(sessionId, AiMessage)
  │     Event.reply(ev, "chat.response", "Paris", "llm-plugin")
  │     publish → [UDS write to kernel]
  │
  └─▶ LoggerPlugin
        writes to logs/system.log

Kernel
  │  Receives "chat.response" from LLMPlugin
  │  subscriptions.get("chat.response") → [consoleWriter, loggerWriter]
  │  executor.submit(() → sendTo(consoleWriter, json))
  │  executor.submit(() → sendTo(loggerWriter, json))
  │
  ├─▶ ConsolePlugin (receiver thread)
  │     ev.type() == "chat.response"
  │     System.out.println("[AI] Paris")
  │
  └─▶ LoggerPlugin
        writes to logs/system.log

Terminal output:
  You: What is the capital of France?
  [AI] The capital of France is Paris.
  You:
```

---

### Scenario B — Single tool invocation

```
User types: "!WeatherTool London"
```

```
ConsolePlugin
  │  parses: tool="WeatherTool", input="London"
  │  payload = {"tool":"WeatherTool","input":"London"}
  │  Event.withSession("tool.invoke", payload, "console-plugin", sessionId)
  │
  ▼ [UDS write]
Kernel
  │  subscriptions.get("tool.invoke") → [toolRunnerWriter]
  │  sendTo(toolRunnerWriter, json)
  │
  ▼
ToolRunner (main read loop)
  │  ev.type() == "tool.invoke"
  │  executor.submit(() → handleInvoke(ev, out))
  │
  ▼ (virtual thread)
ToolRunner.handleInvoke
  │  inv.has("tool") == true
  │  runTool("WeatherTool", "London")
  │    ProcessBuilder("jbang", "../../tools/WeatherTool.java")
  │    stdin.write("London")
  │    stdout → "London: ⛅ +15°C"
  │    process.waitFor(30s)
  │  Event.reply(ev, "tool.result", "London: ⛅ +15°C", "tool-runner")
  │  publish → [UDS write to kernel]
  │
  ▼
Kernel
  │  subscriptions.get("tool.result") → [consoleWriter]
  │  sendTo(consoleWriter, json)
  │
  ▼
ConsolePlugin (receiver thread)
  │  ev.type() == "tool.result"
  │  System.out.println("[TOOL] London: ⛅ +15°C")

Terminal output:
  You: !WeatherTool London
  [TOOL] London: ⛅ +15°C
  You:
```

---

### Scenario C — Pipeline: tool → LLM analysis

```
Triggered by: tool.invoke with pipeline payload
{ "pipeline": [
    { "tool": "WeatherTool", "input": "London" },
    { "llm": true, "prompt": "Write a travel tip based on: {result}" }
]}
```

```
ConsolePlugin ──▶ Kernel ──▶ ToolRunner

ToolRunner.handleInvoke
  │  inv.has("pipeline") == true
  │  runPipeline(steps, ev, workflowId="wf-uuid-1", out)
  │
  │  Step 1: tool
  │    runTool("WeatherTool", "London") → "London: ⛅ +15°C"
  │    result = "London: ⛅ +15°C"
  │
  │  Step 2: llm
  │    prompt = "Write a travel tip based on: London: ⛅ +15°C"
  │    callLLM(prompt, origin, "wf-uuid-1", out)
  │      corrId = "corr-uuid-1"
  │      pendingLLM.put("corr-uuid-1", CompletableFuture)
  │      Event.request("llm.invoke", prompt, "tool-runner",
  │                    "corr-uuid-1", "wf-uuid-1", "llm.result")
  │      publish → [UDS write to kernel]
  │      future.get(60s) ← virtual thread blocks here
  │
  ▼
Kernel ──▶ LLMPlugin

LLMPlugin.processEvent
  │  ev.type() == "llm.invoke"
  │  historyKey = workflowId = "wf-uuid-1"
  │  generate(prompt, "wf-uuid-1") → "London is mild today..."
  │  Event.reply(ev, "llm.result", "London is mild today...", "llm-plugin")
  │    correlationId = "corr-uuid-1"  ← copied from origin
  │    workflowId   = "wf-uuid-1"    ← copied from origin
  │  publish → [UDS write to kernel]
  │
  ▼
Kernel ──▶ ToolRunner (main read loop)

ToolRunner main loop
  │  ev.type() == "llm.result"
  │  ev.correlationId() == "corr-uuid-1"
  │  pendingLLM.remove("corr-uuid-1").complete("London is mild today...")
  │
  ▼ (workflow virtual thread unblocks)
ToolRunner.callLLM returns "London is mild today..."
  │
  │  result = "London is mild today..."
  │  pipeline finished
  │  Event.reply(originalInvoke, "tool.result", "London is mild today...", "tool-runner")
  │  publish → kernel → ConsolePlugin
  │
  ▼
Terminal output:
  [TOOL] London is mild today — pack a light jacket and enjoy the sights!
```

---

### Scenario D — Parallel tool execution

```
Payload: { "parallel": "WeatherTool", "inputs": ["London", "Tokyo", "New York"] }
```

```
ToolRunner.handleInvoke
  │  inv.has("parallel") == true
  │  runParallel("WeatherTool", ["London", "Tokyo", "New York"])
  │
  │  VirtualThread-1 → runTool("WeatherTool", "London")  → "London: ⛅ +15°C"
  │  VirtualThread-2 → runTool("WeatherTool", "Tokyo")   → "Tokyo: 🌧 +22°C"
  │  VirtualThread-3 → runTool("WeatherTool", "New York")→ "New York: ☀ +28°C"
  │
  │  (all three run concurrently)
  │  CompletableFuture.join() × 3 — waits for all
  │
  │  result = """
  │    London: London: ⛅ +15°C
  │    Tokyo: Tokyo: 🌧 +22°C
  │    New York: New York: ☀ +28°C
  │    """
  │
  │  publish tool.result → kernel → ConsolePlugin
  │
  ▼
Terminal output:
  [TOOL] London: ⛅ +15°C
         Tokyo: 🌧 +22°C
         New York: ☀ +28°C
```

---

### Scenario E — System startup sequence

```
jbang Start.java
  │
  ├─1─▶ jbang Kernel.java  (cwd: kernel/)
  │       Creates kernel.sock
  │       Starts socket server (virtual thread)
  │       Starts event processor (virtual thread)
  │
  ├─2─▶ Start polls for kernel/kernel.sock → appears → continues
  │
  ├─3─▶ jbang LLMPlugin.java  (cwd: plugins/llm/)
  │       Reads GROQ_API_KEY, initializes OpenAiChatModel
  │       connectAndRun → sends plugin.register
  │         Kernel: registers llm-plugin for [chat.prompt, llm.invoke]
  │
  ├─4─▶ jbang LoggerPlugin.java  (cwd: plugins/logger/)
  │       connectAndRun → sends plugin.register
  │         Kernel: registers logger-plugin for [chat.prompt, chat.response, plugin.register]
  │
  ├─5─▶ jbang ToolRunner.java  (cwd: plugins/toolrunner/)
  │       connectAndRun → sends plugin.register
  │         Kernel: registers tool-runner for [tool.invoke, llm.result]
  │
  ├─6─▶ sleep 3s
  │
  └─7─▶ jbang ConsolePlugin.java  (cwd: plugins/console/, inheritIO)
          Generates sessionId
          connectAndRun → sends plugin.register
            Kernel: registers console-plugin for [chat.response, chat.prompt, tool.result]
          User prompt appears: "You: "
```

---

*Document generated from source — MK3 version as of 2025-04-19*
