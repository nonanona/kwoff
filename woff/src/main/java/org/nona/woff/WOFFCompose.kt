package org.nona.woff

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.text.font.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream

object WOFFCompose {
    suspend fun decodeToTTF(context: Context, inputStream: InputStream): Font? {
        return withContext(Dispatchers.IO) {
            WOFF.decodeToTTF(inputStream)?.let { ttfBuffer->
                TemporaryFile(context).use { tmpFile ->
                    FileOutputStream(tmpFile.file).use { fos -> fos.write(ttfBuffer) }
                    if (Build.VERSION.SDK_INT >= 26) {
                        Font(ParcelFileDescriptor.open(
                            tmpFile.file, ParcelFileDescriptor.MODE_READ_ONLY))
                    } else {
                        tmpFile.deleteOnClose = false
                        Font(tmpFile.file)
                    }
                }
            }
        }
    }
}