package com.example.woffandroid

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.example.woffandroid.ui.theme.WoffAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nona.woff.WOFFCompose

class ComposeDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WoffAndroidTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column {
                        for (woffUrl in WOFF_LIST) {
                            TextWithWOFF(text = "Hello, World.", url = woffUrl)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TextWithWOFF(text: String, url: String) {
    var ttf by remember { mutableStateOf<FontFamily>(FontFamily.Default) }

    val context = LocalContext.current
    LaunchedEffect(url) {
        launch {
            ttf = decodeToTTFForCompose(context, url)
        }
    }

    Text(
        text = text,
        style = LocalTextStyle.current.copy(
            fontSize = 32.sp,
            fontFamily = ttf
        )
    )
}

suspend fun decodeToTTFForCompose(context: Context, url: String): FontFamily {
    val client = OkHttpClient.Builder().build()
    val request = Request.Builder()
        .url(url)
        .build()
    return withContext(Dispatchers.IO) {
        client.newCall(request).execute().use {
            if (it.isSuccessful) {
                WOFFCompose.decodeToTTF(context, it.body()!!.byteStream())?.let {
                    FontFamily(it)
                } ?: FontFamily.Default
            } else {
                FontFamily.Default
            }
        }
    }
}