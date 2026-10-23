package com.ai.assistance.operit.api.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Apply Android audio focus once, independent of the selected synthesis provider. */
internal class FocusedVoiceService(private val context: Context, private val delegate: VoiceService) :
    VoiceService by delegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val active = mutableSetOf<Job>()
    private var lease: AutoCloseable? = null
    private var leaseToken: Any? = null
    @Volatile private var closed = false
    override suspend fun speak(text: String, interrupt: Boolean, rate: Float?, pitch: Float?,
                               extraParams: Map<String,String>): Boolean {
        if (closed) return false
        if (interrupt) stop()
        val session = epoch.get()
        return coroutineScope {
            val task = async(start = CoroutineStart.LAZY) {
                delegate.speak(text,false,rate,pitch,extraParams)
            }
            lifecycle.withLock {
                if (closed || session != epoch.get()) { task.cancel(); return@coroutineScope false }
                if (lease == null) {
                    val token = Any()
                    leaseToken = token
                    lease = VoiceAudioSession.acquire(context) {
                        scope.launch {
                            lifecycle.withLock {
                                if (leaseToken === token) stopLocked()
                            }
                        }
                    }
                }
                active.add(task)
            }
            try { task.await() }
            finally {
                withContext(NonCancellable) {
                    lifecycle.withLock {
                        active.remove(task)
                        if (active.isEmpty()) {
                            if (task.isCancelled && session == epoch.get()) delegate.stop()
                            lease?.close()
                            lease = null
                            leaseToken = null
                        }
                    }
                }
            }
        }
    }
    override suspend fun stop(): Boolean {
        return lifecycle.withLock { stopLocked() }
    }
    private suspend fun stopLocked(): Boolean {
            epoch.incrementAndGet()
            active.forEach { it.cancel() }
            active.clear()
            return try { delegate.stop() }
            finally { lease?.close(); lease = null; leaseToken = null }
    }
    override fun shutdown() {
        closed = true
        epoch.incrementAndGet()
        scope.launch {
            stop()
            delegate.shutdown()
            scope.cancel()
        }
    }
}
