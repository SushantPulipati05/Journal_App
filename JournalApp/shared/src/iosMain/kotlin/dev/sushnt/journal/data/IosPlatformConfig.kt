@file:OptIn(ExperimentalForeignApi::class)

package dev.sushnt.journal.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** The app's private Application Support folder (not visible in the Files app). */
fun iosPlatformConfig(): PlatformConfig {
    val fm = NSFileManager.defaultManager
    val url = fm.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    ) as NSURL
    return PlatformConfig(requireNotNull(url.path))
}
