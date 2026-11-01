package com.easysavevideo.app.discovery

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.storage.SearxngConfigurationRead
import com.easysavevideo.app.storage.SearxngConfigurationStore
import com.easysavevideo.app.storage.SearxngConfigurationWrite
import com.easysavevideo.app.storage.readableConfiguration
import com.easysavevideo.searxng.SearxngResultFormat
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class SearxngConfigurationAction { ADD, EDIT, DELETE, RESET }

/** UI claims cannot move between controller instances, actions, slots, reloads, or committed generations. */
class SearxngConfigurationClaim internal constructor(internal val owner: Any, internal val epoch: Long,
    val generation: String, val slotId: String?, val kind: SearxngConfigurationAction) {
    override fun toString() = "SearxngConfigurationClaim([redacted])"
}

enum class SearxngConfigurationStatus { NOT_LOADED, MISSING, READY, MALFORMED, UNAVAILABLE, CLOSED }

data class SearxngConfigurationState(
    val status: SearxngConfigurationStatus = SearxngConfigurationStatus.NOT_LOADED,
    val config: SearxngSourceConfiguration? = null,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val loaded: Boolean get() = status != SearxngConfigurationStatus.NOT_LOADED
    val unavailable: Boolean get() = status in setOf(SearxngConfigurationStatus.MALFORMED,
        SearxngConfigurationStatus.UNAVAILABLE, SearxngConfigurationStatus.CLOSED)
    val editable: Boolean get() = config != null && loaded && !unavailable && !busy
    override fun toString() = "SearxngConfigurationState([redacted])"
}

/**
 * Construct on main, then call reload(). Construction performs no filesystem access. Public
 * controls/publication use main; storage and onCommitted use the serialized I/O executor.
 * Callbacks only fence/invalidate discovery. None may submit or reissue a query.
 * Tests can inject queued executors and publication without Android's Looper.
 */
class SearxngConfigurationController(
    private val store: SearxngConfigurationStore,
    private val onChangeStarting: () -> Unit = {},
    private val onCommitted: (SearxngSourceConfiguration) -> Unit = {},
    private val onChangeFinished: () -> Unit = {},
    private val onLoaded: (SearxngConfigurationState) -> Unit = {},
    private val io: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "esv-source-config").apply { isDaemon = true }
    },
    private val publish: ((() -> Unit) -> Unit) = defaultPublisher(),
) : AutoCloseable {
    private val owner = Any()
    private var epoch = 0L
    // UI ownership changes retire dialogs without cancelling an already accepted operation.
    private var claimEpoch = 0L
    private var closed = false
    var state by mutableStateOf(SearxngConfigurationState())
        private set

    @Synchronized fun beginAdd(): SearxngConfigurationClaim? =
        if (state.config?.let { it.slots.size < SearxngConfigurationPolicy.MAX_INSTANCES &&
            it.nextInstanceOrdinal < Long.MAX_VALUE } == true) claim(null, SearxngConfigurationAction.ADD) else null
    @Synchronized fun beginEdit(slotId: String): SearxngConfigurationClaim? =
        if (state.config?.slots?.any { it.id == slotId } == true) claim(slotId, SearxngConfigurationAction.EDIT) else null
    @Synchronized fun beginDelete(slotId: String): SearxngConfigurationClaim? =
        if (state.config?.slots?.any { it.id == slotId } == true) claim(slotId, SearxngConfigurationAction.DELETE) else null
    @Synchronized fun beginReset(): SearxngConfigurationClaim? = claim(null, SearxngConfigurationAction.RESET)
    private fun claim(slotId: String?, kind: SearxngConfigurationAction): SearxngConfigurationClaim? =
        if (closed || !state.editable) null else SearxngConfigurationClaim(owner, claimEpoch, state.config!!.generation, slotId, kind)
    @Synchronized fun isCurrent(claim: SearxngConfigurationClaim): Boolean = !closed && state.editable &&
        claim.owner === owner && claim.epoch == claimEpoch && claim.generation == state.config?.generation &&
        (claim.slotId == null || state.config?.slots?.any { it.id == claim.slotId } == true)

    /** Retire editor claims only. A queued write/read and its terminal publication still own epoch. */
    @Synchronized fun invalidateClaims() { claimEpoch++ }

    @Synchronized fun reload(): Boolean {
        if (closed || state.busy) return false
        val operation = ++epoch
        invalidateClaims()
        state = state.copy(busy = true, notice = null)
        return enqueue(operation, {
            val loaded = try { store.load() } catch (_: Exception) { SearxngConfigurationRead.Unavailable }
            publish {
                synchronized(this) {
                    if (closed || operation != epoch) return@synchronized
                    state = readState(loaded)
                    onLoaded(state)
                }
            }
        }, started = false)
    }

    @Synchronized fun add(claim: SearxngConfigurationClaim, searchUrl: String, enabled: Boolean = false,
        acknowledged: Boolean, format: SearxngResultFormat = SearxngResultFormat.JSON_VIDEOS): Boolean {
        if (!isCurrent(claim) || claim.kind != SearxngConfigurationAction.ADD || claim.slotId != null) return rejectStale()
        return apply(claim, SearxngConfigurationPolicy.add(state.config!!, searchUrl, enabled, acknowledged, format))
    }
    @Synchronized fun remove(claim: SearxngConfigurationClaim): Boolean {
        if (!isCurrent(claim) || claim.kind != SearxngConfigurationAction.DELETE || claim.slotId == null) return rejectStale()
        return apply(claim, SearxngConfigurationPolicy.remove(state.config!!, claim.slotId))
    }
    @Synchronized fun replace(claim: SearxngConfigurationClaim, searchUrl: String, enabled: Boolean,
        acknowledged: Boolean, format: SearxngResultFormat = state.config?.slots?.firstOrNull { it.id == claim.slotId }?.format
            ?: SearxngResultFormat.JSON_VIDEOS): Boolean {
        if (!isCurrent(claim) || claim.kind != SearxngConfigurationAction.EDIT || claim.slotId == null) return rejectStale()
        return apply(claim, SearxngConfigurationPolicy.replace(state.config!!, claim.slotId, searchUrl, enabled, acknowledged, format))
    }
    @Synchronized fun reset(claim: SearxngConfigurationClaim): Boolean {
        if (!isCurrent(claim) || claim.kind != SearxngConfigurationAction.RESET || claim.slotId != null) return rejectStale()
        return apply(claim, SearxngConfigurationPolicy.reset(state.config!!))
    }

    private fun rejectStale(): Boolean {
        if (!closed) state = state.copy(notice = "来源配置已变更或暂不可用，请关闭旧窗口后重新核对。")
        return false
    }
    private fun apply(claim: SearxngConfigurationClaim, edit: SearxngConfigurationEdit): Boolean {
        when (edit) {
            SearxngConfigurationEdit.Unchanged -> { state = state.copy(notice = "配置没有变化。"); return false }
            is SearxngConfigurationEdit.Rejected -> { state = state.copy(notice = issueText(edit.issue)); return false }
            is SearxngConfigurationEdit.Accepted -> {
                val operation = ++epoch
                invalidateClaims()
                state = state.copy(busy = true, notice = null)
                try { onChangeStarting() } catch (_: Exception) {
                    state = state.copy(busy = false, notice = "来源配置暂不能应用；已保存资料未改动。")
                    onChangeFinished(); return false
                }
                return enqueue(operation, {
                    val result = try { store.replace(claim.generation, edit.config) }
                    catch (_: Exception) { SearxngConfigurationWrite.Unavailable }
                    var synchronizedRuntime = true
                    if (result is SearxngConfigurationWrite.Committed) {
                        try { onCommitted(result.config) } catch (_: Exception) { synchronizedRuntime = false }
                    }
                    publish {
                        synchronized(this) {
                            if (closed || operation != epoch) return@synchronized
                            state = when {
                                !synchronizedRuntime -> SearxngConfigurationState(SearxngConfigurationStatus.UNAVAILABLE,
                                    notice = "配置已保存，但来源同步失败；请重新读取配置。")
                                result is SearxngConfigurationWrite.Committed -> SearxngConfigurationState(
                                    SearxngConfigurationStatus.READY, result.config, notice = "配置已保存。需要主动点击查找；不会自动发送查询。")
                                result is SearxngConfigurationWrite.Conflict -> SearxngConfigurationState(
                                    SearxngConfigurationStatus.UNAVAILABLE,
                                    notice = "来源配置已变更或不可核对；旧操作未覆盖它，请重新读取配置。")
                                else -> SearxngConfigurationState(SearxngConfigurationStatus.UNAVAILABLE,
                                    notice = "配置写入失败；原始资料已保留，来源暂不可用。")
                            }
                            onChangeFinished()
                        }
                    }
                }, started = true)
            }
        }
    }

    private fun enqueue(operation: Long, work: () -> Unit, started: Boolean): Boolean = try {
        io.execute(work); true
    } catch (_: Exception) {
        if (!closed && operation == epoch) state = SearxngConfigurationState(SearxngConfigurationStatus.UNAVAILABLE,
            notice = "本地配置暂不可用；原始资料已保留。")
        if (started) onChangeFinished() else onLoaded(state)
        false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; epoch++; invalidateClaims()
        state = SearxngConfigurationState(SearxngConfigurationStatus.CLOSED)
        (io as? ExecutorService)?.shutdown()
    }

    companion object {
        private fun defaultPublisher(): ((() -> Unit) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            return { action -> main.post(action); Unit }
        }
        private fun readState(value: SearxngConfigurationRead) = SearxngConfigurationState(
            when (value) {
                SearxngConfigurationRead.Missing -> SearxngConfigurationStatus.MISSING
                is SearxngConfigurationRead.Present -> SearxngConfigurationStatus.READY
                SearxngConfigurationRead.Malformed -> SearxngConfigurationStatus.MALFORMED
                SearxngConfigurationRead.Unavailable -> SearxngConfigurationStatus.UNAVAILABLE
            }, value.readableConfiguration())
        fun issueText(issue: SearxngConfigurationIssue): String = when (issue) {
            SearxngConfigurationIssue.INVALID_ENDPOINT -> "请输入公共域名的 HTTPS 搜索地址，以 /search 结尾，可含路径前缀；不可含账号、密码、查询参数、片段、端口、IP 或本地地址。"
            SearxngConfigurationIssue.DUPLICATE_ORIGIN -> "此服务已在另一个实例配置；同一服务不能重复配置为 JSON 与 HTML 来源。"
            SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED -> "请先勾选查询数据发送说明。"
            SearxngConfigurationIssue.REVISION_EXHAUSTED -> "配置版本已达上限；原始资料已保留。"
            SearxngConfigurationIssue.UNKNOWN_SLOT -> "来源实例不存在或已删除。"
            SearxngConfigurationIssue.INSTANCE_LIMIT -> "最多配置 ${SearxngConfigurationPolicy.MAX_INSTANCES} 个实例；请先删除不需要的实例。"
            SearxngConfigurationIssue.ID_EXHAUSTED -> "实例标识已达上限；原始资料已保留。"
        }
    }
}
