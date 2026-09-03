package app.morphe.manager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LicensedRuntimeGateTest {
    @Test
    fun `starts licensed runtime only once`() {
        val gate = LicensedRuntimeGate()
        var starts = 0

        assertTrue(gate.start { starts++ })
        assertFalse(gate.start { starts++ })
        assertEquals(1, starts)
    }

    @Test
    fun `allows retry when initialization throws`() {
        val gate = LicensedRuntimeGate()

        assertFailsWith<IllegalStateException> {
            gate.start { error("startup failed") }
        }
        assertTrue(gate.start { })
    }

    @Test
    fun `concurrent activation callbacks still initialize once`() {
        val gate = LicensedRuntimeGate()
        val starts = AtomicInteger()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.submit {
                ready.countDown()
                go.await()
                gate.start { starts.incrementAndGet() }
            }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, starts.get())
    }
}
