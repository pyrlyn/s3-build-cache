package dev.pyrlyn.s3buildcache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class EvictionTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private fun o(k: String, size: Long, ageSec: Long) = CacheObject(k, size, t0.minusSeconds(ageSec))

    @Test fun `under limit evicts nothing`() =
        assertEquals(emptyList<String>(), selectForEviction(listOf(o("a", 10, 5), o("b", 10, 1)), 20))

    @Test fun `evicts oldest first until under limit`() =
        assertEquals(listOf("old", "mid"), selectForEviction(listOf(o("new", 10, 1), o("old", 10, 30), o("mid", 10, 20)), 10))

    @Test fun `single huge entry above limit evicted`() =
        assertEquals(listOf("a", "b"), selectForEviction(listOf(o("a", 100, 2), o("b", 5, 1)), 1))

    @Test fun `ties broken by key`() =
        assertEquals(listOf("a"), selectForEviction(listOf(o("b", 10, 5), o("a", 10, 5)), 10))

    @Test fun `empty input`() = assertEquals(emptyList<String>(), selectForEviction(emptyList(), 0))
}
