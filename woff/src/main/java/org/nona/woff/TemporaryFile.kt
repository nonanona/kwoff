package org.nona.woff

import android.content.Context
import java.io.File

internal class TemporaryFile(context: Context) : AutoCloseable {
    val file = File.createTempFile("temp", "dat", context.cacheDir)
    var deleteOnClose: Boolean = true
    override fun close() {
        if (deleteOnClose) {
            file.delete()
        }
    }
}