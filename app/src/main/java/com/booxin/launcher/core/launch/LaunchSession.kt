package com.booxin.launcher.core.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** :game 进程内启动阶段。Running 之后只能杀进程，不能干净停 HotSpot。 */
enum class LaunchPhase {
    Idle,
    Starting,
    Binding,
    Running,
    Stopping,
    Failed,
}

object LaunchSession {

    private val lock = Any()

    private val _phase = MutableStateFlow(LaunchPhase.Idle)
    val phase: StateFlow<LaunchPhase> = _phase.asStateFlow()

    @Volatile
    var failReason: String? = null
        private set

    @Volatile
    var hotspotEntered: Boolean = false
        private set

    fun current(): LaunchPhase = _phase.value

    fun tryBegin(): Boolean = synchronized(lock) {
        when (_phase.value) {
            LaunchPhase.Idle, LaunchPhase.Failed -> {
                failReason = null
                hotspotEntered = false
                _phase.value = LaunchPhase.Starting
                true
            }
            else -> false
        }
    }

    fun enterBinding() = transition(LaunchPhase.Binding)

    fun enterRunning() = synchronized(lock) {
        if (_phase.value == LaunchPhase.Stopping) return@synchronized false
        hotspotEntered = true
        _phase.value = LaunchPhase.Running
        true
    }

    fun requestStop(): StopKind = synchronized(lock) {
        when (_phase.value) {
            LaunchPhase.Idle, LaunchPhase.Failed -> StopKind.None
            LaunchPhase.Stopping -> {
                if (hotspotEntered) StopKind.HardKill else StopKind.None
            }
            LaunchPhase.Running -> {
                _phase.value = LaunchPhase.Stopping
                StopKind.HardKill
            }
            LaunchPhase.Starting, LaunchPhase.Binding -> {
                _phase.value = LaunchPhase.Stopping
                if (hotspotEntered) StopKind.HardKill else StopKind.CancelJob
            }
        }
    }

    fun fail(reason: String) = synchronized(lock) {
        failReason = reason
        hotspotEntered = false
        _phase.value = LaunchPhase.Failed
    }

    private fun transition(to: LaunchPhase) = synchronized(lock) {
        if (_phase.value == LaunchPhase.Stopping) return@synchronized
        _phase.value = to
    }

    enum class StopKind {
        None,
        CancelJob,
        HardKill,
    }
}
