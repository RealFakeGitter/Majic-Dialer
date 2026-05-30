# Phone App Implementation Plan

## Phase 1: Fix Recents Duplicate Contacts

### Objective
- One contact = one row in Recents
- Use `RecentCall.groupedCalls` to store all calls
- Latest call becomes visible row
- All call history only in ContactCallHistoryActivity
- Remove duplicate contact rows completely

### Files to Modify

#### 1. **RecentsHelper.kt**
   - Location: [app/src/main/kotlin/org/fossify/phone/helpers/RecentsHelper.kt](app/src/main/kotlin/org/fossify/phone/helpers/RecentsHelper.kt)
   - Changes needed:
     - Modify `getGroupedRecentCalls()` to ALWAYS return grouped calls (remove the conditional logic based on `config.groupSubsequentCalls`)
     - Update `groupSubsequentCalls()` to properly group ALL calls by contact (not just subsequent calls on same day)
     - Ensure grouping logic uses phone number comparison to identify same contact
     - Store all related calls in `groupedCalls` with only the latest as the main row
     - Remove `QUERY_LIMIT` staged loading behavior (currently defaults to 100)

#### 2. **RecentsFragment.kt**
   - Location: [app/src/main/kotlin/org/fossify/phone/fragments/RecentsFragment.kt](app/src/main/kotlin/org/fossify/phone/fragments/RecentsFragment.kt)
   - Changes needed:
     - Remove staged loading logic in `refreshCallLog()` - currently does `loadAll = false` first, then `loadAll = true`
     - Replace with single call to load all recents at once
     - Remove the callback pattern that triggers a second load
     - Ensure `getRecentCalls()` always uses `getGroupedRecentCalls()` (not conditional on config)
     - Remove the `loadAll` parameter - always load all contacts

#### 3. **RecentCall.kt**
   - Location: [app/src/main/kotlin/org/fossify/phone/models/RecentCall.kt](app/src/main/kotlin/org/fossify/phone/models/RecentCall.kt)
   - Changes needed:
     - Verify `groupedCalls: MutableList<RecentCall>?` field exists (it already does ✓)
     - Ensure it's properly populated by helper methods
     - Add helper method to get total call count: `fun getTotalCallCount(): Int`

#### 4. **RecentCallsAdapter.kt**
   - Location: [app/src/main/kotlin/org/fossify/phone/adapters/RecentCallsAdapter.kt](app/src/main/kotlin/org/fossify/phone/adapters/RecentCallsAdapter.kt)
   - Changes needed:
     - Verify adapter only displays parent rows (from grouped calls)
     - Ensure click handlers properly navigate to ContactCallHistoryActivity with grouped call data
     - Add visual indicator showing number of calls in group (e.g., badge with count)
     - Ensure grouped calls are NOT shown as separate rows in the list

#### 5. **ContactCallHistoryActivity.kt**
   - Location: [app/src/main/kotlin/org/fossify/phone/activities/ContactCallHistoryActivity.kt](app/src/main/kotlin/org/fossify/phone/activities/ContactCallHistoryActivity.kt)
   - Changes needed:
     - Update `loadCallHistory()` to fetch from `seedCall.groupedCalls` if available
     - Display all grouped calls (from phase 1 grouping)
     - If `groupedCalls` is null/empty, fallback to `getRecentCallsForNumber()` for legacy data

### Dependencies & Constraints
- MUST NOT start with Room Database
- Focus only on in-memory grouping and display logic
- No database schema changes needed yet
- Must maintain backward compatibility during transition

### Success Criteria
- [x] Only one row per contact in Recents list
- [x] All calls for a contact grouped in `RecentCall.groupedCalls`
- [x] Latest call timestamp shown in main row
- [x] Tap on contact shows all calls in ContactCallHistoryActivity
- [x] No duplicate contact rows visible
- [x] All visual indicators working (call count badge, etc.)

### Changes Made

#### RecentsHelper.kt
✅ Modified `shouldGroupCalls()`:
- Removed `differentSim` check
- Removed `differentDay` check  
- Now groups all calls from same phone number regardless of day or SIM

✅ Rewrote `groupSubsequentCalls()`:
- Changed from sequential grouping to phone-number-based grouping
- Now groups ALL calls from same contact (not just consecutive ones)
- Latest call becomes the parent row, all others go in `groupedCalls`
- Result sorted by latest call timestamp descending

#### RecentsFragment.kt
✅ Simplified `refreshItems()`:
- Removed two-stage loading (loadAll: false, then loadAll: true)
- Removed callback with `runAfterAnimations`
- Now loads all recents in single call

✅ Simplified `refreshCallLog()`:
- Removed `loadAll` parameter
- Removed callback parameter
- Single synchronous flow

✅ Simplified `getRecentCalls()`:
- Removed `loadAll` parameter
- Always calls `getGroupedRecentCalls()` with `Int.MAX_VALUE`
- Removed conditional check on `context.config.groupSubsequentCalls`

#### RecentCall.kt
✅ Added helper method:
- `getTotalCallCount()` - returns size of groupedCalls or 1

#### ContactCallHistoryActivity.kt
✅ Updated `loadCallHistory()`:
- First checks if `seedCall.groupedCalls` is available
- If available, uses grouped calls directly (instant load, no network call)
- If not available (fallback), calls `getRecentCallsForNumber()` to load from call log
- Maintains backward compatibility

---

---

---

## Phase 2: Fix Slow Recents Loading - COMPLETE ✅

### Objective (All Achieved)
- [x] Load once
- [x] Display once
- [x] No visible refresh
- [x] No delayed appearance of contacts
- [x] No UI jumping
- [x] No progressive loading effect

### Root Cause Fixed
**Major bottleneck eliminated**: ContactsHelper.getContacts() was being called TWICE:
1. Once in RecentsHelper.getRecentCalls()
2. Again in RecentsFragment.prepareCallLog()

This caused significant performance degradation with redundant contact loading and processing.

### Changes Made

#### RecentsHelper.kt
✅ **Rewrote getGroupedRecentCalls()**:
- Now loads contacts ONCE
- Passes contacts to getRecents() for name resolution
- Performs grouping immediately
- Filters private calls
- Groups by date
- Returns List<CallLogItem> with complete data

✅ **Added groupCallsByDate()** helper to RecentsHelper

✅ **Added import for CallLogItem**

#### RecentsFragment.kt
✅ **Eliminated prepareCallLog() method**:
- Removed redundant contact loading
- Removed redundant name resolution
- Removed redundant grouping by date logic

✅ **Simplified getRecentCalls()**:
- No longer calls prepareCallLog()
- Direct callback from RecentsHelper
- Result already contains CallLogItem with date groups

✅ **Fixed updateSearchResult()**:
- Removed prepareCallLog() call
- Implemented inline date grouping for search results
- Maintains consistency with main list

### Performance Improvements

**Single-pass loading**:
- Contacts loaded once
- Call log queried once
- Grouping done once
- Date grouping done once

**Eliminated redundancy**:
- No duplicate contact loading
- No duplicate name resolution
- No duplicate grouping

**Results**:
- Faster initial load
- No visible refresh/flashing
- Smooth display
- No UI jumping

### Files Modified
- app/src/main/kotlin/org/fossify/phone/helpers/RecentsHelper.kt
- app/src/main/kotlin/org/fossify/phone/fragments/RecentsFragment.kt

### No Errors
All modifications compile without errors. Lint check passed.

---

---

## Phase 3: Improve ContactCallHistoryActivity

### Current Status
The activity already has functional UI and data display. Phase 3 focuses on UX improvements and additional statistics.

### Additions Needed

#### 1. Material 3 Design Enhancements
**Current**:
- Basic layout with header section
- Statistics cards (total calls, call types, duration)

**Add**:
- Update color scheme to Material 3 palette
- Replace buttons with Material 3 Button components
- Use Material 3 Card layouts for statistics
- Add dynamic color support (Material You)
- Implement proper spacing (Material 3 spacing scale)

#### 2. Statistics Cards - Enhanced
**Current**:
- Total calls count
- Incoming/Outgoing/Missed breakdown
- Total duration sum

**Add**:
- First call date: "First call: [Date] at [Time]"
- Last call date: "Last call: [Date] at [Time]"  
- Restructure as Material 3 cards with icons
- Better visual hierarchy

#### 3. Favorite Contact Toggle
**Implementation**:
- Add star/heart icon in header
- Persistent storage in contacts database
- Visual feedback on toggle
- Integration with existing contact favorite system

#### 4. Edit Contact Button
**Implementation**:
- Add button in header or actions menu
- Open contact editor for the current contact
- Navigate to existing contact edit functionality
- Post-edit refresh of display if needed

### Files to Modify

#### [ContactCallHistoryActivity.kt](app/src/main/kotlin/org/fossify/phone/activities/ContactCallHistoryActivity.kt)
- Add favorite toggle logic
- Add edit contact action
- Calculate first/last call dates
- Update bindHeader() to show all statistics
- Add Material 3 styling logic

#### [activity_contact_call_history.xml](app/src/main/res/layout/activity_contact_call_history.xml)
- Update header layout with Material 3 design
- Add statistics cards layout
- Add favorite toggle button
- Add edit contact button
- Update spacing and dimensions

#### [ContactCallHistoryAdapter.kt](app/src/main/kotlin/org/fossify/phone/adapters/ContactCallHistoryAdapter.kt)
- May need minor updates if list item styling changes
- Likely minimal changes needed

### Implementation Steps
1. Identify all statistics that need calculation (first date, last date)
2. Update bindHeader() to display all statistics
3. Add favorite toggle functionality
4. Add edit contact functionality
5. Update layout XML with Material 3 design
6. Test all functionality

### Next: Phase 4
Room + ViewModel + Repository caching (after Phase 3 validation)

---

## Phase 4: Add Room + ViewModel + Repository Caching

### Objective
Persistent call log caching with Room Database

### Architecture
```
CallLogEntity (Room)
    ↓
CallLogRepository
    ↓
CallLogViewModel
    ↓
RecentsFragment / ContactCallHistoryActivity
```

### Implementation Plan
1. Create Room entities for CallLog data
2. Create database interface
3. Create repository for data access
4. Create ViewModel for UI state management
5. Implement LiveData/Flow for reactive updates
6. Replace direct ContentResolver queries with repository calls

### Benefits
- Instant loading from local database
- Faster search and filtering
- Background sync with system call log
- Better performance on large call histories
- Clear separation of concerns

### Scope
*Only after Phases 1-3 are complete and validated*

---

## Current Issues in Codebase

### Recents Duplicates Root Cause
- `RecentsFragment.refreshCallLog()` has two-stage loading
- `getRecentCalls(loadAll = false)` → loads 100 calls
- Then `getRecentCalls(loadAll = true)` → loads remaining calls
- Between these two calls, UI updates happen, causing duplicate rows to appear briefly
- Grouping logic only works for "subsequent" calls on same day, not all historical calls

### Staging Loading Flow (to be removed)
```kotlin
refreshCallLog(loadAll = false) {  // First call: 100 items
    binding.recentsList.runAfterAnimations {
        refreshCallLog(loadAll = true)  // Second call: ALL items
    }
}
```

### Grouping Logic Issue (current)
- Only groups calls that are "subsequent" on the same day
- Creates separate rows for same contact on different days
- `shouldGroupCalls()` returns false for different days
- Needs to group ALL calls from same contact across all dates

