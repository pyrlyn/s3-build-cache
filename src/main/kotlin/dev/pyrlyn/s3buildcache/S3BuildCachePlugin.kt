package dev.pyrlyn.s3buildcache

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/** Settings plugin: registers the S3 build cache type for `buildCache { remote<S3BuildCache> { } }`. */
class S3BuildCachePlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.buildCache.registerBuildCacheService(
            S3BuildCache::class.java,
            S3BuildCacheServiceFactory::class.java,
        )
    }
}
