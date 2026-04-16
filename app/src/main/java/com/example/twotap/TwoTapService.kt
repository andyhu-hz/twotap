package com.example.twotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * 恢复为「上一版可用」逻辑（你反馈 continue 链虽不完美但可用）：
 *
 * - 阶段1：仅右指 300ms
 * - 阶段2：双指同长 300ms，**右指 willContinue=true**
 * - 阶段2 onCompleted 后 **handler.post** 启动 [continueRightHold]（避免在回调栈内直接 dispatch）
 * - 第二次双键：仅 [State.RELEASING]，由下一段 [continueRightHold] 派发抬起
 * - 组合键：1.2s 窗口 + 过期时间戳清理；释放后短冷却
 *
 * **续接被系统取消**：逻辑已回 [State.IDLE]，但 phase2 右指 `willContinue` 在部分 ROM 上仍会残留注入。
 * 置 [consumeNextIdleComboAsLiftOnly]：之后**第一次**空闲双键只做补抬手（对齐「第二次双键=松手」）。
 * 续接刚断时**不**自动派发补抬：自动 50ms 手势会立刻取消 phase2 后 `willContinue` 在部分 ROM 上仍有效的长按，
 * 只剩极短触碰。仅用户主动再按一次双键时才 [forceLiftRightFinger]。
 */
class TwoTapService : AccessibilityService() {

    companion object {
        private const val TAG = "TwoTap"

        private const val PHASE1_MS = 300L
        private const val PHASE2_MS = 300L
        private const val HOLD_SEGMENT_MS = 500L
        private const val LIFT_MS = 50L

        private const val COMBO_WINDOW_MS = 1_200L
        private const val POST_RELEASE_COOLDOWN_MS = 250L
    }

    private enum class State { IDLE, HOLDING, RELEASING }

    @Volatile private var state = State.IDLE
    @Volatile private var phase3Active = false
    @Volatile private var gestureReady = true

    /** 续接链刚断：下一次在 [State.IDLE] 收到的双键应视为「对齐/松手」，不发阶段1。 */
    @Volatile private var consumeNextIdleComboAsLiftOnly = false

    private val handler = Handler(Looper.getMainLooper())

    private var rightHoldPath: Path? = null
    @Volatile private var currentRightStroke: GestureDescription.StrokeDescription? = null

    private var leftX = 0f
    private var rightX = 0f
    private var cy = 0f

    private fun ensureCoords() {
        if (rightHoldPath != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
        leftX = w * 0.30f
        rightX = w * 0.70f
        cy = h * 0.50f
        rightHoldPath = Path().apply { moveTo(rightX, cy) }
        Log.d(TAG, "屏幕：${w}×${h}  左=(${leftX},${cy}) 右=(${rightX},${cy})")
    }

    private var volumeUpTime = 0L
    private var volumeDownTime = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val now = System.currentTimeMillis()
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (volumeDownTime != 0L && now - volumeDownTime > COMBO_WINDOW_MS) {
                    volumeDownTime = 0L
                }
                volumeUpTime = now
                Log.d(TAG, "按键：音量加")
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeUpTime != 0L && now - volumeUpTime > COMBO_WINDOW_MS) {
                    volumeUpTime = 0L
                }
                volumeDownTime = now
                Log.d(TAG, "按键：音量减")
            }
            else -> return false
        }

        if (volumeUpTime == 0L || volumeDownTime == 0L) return false
        val gap = kotlin.math.abs(volumeUpTime - volumeDownTime)
        if (gap > COMBO_WINDOW_MS) return false

        volumeUpTime = 0L
        volumeDownTime = 0L

        if (!gestureReady) {
            Log.d(TAG, "冷却中，忽略")
            return true
        }

        when (state) {
            State.IDLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (consumeNextIdleComboAsLiftOnly) {
                        Log.d(TAG, "▣ 消耗双键：仅补抬手（上次续接失败后的对齐，不当新开始）")
                        handler.post {
                            forceLiftRightFinger(
                                reason = "user-idle-sync-after-broken",
                                clearConsumeAfterSuccess = true
                            )
                        }
                    } else {
                        state = State.HOLDING
                        Log.d(TAG, "▶ 开始 [IDLE→HOLDING]")
                        handler.post { startPhase1() }
                    }
                }
            }
            State.HOLDING -> {
                state = State.RELEASING
                Log.d(TAG, "■ 停止 [HOLDING→RELEASING]，phase3Active=$phase3Active")
            }
            State.RELEASING -> Log.d(TAG, "RELEASING 中忽略")
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startPhase1() {
        ensureCoords()
        Log.d(TAG, "阶段1：仅右手指 ${PHASE1_MS}ms")

        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(rightX, cy) }, 0L, PHASE1_MS
        )
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    if (state == State.HOLDING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startPhase2DualFingerStartChain()
                    } else {
                        resetState()
                    }
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "阶段1 取消")
                    resetState()
                }
            },
            handler
        )
        if (!ok) { Log.e(TAG, "阶段1 dispatch 失败"); resetState() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startPhase2DualFingerStartChain() {
        val pathLeft = Path().apply { moveTo(leftX, cy) }
        val pathRight = rightHoldPath ?: Path().apply { moveTo(rightX, cy) }.also { rightHoldPath = it }

        val leftStroke = GestureDescription.StrokeDescription(pathLeft, 0L, PHASE2_MS)
        val rightStroke = GestureDescription.StrokeDescription(pathRight, 0L, PHASE2_MS, true)

        Log.d(TAG, "阶段2：双指同长 ${PHASE2_MS}ms，右指 willContinue，完成后 post 续接链")

        val ok = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(leftStroke)
                .addStroke(rightStroke)
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    Log.d(TAG, "阶段2 onCompleted，state=$state")
                    when (state) {
                        State.HOLDING -> {
                            phase3Active = true
                            currentRightStroke = rightStroke
                            handler.post {
                                if (state == State.HOLDING) {
                                    Log.d(TAG, "post：开始 continueRightHold")
                                    continueRightHold()
                                } else {
                                    resetState()
                                }
                            }
                        }
                        State.RELEASING -> resetState()
                        else -> resetState()
                    }
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "阶段2 取消")
                    resetState()
                }
            },
            handler
        )
        if (!ok) { Log.e(TAG, "阶段2 dispatch 失败"); resetState() }
    }

    /**
     * 短点同坐标，取消可能残留的 willContinue 注入。
     * @param clearConsumeAfterSuccess 为 true 且 onCompleted 时清除 [consumeNextIdleComboAsLiftOnly]
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun forceLiftRightFinger(
        reason: String,
        clearConsumeAfterSuccess: Boolean = false
    ) {
        ensureCoords()
        Log.d(TAG, "补抬手 ($reason) clearConsume=$clearConsumeAfterSuccess")
        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(rightX, cy) }, 0L, LIFT_MS
        )
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    if (clearConsumeAfterSuccess) consumeNextIdleComboAsLiftOnly = false
                    Log.d(TAG, "补抬手完成")
                    gestureReady = false
                    handler.postDelayed({ gestureReady = true }, POST_RELEASE_COOLDOWN_MS)
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "补抬手被取消（${if (clearConsumeAfterSuccess) "仍须再按一次双键对齐" else "可再试"}）")
                }
            },
            handler
        )
        if (!ok) Log.w(TAG, "补抬手 dispatch 失败")
    }

    /**
     * 续接段未跑起来：清逻辑状态，并标记「下次空闲双键 = 仅补抬」。
     * 不在此处自动 [forceLiftRightFinger]：新手势会取消 OEM 上仍有效的 willContinue 长按，导致只剩 ~600ms 短触。
     */
    private fun onBrokenContinueChain() {
        Log.w(TAG, "续接链断裂 → 逻辑 IDLE，下次空闲双键仅补抬（不自动补抬，避免截断残留长按）")
        currentRightStroke = null
        phase3Active = false
        state = State.IDLE
        gestureReady = true
        consumeNextIdleComboAsLiftOnly = true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun continueRightHold() {
        val prev = currentRightStroke
        val path = rightHoldPath
        if (prev == null || path == null) {
            Log.e(TAG, "continueRightHold：无 stroke")
            resetState()
            return
        }

        val isReleasing = (state == State.RELEASING)
        val duration = if (isReleasing) 50L else HOLD_SEGMENT_MS
        val willContinue = !isReleasing

        Log.d(TAG, "continueRightHold：isReleasing=$isReleasing duration=${duration}ms")

        val next = try {
            prev.continueStroke(path, 0L, duration, willContinue)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "continueStroke 失败", e)
            onBrokenContinueChain()
            return
        }
        currentRightStroke = next

        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    if (isReleasing) {
                        Log.d(TAG, "抬起完成，冷却 ${POST_RELEASE_COOLDOWN_MS}ms")
                        consumeNextIdleComboAsLiftOnly = false
                        gestureReady = false
                        phase3Active = false
                        currentRightStroke = null
                        state = State.IDLE
                        handler.postDelayed({ gestureReady = true }, POST_RELEASE_COOLDOWN_MS)
                    } else {
                        consumeNextIdleComboAsLiftOnly = false
                        handler.post {
                            when (state) {
                                State.HOLDING, State.RELEASING -> continueRightHold()
                                else -> resetState()
                            }
                        }
                    }
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "continue 段取消 isReleasing=$isReleasing")
                    if (!isReleasing) onBrokenContinueChain()
                }
            },
            handler
        )
        if (!ok) {
            Log.e(TAG, "continue dispatch 失败")
            onBrokenContinueChain()
        }
    }

    private fun resetState() {
        Log.d(TAG, "resetState → IDLE")
        currentRightStroke = null
        phase3Active = false
        state = State.IDLE
        gestureReady = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also {
            it.flags = it.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.d(TAG, "✅ 服务已连接（已恢复 continue 链版本）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
