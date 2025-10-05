TimeLinter – Project Structure

Overview
- Purpose: Background service that monitors time‑wasting app usage, periodically starts an AI conversation, and enforces/records time grants and AI memory.
- Primary stacks: Kotlin, Jetpack Compose, Android Services/Notifications, Google AI (Gemini) client, Handlebars templates for prompts.

Entrypoint and UI
- app/src/main/java/com/example/timelinter/MainActivity.kt
  - App entry. Hosts the main Compose UI and toggles sub‑screens:
    - `AppSelectionScreen` (manage wasteful apps)
    - `TimerSettingsScreen` (thresholds/timers)
    - `AILogScreen` (AI memory + API history)
  - Top‑bar actions to open screens; persists user notes and coach name via `ApiKeyManager`.

- app/src/main/java/com/example/timelinter/AILogScreen.kt
  - Read‑only API history from `ConversationLogStore.apiHistory`.
  - AI Memory section:
    - Rules editor (editable, saved via `AIMemoryManager.setMemoryRules`).
    - Permanent memory editor (Edit/Save/Cancel) persisted via `AIMemoryManager.setPermanentMemory` and reflected in `ConversationLogStore.aiMemory`.
    - Temporary memories grouped by expiration date using `AIMemoryManager.getActiveTemporaryGroupsByDate`.

- app/src/main/java/com/example/timelinter/AppSelectionScreen.kt
  - Selects and shows wasteful apps; relies on `TimeWasterAppManager`.

- app/src/main/java/com/example/timelinter/TimerSettingsScreen.kt
  - Configures monitoring thresholds/timers.

Background Services and Flow
- app/src/main/java/com/example/timelinter/AppUsageMonitorService.kt
  - Foreground service managing thresholds (token bucket), notifications, conversation lifecycle, and AI calls.
  - Initializes:
    - `InteractionStateManager` (state machine)
    - `ConversationHistoryManager` (system prompt, memory, user templates)
    - `AIInteractionManager` (Gemini model + calls)
  - Responds to tool commands (Allow/Remember) and updates storage (`AIMemoryManager`) and UI store (`ConversationLogStore`).

- app/src/main/java/com/example/timelinter/BrowserUrlAccessibilityService.kt
  - AccessibilityService to read browser URL signals (wasteful or not).

AI and Conversation
- app/src/main/java/com/example/timelinter/AIInteractionManager.kt
  - Wraps Google AI GenerativeModel calls (initialization, subsequent calls, error handling).
  - Sends composed history from `ConversationHistoryManager`.

- app/src/main/java/com/example/timelinter/ConversationHistoryManager.kt
  - Owns two histories: user‑visible vs API (model) history.
  - Builds initial AI turn:
    1) System prompt
    2) AI Memory message using `AIMemoryManager.getAllMemories()` and `ai_memory_template`
    3) User status message (`user_info_template` + `gemini_user_template`)
  - Publishes API history snapshots to `ConversationLogStore` and adds session separators.

- app/src/main/java/com/example/timelinter/ConversationLogStore.kt
  - `StateFlow` store observed by UI: `apiHistory` and `aiMemory`.

Memory and Settings
- app/src/main/java/com/example/timelinter/AIMemoryManager.kt
  - Storage in `SharedPreferences`:
    - Permanent: newline‑joined text under `permanent_memory` with set/get/append APIs.
    - Temporary: entries under `temp_memory_<timestamp>` storing `content|expiresAt`; auto‑cleans expired on read.
    - Rules: editable guidelines persisted under `memory_rules`, defaulted from `res/raw/ai_memory_rules.txt`.
  - Helpers:
    - `getAllMemories()` for prompt inclusion (permanent + active temporary)
    - `getActiveTemporaryGroupsByDate()` returns temporary memories grouped by YYYY‑MM‑DD expiration.

- app/src/main/java/com/example/timelinter/SettingsManager.kt
  - Reads/writes app settings not covered elsewhere.

- app/src/main/java/com/example/timelinter/ApiKeyManager.kt
  - Stores API key, coach name, and user notes.

Wasteful Apps and Token Bucket
- app/src/main/java/com/example/timelinter/TimeWasterAppManager.kt
  - Tracks selected apps considered wasteful; resolves labels and persistence.

- app/src/main/java/com/example/timelinter/TokenBucket.kt
  - Threshold accounting used by the service for triggering conversations.

Prompts and Templates
- app/src/main/java/com/example/timelinter/TemplateManager.kt
  - Handlebars‑based template rendering utilities.

- app/src/main/res/raw/
  - `gemini_system_prompt.txt` – system prompt
  - `gemini_user_template.txt` – format for user messages
  - `user_info_template.txt` – status block used at session start
  - `ai_memory_template.txt` – inserted model message with memory
  - `ai_memory_rules.txt` – editable memory guidelines surfaced in UI
  - `gemini_first_ai_message.txt` – first AI message format

AI Tooling and Types
- app/src/main/java/com/example/timelinter/ToolTypes.kt
  - Data classes/enums for AI tool calls: Allow, Remember, etc.

- app/src/main/java/com/example/timelinter/GeminiFunctionCallParser.kt
  - Parser translating model output into `ToolCommand` usages.

Compose UI Utilities
- app/src/main/java/com/example/timelinter/ui/components/ScrollableTextFieldWithScrollbar.kt
- app/src/main/java/com/example/timelinter/ui/theme/Color.kt, Theme.kt, Type.kt

Other Core Files
- app/src/main/java/com/example/timelinter/ChatModels.kt – content builders and helpers
- app/src/main/java/com/example/timelinter/InteractionStateManager.kt – conversation state machine
- app/src/main/java/com/example/timelinter/TimeProvider.kt – injectable time provider (tests)
- app/src/main/java/com/example/timelinter/SettingsScreen.kt – legacy/general settings
- app/src/main/java/com/example/timelinter/AppInfo.kt – app metadata

Android Resources
- app/src/main/AndroidManifest.xml – services/permissions/components
- res/xml/*.xml – accessibility, backup rules, data extraction
- res/mipmap/*, res/drawable/* – launcher icons and backgrounds
- res/values/*.xml – strings, themes, colors

Tests
- Unit Tests – app/src/test/java/com/example/timelinter/
  - `AIMemorySimpleTest.kt`, `AIMemoryScenarioTest.kt` – memory behaviors
  - `TokenBucketTest.kt` – token bucket logic
  - `CoachNameUnitTest.kt`, `ApiKeyManagerCoachNameTest.kt`

- Instrumented Tests – app/src/androidTest/java/com/example/timelinter/
  - `AIMemoryInstrumentedTest.kt`, `AIMemoryQuickTest.kt`, `AIMemoryConversationFlowInstrumentedTest.kt`
  - `AILogScreenEditingTest.kt` – in‑tab permanent memory editing
  - `AIMemoryEditingInstrumentedTest.kt` – manager replace behavior and temp integrity
  - `AIMemoryRulesAndTempUITest.kt` – rules editor and temp grouping in UI
  - `FakeTimeProvider.kt`, `TimeFlowsTest.kt`

Event Log
- app/src/main/java/com/example/timelinter/EventLogStore.kt
  - Centralized event log with reverse-chronological entries and in-memory search.
  - Event types: `MESSAGE`, `TOOL`, `STATE`, `APP`, `BUCKET`, `SYSTEM`.
  - Public APIs: `logMessage`, `logTool`, `logStateChange`, `logSessionStarted`, `logSessionReset`, `logAppChanged`, `logNewWastefulAppDetected`, `logBucketRefilledAndReset`, `search`.

- UI integration in `AILogScreen`:
  - Shows a searchable event feed (most recent at the top) covering messages, tool calls, state transitions, app changes, and bucket resets.
  - Retains AI Memory editing and rules sections.

- Hooks
  - `ConversationHistoryManager` logs session start, user/model messages, tool calls, and resets.
  - `InteractionStateManager` logs state changes on transitions.
  - `AppUsageMonitorService` logs app changes, new wasteful app detection, and bucket refills that trigger conversation resets.

How Things Connect (Quick Navigation)
- Start monitoring: `MainActivity` toggles → `AppUsageMonitorService`
- Conversation boot: `ConversationHistoryManager.startNewSession()` builds initial API history
- AI call: `AIInteractionManager.generateResponse()` / `generateFromContents()`
- Tool handling: `AppUsageMonitorService` parses tools → updates `AIMemoryManager` and `ConversationLogStore`
- Memory in prompts: `ConversationHistoryManager.initializeConversation()` uses `ai_memory_template` with `AIMemoryManager.getAllMemories()`
- UI observation: `ConversationLogStore.apiHistory` and `aiMemory` consumed by `AILogScreen`

Conventions
- Memory keys: permanent (`permanent_memory`), temporary (`temp_memory_<ts>`), rules (`memory_rules`).
- Grouping key for temporary memory: ISO date `yyyy-MM-dd` of expiration.
- All Compose screens follow: title, body, and optional top‑bar actions; tests target nodes via `testTag` and text.

