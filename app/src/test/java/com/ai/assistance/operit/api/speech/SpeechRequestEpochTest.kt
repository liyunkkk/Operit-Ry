package com.ai.assistance.operit.api.speech

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechRequestEpochTest {
    @Test fun cancelledTranscriptionCannotPublishIntoNextRecording() {
        val sessions = SpeechRequestEpoch()
        val old = sessions.begin()
        val oldCall = OkHttpClient().newCall(Request.Builder().url("https://example.invalid").build())
        sessions.attach(old, oldCall)
        val next = sessions.begin()
        assertTrue(oldCall.isCanceled())
        var text = ""
        assertFalse(sessions.publish(old) { text = "late old result" })
        assertTrue(sessions.publish(next) { text = "new result" })
        assertEquals("new result", text)
    }

    @Test fun cancellationBeforeRequestRegistrationCancelsTheLateRequest() {
        val sessions = SpeechRequestEpoch()
        val old = sessions.begin()
        sessions.begin()
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.invalid").build())
        try {
            sessions.attach(old, call)
            fail("A late request must never reach the server")
        } catch (_: CancellationException) {
            assertTrue(call.isCanceled())
        }
    }

    @Test fun delayedWorkerCannotPublishAfterCancellationReturns() {
        val sessions = SpeechRequestEpoch()
        val old = sessions.begin()
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var published = true
        val worker = Thread {
            ready.countDown()
            release.await(2, TimeUnit.SECONDS)
            published = sessions.publish(old) { error("Stale result accepted") }
            finished.countDown()
        }
        worker.start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        sessions.begin()
        release.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        worker.join(2000)
        assertFalse(published)
    }
}
