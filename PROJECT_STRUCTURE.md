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
    - `AIConfigScreen` (AI model configuration)
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
  - Includes good apps reward settings (reward interval, reward amount, max overfill, decay rate).

- app/src/main/java/com/example/timelinter/GoodAppSelectionScreen.kt
  - Screen for selecting beneficial apps that provide time rewards.
  - Similar to AppSelectionScreen but for good apps.

Background Services and Flow
- app/src/main/java/com/example/timelinter/AppUsageMonitorService.kt
  - Foreground service managing thresholds (token bucket), notifications, conversation lifecycle, and AI calls.
  - Initializes:
    - `InteractionStateManager` (state machine)
    - `ConversationHistoryManager` (system prompt, memory, user templates)
    - `AIInteractionManager` (Gemini model + calls)
  - Responds to tool commands (Allow/Remember) and updates storage (`AIMemoryManager`) and UI store (`ConversationLogStore`).
  - Good Apps integration:
    - Tracks `bucketGoodAppAccumulatedMs` for reward accumulation
    - Passes `isCurrentlyGoodApp` to TokenBucket.update()
    - Applies good app rewards and overfill decay through TokenBucket
    - Private `AppInfo` includes `isGoodApp` field

Reset Event (Bucket Refilled to Full)
- Trigger: When the token bucket transitions from empty (<=0) back to full (== max) during non‑wasteful time accumulation.
  - Code: `AppUsageMonitorService.updateTimeTracking(...)` → `TokenBucket.update(...)` → check `becameFull`.
- Actions:
  1) Review active API history to extract memories (permanent or temporary) via an AI prompt
     - Code: `AppUsageMonitorService.tryArchiveMemoriesThenClear(...)` → adds API‑only prompt → `aiManager.generateFromContents(...)` → `AIMemoryManager.addPermanentMemory(...)` when appropriate.
  2) Reset active histories (both API and user‑visible); the persistent log tab is not cleared
     - Code: `ConversationHistoryManager.clearHistories()` (publishes reset and leaves `ConversationLogStore` intact).
  3) Conversation returns to observing; the next interaction will be a First Message, not a continuation
     - Code: `InteractionStateManager.resetToObserving()` → later `handleObservingState(...)` calls `startNewSession(...)` and `generateAIResponse(..., AITask.FIRST_MESSAGE)`.
  4) Log and notifications
     - Code: `EventLogStore.logBucketRefilledAndReset()` and notification cleanup in `AppUsageMonitorService`.

- app/src/main/java/com/example/timelinter/BrowserUrlAccessibilityService.kt
  - AccessibilityService to read browser URL signals (wasteful or not).

AI and Conversation
- app/src/main/java/com/example/timelinter/AIInteractionManager.kt
  - Wraps Google AI GenerativeModel calls (initialization, subsequent calls, error handling).
  - Uses `AIConfigManager` to get the configured model for each task.
  - Sends composed history from `ConversationHistoryManager`.
  - Supports switching between different AI models for different tasks.

- app/src/main/java/com/example/timelinter/ConversationHistoryManager.kt
  - Owns two histories: user‑visible vs API (model) history.
  - Builds initial AI turn:
    1) System prompt
    2) AI Memory message using `AIMemoryManager.getAllMemories()` and `ai_memory_template`
    3) User status message (`user_info_template` + `gemini_user_template`)
  - Includes good apps list in AI initialization (AUTOMATED_DATA section):
    - Automatically fetches good apps from `GoodAppManager` during session start
    - Adds to system context as "Good apps to suggest instead of wasteful ones: [names]"
    - Omits entirely when no good apps configured
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
  - Good Apps settings:
    - `getMaxOverfillMinutes` / `setMaxOverfillMinutes` - maximum bonus time (default: 30 min)
    - `getOverfillDecayPerHourMinutes` / `setOverfillDecayPerHourMinutes` - overfill decay rate (default: 10 min/hr)
    - `getGoodAppRewardIntervalMinutes` / `setGoodAppRewardIntervalMinutes` - interval to earn rewards (default: 5 min)
    - `getGoodAppRewardAmountMinutes` / `setGoodAppRewardAmountMinutes` - reward amount (default: 10 min)
    - `getGoodAppAccumulatedMs` / `setGoodAppAccumulatedMs` - persistence for reward accumulator

- app/src/main/java/com/example/timelinter/ApiKeyManager.kt
  - Stores API key, coach name, and user notes.

AI Configuration
- app/src/main/java/com/example/timelinter/AIConfigManager.kt
  - Manages AI model configuration per task type (conversation, summary, analysis).
  - Stores configurations in SharedPreferences.
  - Supports export/import of configurations as JSON.
  - Provides default model configurations for each task.

- app/src/main/java/com/example/timelinter/AITask.kt
  - Enum defining different types of AI tasks (FIRST_MESSAGE, FOLLOWUP_NO_RESPONSE, USER_RESPONSE).
  - Each task corresponds to a stage in the conversation flow and can have a different AI model configured.

- app/src/main/java/com/example/timelinter/AIProvider.kt
  - Enum for AI providers (GOOGLE_AI, OPENAI, ANTHROPIC, CUSTOM).

- app/src/main/java/com/example/timelinter/AIModelConfig.kt
  - Data class for AI model configuration.
  - Contains model name, display name, provider, description, and optional parameters.
  - Defines available pre-configured models and default configurations.

- app/src/main/java/com/example/timelinter/AIConfigScreen.kt
  - UI screen for configuring AI models per task.
  - Allows selection of different models for different tasks.
  - Provides export/import functionality for configurations.
  - Accessible from main screen via Settings icon in top bar.

Wasteful Apps, Good Apps, and Token Bucket
- app/src/main/java/com/example/timelinter/TimeWasterAppManager.kt
  - Tracks selected apps considered wasteful; resolves labels and persistence.

- app/src/main/java/com/example/timelinter/GoodAppManager.kt
  - Tracks selected apps considered beneficial (good apps); provides time rewards.
  - Caches display names in SharedPreferences to avoid repeated PackageManager lookups.
  - `getSelectedAppDisplayNames()` returns nullable List<String>: null if no apps configured, empty if all uninstalled.
  - Similar structure to TimeWasterAppManager but for rewarding good behavior.

- app/src/main/java/com/example/timelinter/TokenBucket.kt
  - Threshold accounting used by the service for triggering conversations.
  - Supports good apps feature:
    - Overfilling beyond normal max threshold
    - Time rewards for using good apps
    - Decay of overfill over time
  - `TokenBucketConfig` includes good app parameters: maxOverfillMs, overfillDecayPerHourMs, goodAppRewardIntervalMs, goodAppRewardAmountMs
  - `TokenBucketUpdateResult` includes goodAppAccumulatedMs for tracking reward progress.

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
  - `TokenBucketTest.kt` – token bucket logic (basic)
  - `GoodAppsTokenBucketTest.kt` – comprehensive tests for good apps feature (overfill, decay, rewards)
  - `CoachNameUnitTest.kt`, `ApiKeyManagerCoachNameTest.kt`

- Instrumented Tests – app/src/androidTest/java/com/example/timelinter/
  - `AIMemoryInstrumentedTest.kt`, `AIMemoryQuickTest.kt`, `AIMemoryConversationFlowInstrumentedTest.kt`
  - `AILogScreenEditingTest.kt` – in‑tab permanent memory editing
  - `AIMemoryEditingInstrumentedTest.kt` – manager replace behavior and temp integrity
  - `AIMemoryRulesAndTempUITest.kt` – rules editor and temp grouping in UI
  - `FakeTimeProvider.kt`, `TimeFlowsTest.kt`
  - `GoodAppsIntegrationTest.kt` – full integration tests for good apps (manager, settings, bucket)
  - `GoodAppsAIContextTest.kt` – tests that good apps list is included in AI initialization (not repeated in each message)
  - `GoodAppManagerCacheTest.kt` – tests display name caching and nullability behavior

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

