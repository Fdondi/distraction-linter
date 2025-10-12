# AI Memory Usage Guide

## Summary

This document explains how temporary and permanent memory work in TimeLinter, and clarifies a common misconception about their usage.

## The Question

**Is temporary memory ever used?**
- **YES!** Temporary memory IS used and IS sent to the AI.
- Both temporary and permanent memories are retrieved via `getAllMemories()` and included in every AI conversation.

## How It Works

### Memory Retrieval Flow

1. When a conversation starts (`ConversationHistoryManager.initializeConversation()`):
   ```kotlin
   val memories = AIMemoryManager.getAllMemories(context)  // Line 105
   ```

2. `getAllMemories()` returns BOTH permanent and temporary (non-expired) memories combined
3. These memories are sent to the AI via the `ai_memory_template.txt`
4. Expired temporary memories are automatically cleaned up during retrieval

### Code References

- **Memory retrieval**: `AIMemoryManager.kt` line 122-161 (`getAllMemories()`)
- **Conversation init**: `ConversationHistoryManager.kt` line 94-115
- **Tool processing**: `AppUsageMonitorService.kt` line 398-410

## The Problem

The AI was saving **temporary situations** as **permanent memory**. For example:

❌ **WRONG**:
```kotlin
ToolCommand.Remember(
    content = "User is working for Google temporarily and wants to make a good impression.",
    durationMinutes = null  // Saved forever!
)
```

✅ **CORRECT**:
```kotlin
ToolCommand.Remember(
    content = "User is working for Google temporarily and wants to make a good impression.",
    durationMinutes = 10080  // 7 days = 7 * 24 * 60 minutes
)
```

## When to Use Each Type

### Use TEMPORARY Memory (with duration)

Use when the information has a time limit:
- **Temporary situations**: "working temporarily", "on vacation", "sick today"
- **Current context**: "currently focused on X", "taking a break", "in a meeting"
- **Short-term plans**: "deadline this week", "project due Friday"
- **Time-bound goals**: "trying to avoid X today", "focusing on Y this afternoon"

**Duration examples**:
- Current activity: 60-120 minutes
- Today only: 480-720 minutes (8-12 hours)
- This week: 10080 minutes (7 days)
- This month: 43200 minutes (30 days)

### Use PERMANENT Memory (no duration)

Use for information that should persist indefinitely:
- **Persistent preferences**: "prefers working in morning", "doesn't like interruptions"
- **Long-term goals**: "wants to learn programming", "building a business"
- **User characteristics**: "works from home", "has kids", "studies at night"
- **Lasting constraints**: "can't use phone at work", "meditation every morning"

## The Fix

### Updated AI Memory Rules

The file `app/src/main/res/raw/ai_memory_rules.txt` was updated with:
- Clear examples of when to use temporary vs permanent memory
- Specific guidance: if content mentions "temporarily", "currently", "today", "this week" → use temporary memory
- Example duration calculations

### Updated Tests

Test files were updated to:
- Remove examples where "temporarily" was incorrectly saved as permanent
- Add correct examples demonstrating temporary memory usage
- Include test `testTemporarySituationMemoryShouldUseTemporaryMemory()` showing the RIGHT way

## Good Apps Are NOT Memory

**Important**: Good apps list is NOT stored in AI memory at all!

Good apps are:
- Stored separately via `GoodAppManager`
- Automatically injected into EVERY conversation initialization as `AUTOMATED_DATA`
- Updated dynamically without using the memory system

See: `ConversationHistoryManager.kt` lines 125-131

## Testing

To verify temporary memory works correctly:

```kotlin
// Add temporary memory
AIMemoryManager.addTemporaryMemory(context, "User on vacation", durationMinutes = 1440) // 1 day

// Retrieve immediately - should be present
val memories = AIMemoryManager.getAllMemories(context)
assertTrue(memories.contains("User on vacation"))

// Fast-forward time beyond duration
fakeTime.advanceMinutes(1441)

// Retrieve again - should be gone
val expiredMemories = AIMemoryManager.getAllMemories(context)
assertFalse(expiredMemories.contains("User on vacation"))
```

## Conclusion

- ✅ Temporary memory IS used - it's retrieved and sent to the AI
- ✅ The code infrastructure works correctly
- ✅ The problem was the AI wasn't instructed clearly on WHEN to use it
- ✅ Fixed by improving `ai_memory_rules.txt` with clear examples
- ✅ Fixed by correcting test examples to demonstrate proper usage

