package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.veneranative.core.designsystem.VeneraNativeTheme

class MainActivity : ComponentActivity() {

    private val graph by lazy {
        androidx.lifecycle.ViewModelProvider(this)[AppGraph::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                AppRoot(this@MainActivity, graph)
            }
        }
    }

    override fun onStop() {
        graph.flushProgress()
        super.onStop()
    }
}
