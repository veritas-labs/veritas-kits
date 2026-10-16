package uk.veritaslabs.kits.store

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

@OptIn(ExperimentalForeignApi::class)
internal actual object DatabaseFiles {
    actual fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun siblings(path: String): List<String> {
        val directory = path.substringBeforeLast('/')
        return NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null).orEmpty()
            .map { "$directory/$it" }
    }

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
