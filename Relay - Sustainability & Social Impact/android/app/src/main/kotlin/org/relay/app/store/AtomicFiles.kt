package org.relay.app.store

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe whole-file replacement: write a sibling temp file, fsync it, then rename over the target. */
object AtomicFiles {
    fun tmpFor(target: File): File = File(target.parentFile, target.name + ".tmp")

    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = tmpFor(target)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            try { out.fd.sync() } catch (_: Exception) { /* best effort */ }
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun readOrNull(file: File): ByteArray? = try {
        if (file.isFile) file.readBytes() else null
    } catch (_: Exception) {
        null
    }
}
