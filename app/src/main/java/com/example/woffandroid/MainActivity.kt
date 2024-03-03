package com.example.woffandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.example.woffandroid.ui.theme.WoffAndroidTheme

val WOFF_LIST = listOf(
    "https://fonts.gstatic.com/s/pacifico/v22/FwZY7-Qmy14u9lezJ-6H6Mk.woff2",
    "https://fonts.gstatic.com/s/madimione/v1/2V0YKIEADpA8U6RygDnZVFMiBw.woff2",
    "https://fonts.gstatic.com/s/ojuju/v1/7r3IqXF7v9Apbpkur4k.woff2",
    "https://fonts.gstatic.com/s/jacquardabastarda9/v1/f0Xp0fWr_8t6WFtKQJfOhaC0hcZ1HYAMAYwE3zE.woff2",
)

class MainActivity : ComponentActivity() {
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
                        Button(onClick = {
                                startActivity(
                                    Intent(this@MainActivity, ComposeDemoActivity::class.java))
                        }) { Text("Compose Demo") }
                        Button(onClick = {
                            startActivity(Intent(this@MainActivity, ViewDemoActivity::class.java))
                        }) { Text("Views Demo") }
                    }
                }
            }
        }
    }
}
