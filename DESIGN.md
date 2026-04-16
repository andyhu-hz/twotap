# TwoTap — Design & Implementation (for humans & AI)

本文描述 **设计意图、状态机、手势管线、ROM 差异与关键标志位**，便于代码审阅与 AI 辅助修改时快速对齐上下文。

---

## 1. Product intent

**User goal**: Trigger a two-finger touch pattern from hardware keys without a visible overlay:

1. **First combo** (VolUp + VolDown within a time window, order-independent): simulate **two fingers down** (left ~30% width, right ~70% width, vertical mid), then **lift left**, **keep right finger down** (long press semantics for games/UI).
2. **Second combo** while logically “holding”: **release** right finger, return to idle; repeatable.

**Why not Power key**: Many devices do not deliver `KEYCODE_POWER` to accessibility services; **volume keys** are reliable with `FLAG_REQUEST_FILTER_KEY_EVENTS`.

---

## 2. High-level architecture

| Piece | Role |
|--------|------|
| `MainActivity` | One-shot launcher: if accessibility disabled → open system accessibility settings + Toast; else Toast + `finish()`. Transparent theme. |
| `TwoTapService` | `AccessibilityService`: `onKeyEvent` for combo detection; `dispatchGesture` for `GestureDescription` strokes. |
| `res/xml/accessibility_service_config.xml` | Service metadata: `canRequestFilterKeyEvents`, etc. |
| `AndroidManifest.xml` | Declares service with `BIND_ACCESSIBILITY_SERVICE`. |

**Min SDK**: 26 — required for multi-stroke gestures and `continueStroke` (API 26+).

---

## 3. Combo key detection (`onKeyEvent`)

- Listens to `ACTION_DOWN` only for `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN`.
- Stores last timestamp per key; clears stale opposite key if outside **`COMBO_WINDOW_MS`** (1200 ms).
- Combo fires when **both** keys have timestamps and `abs(up - down) <= COMBO_WINDOW_MS`; then both timestamps cleared.
- If **`gestureReady == false`**: consume event, log cooldown, **no state transition** (post-release debounce).

---

## 4. State machine (`TwoTapService`)

Enum **`State`**:

| State | Meaning |
|--------|---------|
| `IDLE` | No active “start sequence”; first combo begins hold flow. |
| `HOLDING` | Phase 1/2 or continue chain in progress; second combo → `RELEASING`. |
| `RELEASING` | User requested release; next `continueRightHold` segment uses short duration + `willContinue=false`. |

**`gestureReady`**: After successful release or sync lift completion, false for **`POST_RELEASE_COOLDOWN_MS`** (250 ms) to avoid accidental double triggers.

**`phase3Active`**: Set when phase-2 completes and continue chain is intended (logging / release path context).

---

## 5. Gesture pipeline (happy path)

All timings in **`TwoTapService` companion object**.

### Phase 1 — `startPhase1()`

- Single stroke: **right finger only**, **`PHASE1_MS`** (300 ms).
- On success and `state == HOLDING` → **`startPhase2DualFingerStartChain()`**.
- On cancel / dispatch failure → **`resetState()`**.

### Phase 2 — `startPhase2DualFingerStartChain()`

- Two strokes, **same duration** **`PHASE2_MS`** (300 ms): left path + right path.
- **Critical**: right stroke uses **`GestureDescription.StrokeDescription(..., willContinue = true)`** so the gesture engine expects **`continueStroke`** to extend the same logical finger.
- On `onCompleted` while `HOLDING`: save **`currentRightStroke`** reference to that **right** `StrokeDescription` instance (must be the object from this gesture), set `phase3Active`, then **`handler.post { continueRightHold() }`** — avoid synchronous `dispatchGesture` directly inside `onCompleted` stack where possible.

### Phase 3 — `continueRightHold()` (API O+)

- Builds next segment via **`prev.continueStroke(path, 0, duration, willContinue)`**.
- **Holding**: `duration = HOLD_SEGMENT_MS` (500 ms), `willContinue = true`.
- **Releasing** (`state == RELEASING`): short **`LIFT_MS`** (50 ms), `willContinue = false` to lift.
- On segment **`onCompleted`** (holding): `handler.post` → recurse **`continueRightHold()`** if state still `HOLDING` or `RELEASING`.
- On segment **`onCancelled`** while **not** releasing → **`onBrokenContinueChain()`** (see §6).

**Why same duration in phase 2**: On some OEMs, multi-stroke gesture `onCompleted` fires when the **shortest** stroke ends; equal durations align `onCompleted` with the end of the dual-finger window.

---

## 6. Broken continue chain & `consumeNextIdleComboAsLiftOnly`

### Observed OEM behavior (e.g. ColorOS / OPLUS)

The **first** `dispatchGesture` after phase 2 for `continueStroke` is often **`onCancelled` within a few ms**. Logical state must not assume “finger is up” while the system may still treat **`willContinue`** right stroke as pending.

### `onBrokenContinueChain()`

- Clears `currentRightStroke`, `phase3Active`, sets `state = IDLE`, `gestureReady = true`.
- Sets **`consumeNextIdleComboAsLiftOnly = true`**.
- **Does not** auto-dispatch a lift gesture here: a **new** gesture immediately cancels the lingering `willContinue` injection on some devices, reducing touch to ~600 ms total (“tap then idle”). User explicitly requested avoiding that regression.

### Next combo while `IDLE` and flag true

- Log **`▣ 消耗双键：仅补抬手`**.
- **`forceLiftRightFinger(..., clearConsumeAfterSuccess = true)`**: short tap same coordinates to cancel orphan injection.
- On lift **`onCompleted`**: clear flag, apply **`gestureReady`** cooldown.

### Next combo after flag cleared

- Normal **`▶ 开始 [IDLE→HOLDING]`** → phase 1 again.

This yields the user-visible pattern on broken-continue devices: **start → (optional) sync lift combo → start again**.

---

## 7. `forceLiftRightFinger`

- **`LIFT_MS`** tap at `(rightX, cy)` — same point as hold.
- Used for: user-initiated sync after broken chain; normal release path uses **`continueRightHold`** short segment when `RELEASING` (not necessarily the same code path, but same idea).

---

## 8. Constants (source of truth)

| Constant | Value | Purpose |
|----------|-------|---------|
| `PHASE1_MS` | 300 | Right-only intro stroke. |
| `PHASE2_MS` | 300 | Dual-finger window; right `willContinue`. |
| `HOLD_SEGMENT_MS` | 500 | Continue segment length while holding. |
| `LIFT_MS` | 50 | Release / sync tap duration. |
| `COMBO_WINDOW_MS` | 1200 | Max gap between the two volume keys. |
| `POST_RELEASE_COOLDOWN_MS` | 250 | Ignore combos briefly after release/sync lift. |

---

## 9. Logging (Log tag `TwoTap`)

Useful strings for support:

- `▶ 开始 [IDLE→HOLDING]` — new session from idle (no consume flag).
- `■ 停止 [HOLDING→RELEASING]` — user second combo while holding.
- `续接链断裂` — continue segment cancelled / dispatch failed; **`consumeNextIdleComboAsLiftOnly`** set.
- `▣ 消耗双键：仅补抬手` — idle combo consumed as sync lift.
- `补抬手完成` — lift gesture completed.

---

## 10. Files map

```
app/src/main/java/com/example/twotap/MainActivity.kt   # Permission UX
app/src/main/java/com/example/twotap/TwoTapService.kt # Core logic
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/values/strings.xml                   # Service label
app/build.gradle                                      # applicationVariants → twotap.apk / twotap-debug.apk
```

---

## 11. Known limitations & non-goals

- **`continueStroke`** reliability is **device-dependent**. Design intentionally keeps phase-2 `willContinue` + optional continue chain for devices where it works; broken path uses **consume-next-combo lift** without auto-lift on break.
- Max gesture duration (~60 s) and injection policies are OS-defined; this app does not attempt root/shizuku.
- **Not** changing `applicationId` in this doc; release signing is owner’s responsibility.

---

## 12. Safe modification hints (for AI agents)

1. **Do not** reintroduce **automatic** `forceLiftRightFinger` inside `onBrokenContinueChain()` without re-reading §6 — regression verified on device.
2. Any new `dispatchGesture` while `willContinue` may still be active: assume **cancellation** of prior injection.
3. Keep **`currentRightStroke`** referencing the stroke instance from the **same** `GestureDescription` that created it for `continueStroke` rules.
4. `@Volatile` on fields touched from `onKeyEvent` vs main handler callbacks — preserve happens-before for combo vs gesture threads.
5. If changing combo keys, update **`README.md`** and **`accessibility_service_config`** / user strings consistently.

---

## 13. Build output naming

`app/build.gradle` uses `applicationVariants.configureEach` to set **`output.outputFileName`**:

- **release** → `twotap.apk`
- **debug** → `twotap-debug.apk`
