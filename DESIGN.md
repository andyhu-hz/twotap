# TwoTap — Design & Implementation

*English section — structure for code review and AI-assisted edits.*

## 1. Product intent

**User goal**: Trigger a two-finger touch pattern from hardware keys without a visible overlay:

1. **First combo** (VolUp + VolDown within a time window, order-independent): simulate **two fingers down** (left ~30% width, right ~70% width, vertical mid), then **lift left**, **keep right finger down** (long press semantics for games/UI).
2. **Second combo** while logically “holding”: **release** right finger, return to idle; repeatable.

**Why not Power key**: Many devices do not deliver `KEYCODE_POWER` to accessibility services; **volume keys** are reliable with `FLAG_REQUEST_FILTER_KEY_EVENTS`.

## 2. High-level architecture

| Piece | Role |
|--------|------|
| `MainActivity` | One-shot launcher: if accessibility disabled → open system accessibility settings + Toast; else Toast + `finish()`. Transparent theme. |
| `TwoTapService` | `AccessibilityService`: `onKeyEvent` for combo detection; `dispatchGesture` for `GestureDescription` strokes. |
| `res/xml/accessibility_service_config.xml` | Service metadata: `canRequestFilterKeyEvents`, etc. |
| `AndroidManifest.xml` | Declares service with `BIND_ACCESSIBILITY_SERVICE`. |

**Min SDK**: 26 — required for multi-stroke gestures and `continueStroke` (API 26+).

## 3. Combo key detection (`onKeyEvent`)

- Listens to `ACTION_DOWN` only for `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN`.
- Stores last timestamp per key; clears stale opposite key if outside **`COMBO_WINDOW_MS`** (1200 ms).
- Combo fires when **both** keys have timestamps and `abs(up - down) <= COMBO_WINDOW_MS`; then both timestamps cleared.
- If **`gestureReady == false`**: consume event, log cooldown, **no state transition** (post-release debounce).

## 4. State machine (`TwoTapService`)

Enum **`State`**:

| State | Meaning |
|--------|---------|
| `IDLE` | No active “start sequence”; first combo begins hold flow. |
| `HOLDING` | Phase 1/2 or continue chain in progress; second combo → `RELEASING`. |
| `RELEASING` | User requested release; next `continueRightHold` segment uses short duration + `willContinue=false`. |

**`gestureReady`**: After successful release or sync lift completion, false for **`POST_RELEASE_COOLDOWN_MS`** (250 ms) to avoid accidental double triggers.

**`phase3Active`**: Set when phase-2 completes and continue chain is intended (logging / release path context).

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

## 7. `forceLiftRightFinger`

- **`LIFT_MS`** tap at `(rightX, cy)` — same point as hold.
- Used for: user-initiated sync after broken chain; normal release path uses **`continueRightHold`** short segment when `RELEASING` (not necessarily the same code path, but same idea).

## 8. Constants (source of truth)

| Constant | Value | Purpose |
|----------|-------|---------|
| `PHASE1_MS` | 300 | Right-only intro stroke. |
| `PHASE2_MS` | 300 | Dual-finger window; right `willContinue`. |
| `HOLD_SEGMENT_MS` | 500 | Continue segment length while holding. |
| `LIFT_MS` | 50 | Release / sync tap duration. |
| `COMBO_WINDOW_MS` | 1200 | Max gap between the two volume keys. |
| `POST_RELEASE_COOLDOWN_MS` | 250 | Ignore combos briefly after release/sync lift. |

## 9. Logging (Log tag `TwoTap`)

Useful strings for support:

- `▶ 开始 [IDLE→HOLDING]` — new session from idle (no consume flag).
- `■ 停止 [HOLDING→RELEASING]` — user second combo while holding.
- `续接链断裂` — continue segment cancelled / dispatch failed; **`consumeNextIdleComboAsLiftOnly`** set.
- `▣ 消耗双键：仅补抬手` — idle combo consumed as sync lift.
- `补抬手完成` — lift gesture completed.

## 10. Files map

```
app/src/main/java/com/example/twotap/MainActivity.kt   # Permission UX
app/src/main/java/com/example/twotap/TwoTapService.kt # Core logic
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/values/strings.xml                   # Service label
app/build.gradle                                      # applicationVariants → twotap.apk / twotap-debug.apk
```

## 11. Known limitations & non-goals

- **`continueStroke`** reliability is **device-dependent**. Design intentionally keeps phase-2 `willContinue` + optional continue chain for devices where it works; broken path uses **consume-next-combo lift** without auto-lift on break.
- Max gesture duration (~60 s) and injection policies are OS-defined; this app does not attempt root/shizuku.
- **Not** changing `applicationId` in this doc; release signing is owner’s responsibility.

## 12. Safe modification hints (for AI agents)

1. **Do not** reintroduce **automatic** `forceLiftRightFinger` inside `onBrokenContinueChain()` without re-reading §6 — regression verified on device.
2. Any new `dispatchGesture` while `willContinue` may still be active: assume **cancellation** of prior injection.
3. Keep **`currentRightStroke`** referencing the stroke instance from the **same** `GestureDescription` that created it for `continueStroke` rules.
4. `@Volatile` on fields touched from `onKeyEvent` vs main handler callbacks — preserve happens-before for combo vs gesture threads.
5. If changing combo keys, update **`README.md`** and **`accessibility_service_config`** / user strings consistently.

## 13. Build output naming

`app/build.gradle` uses `applicationVariants.configureEach` to set **`output.outputFileName`**:

- **release** → `twotap.apk`
- **debug** → `twotap-debug.apk`

---

# 设计与实现（中文）

*以下章节与上文英文部分一一对应，便于中文读者与双语检索。*

## 1. 产品目标

**用户目标**：不依赖屏幕悬浮控件，用实体键触发「双指起手 + 单指长按」：

1. **第一次双键**（音量加、音量减在时间窗内先后按下，**顺序不限**）：模拟**双指按下**（左约屏宽 30%、右约 70%，竖直中线），再**抬起左指**、**右指保持按住**（游戏/UI 意义上的长按）。
2. **第二次双键**（逻辑仍处于「按住」流程时）：**抬起**右指并回到空闲；可重复。

**为何不用电源键**：多数设备不会把 `KEYCODE_POWER` 交给无障碍服务；**音量键**在声明 `FLAG_REQUEST_FILTER_KEY_EVENTS` 后较可靠。

## 2. 总体架构

| 模块 | 作用 |
|--------|------|
| `MainActivity` | 一次性入口：未开无障碍则跳转系统无障碍设置并 Toast；已开启则 Toast 后 `finish()`。透明主题。 |
| `TwoTapService` | `AccessibilityService`：`onKeyEvent` 识别组合键；`dispatchGesture` 注入 `GestureDescription`。 |
| `res/xml/accessibility_service_config.xml` | 服务元数据（如 `canRequestFilterKeyEvents`）。 |
| `AndroidManifest.xml` | 声明服务及 `BIND_ACCESSIBILITY_SERVICE`。 |

**最低 SDK**：26 — 多 stroke 手势与 `continueStroke`（API 26+）所需。

## 3. 组合键检测（`onKeyEvent`）

- 仅处理 `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` 的 **`ACTION_DOWN`**。
- 为两键各记时间戳；若另一键时间戳过旧（超过 **`COMBO_WINDOW_MS`**，1200 ms）则清空。
- 两键均有有效时间戳且 `abs(up - down) <= COMBO_WINDOW_MS` 即视为一次双键，随后清空两时间戳。
- 若 **`gestureReady == false`**：仍消费事件、打日志，**不改变状态**（释放/补抬后的防抖）。

## 4. 状态机（`TwoTapService`）

枚举 **`State`**：

| 状态 | 含义 |
|--------|------|
| `IDLE` | 未在跑「开始序列」；第一次双键进入按住流程。 |
| `HOLDING` | 阶段 1/2 或续接链进行中；此时第二次双键 → `RELEASING`。 |
| `RELEASING` | 用户要松手；下一段 `continueRightHold` 用短时长 + `willContinue=false` 抬起。 |

**`gestureReady`**：正常释放或补抬手势 **`onCompleted`** 后，在 **`POST_RELEASE_COOLDOWN_MS`**（250 ms）内置为 false，避免连触。

**`phase3Active`**：阶段 2 完成且准备进入续接链时置位（日志与释放路径上下文）。

## 5. 手势管线（主路径）

时长常量均在 **`TwoTapService` companion object**。

### 阶段 1 — `startPhase1()`

- 单 stroke：**仅右指**，**`PHASE1_MS`**（300 ms）。
- 成功且 `state == HOLDING` → **`startPhase2DualFingerStartChain()`**。
- 取消或 `dispatchGesture` 失败 → **`resetState()`**。

### 阶段 2 — `startPhase2DualFingerStartChain()`

- 两指 stroke，**时长相同** **`PHASE2_MS`**（300 ms）：左路径 + 右路径。
- **要点**：右指 **`StrokeDescription(..., willContinue = true)`**，引擎期待后续 **`continueStroke`** 延长同一逻辑手指。
- `onCompleted` 且 `HOLDING`：保存本手势中的右指 **`currentRightStroke`**（必须是该 `GestureDescription` 里的实例）、`phase3Active = true`，再 **`handler.post { continueRightHold() }`**，尽量避免在 `onCompleted` 栈内同步再 `dispatchGesture`。

### 阶段 3 — `continueRightHold()`（API 26+）

- 通过 **`prev.continueStroke(path, 0, duration, willContinue)`** 生成下一段。
- **按住中**：`duration = HOLD_SEGMENT_MS`（500 ms），`willContinue = true`。
- **释放中**（`state == RELEASING`）：**`LIFT_MS`**（50 ms），`willContinue = false` 抬手。
- 段 **`onCompleted`**（非释放）：`handler.post` 后若状态仍为 `HOLDING`/`RELEASING` 则递归 **`continueRightHold()`**。
- 段 **`onCancelled`** 且非释放分支 → **`onBrokenContinueChain()`**（见 §6）。

**阶段 2 左右同长的原因**：部分厂商整段手势的 `onCompleted` 取决于**最短** stroke；同长可使 `onCompleted` 与双指窗口结束对齐。

## 6. 续接链断裂与 `consumeNextIdleComboAsLiftOnly`

### 部分 ROM 上的表现（如 ColorOS / OPLUS）

阶段 2 之后**首段** `continueStroke` 的 `dispatchGesture` 常在**数毫秒内**收到 **`onCancelled`**。逻辑上不能假定「手指已抬起」，系统仍可能把 **`willContinue`** 的右指视为待续接。

### `onBrokenContinueChain()`

- 清空 `currentRightStroke`、`phase3Active`，`state = IDLE`，`gestureReady = true`。
- 置 **`consumeNextIdleComboAsLiftOnly = true`**。
- **此处不自动派发补抬手势**：立刻再发新手势会取消部分机型上仍有效的 `willContinue` 注入，触摸总时长缩成约 600 ms（「点一下就没了」）；该回归已在真机上验证，故禁止在断裂路径自动补抬。

### `IDLE` 且上述标志为 true 时的下一次双键

- 日志 **`▣ 消耗双键：仅补抬手`**。
- **`forceLiftRightFinger(..., clearConsumeAfterSuccess = true)`**：同坐标短按，取消残留注入。
- 补抬 **`onCompleted`**：清除标志，并进入 **`gestureReady`** 冷却。

### 标志清除后的下一次双键

- 恢复 **`▶ 开始 [IDLE→HOLDING]`**，从阶段 1 再起。

在「续接必断」机型上，用户可见节奏为：**开始 →（可选）补抬双键 → 再开始**。

## 7. `forceLiftRightFinger`

- 在 **`(rightX, cy)`** 发 **`LIFT_MS`** 短按，与按住同点。
- 用途：续接断裂后用户主动对齐；正常松手路径在 `RELEASING` 时主要由 **`continueRightHold`** 的短段完成（思路一致，代码路径可能不同）。

## 8. 常量表（以代码为准）

| 常量 | 数值 | 用途 |
|----------|-------|---------|
| `PHASE1_MS` | 300 | 仅右指引入段。 |
| `PHASE2_MS` | 300 | 双指同长；右指 `willContinue`。 |
| `HOLD_SEGMENT_MS` | 500 | 按住时续接段时长。 |
| `LIFT_MS` | 50 | 释放 / 补抬短按时长。 |
| `COMBO_WINDOW_MS` | 1200 | 两音量键允许的最大间隔。 |
| `POST_RELEASE_COOLDOWN_MS` | 250 | 释放或补抬后短暂忽略双键。 |

## 9. 日志（Tag：`TwoTap`）

排障时可搜：

- `▶ 开始 [IDLE→HOLDING]` — 从空闲正常开新会话（未消费「仅补抬」标志）。
- `■ 停止 [HOLDING→RELEASING]` — 按住中用户第二次双键。
- `续接链断裂` — 续接段取消或派发失败；已置 **`consumeNextIdleComboAsLiftOnly`**。
- `▣ 消耗双键：仅补抬手` — 空闲态双键被用作对齐补抬。
- `补抬手完成` — 补抬手势完成。

## 10. 文件地图

```
app/src/main/java/com/example/twotap/MainActivity.kt   # 权限引导
app/src/main/java/com/example/twotap/TwoTapService.kt   # 核心逻辑
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/values/strings.xml                      # 服务显示名
app/build.gradle                                       # 变体输出 twotap.apk / twotap-debug.apk
```

## 11. 已知限制与非目标

- **`continueStroke`** 是否可靠**因机而异**。设计保留阶段 2 的 `willContinue` + 续接链；断裂路径依赖**下一次空闲双键仅补抬**，且断裂时**不**自动补抬。
- 单段手势最大时长（约 60 s）与注入策略由系统决定；本应用不依赖 root / Shizuku。
- 本文不讨论修改 `applicationId`；Release 签名由仓库维护者自行管理。

## 12. 修改时注意（给 AI / 协作者）

1. **勿**在 **`onBrokenContinueChain()`** 内恢复**自动** `forceLiftRightFinger`，除非先重读 §6 与真机结论。
2. 在 `willContinue` 仍可能有效时，任何新的 `dispatchGesture` 都可能**取消**上一段注入。
3. **`currentRightStroke`** 必须指向**创建它的同一条** `GestureDescription` 中的 stroke，以满足 `continueStroke` 约定。
4. `onKeyEvent` 与主线程 `Handler` 回调会并发读写状态：保持 **`@Volatile`** 与现有时序假设。
5. 若更换组合键，同步更新 **`README.md`**、**`accessibility_service_config`** 与用户可见文案。

## 13. 构建产物命名

`app/build.gradle` 中 `applicationVariants.configureEach` 设置 **`output.outputFileName`**：

- **release** → `twotap.apk`
- **debug** → `twotap-debug.apk`
