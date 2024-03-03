package com.example.woffandroid

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nona.woff.WOFFAndroid

class ViewDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        val container = findViewById<ViewGroup>(R.id.container)
        val inflater = LayoutInflater.from(this)

        for (woff in WOFF_LIST) {
            val textView = inflater.inflate(R.layout.textview, null) as TextView

            lifecycleScope.launch(Dispatchers.IO) {
                val typeface = decodeToTTFForView(this@ViewDemoActivity, woff)
                withContext(Dispatchers.Main) {
                    textView.typeface = typeface
                }
            }
            container.addView(textView)
        }

    }
}

suspend fun decodeToTTFForView(context: Context, url: String): Typeface {
    val client = OkHttpClient.Builder().build()
    val request = Request.Builder()
        .url(url)
        .build()
    return withContext(Dispatchers.IO) {
        client.newCall(request).execute().use {
            if (it.isSuccessful) {
                WOFFAndroid.decodeToTTF(context, it.body()!!.byteStream()) ?: Typeface.DEFAULT
            } else {
                Typeface.DEFAULT
            }
        }
    }
}