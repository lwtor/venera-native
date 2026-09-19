package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.feature.home.HomeRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                HomeRoute()
            }
        }
    }
}
