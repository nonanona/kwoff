package org.nona.woff

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream

object WOFFAndroid {
    suspend fun decodeToTTF(context: Context, inputStream: InputStream): Typeface? {
        return withContext(Dispatchers.IO) {
            WOFF.decodeToTTF(inputStream)?.let { ttfBuffer->
                TemporaryFile(context).use { tmpFile ->
                    FileOutputStream(tmpFile.file).use { fos -> fos.write(ttfBuffer) }
                    Typeface.createFromFile(tmpFile.file)
                }
            }
        }
    }
}