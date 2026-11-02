package dev.pyrlyn.s3buildcache

import java.time.Instant

data class CacheObject(val key: String, val size: Long, val lastModified: Instant)

/**
 * Pure selection: returns keys to delete (oldest LastModified first) so the remaining
 * total size is <= [maxSizeBytes]. Ties are broken by key for determinism.
 */
fun selectForEviction(objects: List<CacheObject>, maxSizeBytes: Long): List<String> {
    var total = objects.sumOf { it.size }
    if (total <= maxSizeBytes) return emptyList()
    val victims = mutableListOf<String>()
    for (obj in objects.sortedWith(compareBy<CacheObject> { it.lastModified }.thenBy { it.key })) {
        if (total <= maxSizeBytes) break
        victims += obj.key
        total -= obj.size
    }
    return victims
}
