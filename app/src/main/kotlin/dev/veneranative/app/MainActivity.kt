package dev.veneranative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.feature.home.HomeRoute
import dev.veneranative.feature.reader.FakePageProvider
import dev.veneranative.feature.reader.ReaderRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeneraNativeTheme {
                // Stage 0 assembly: the reader is wired to an in-memory provider so the prototype
                // can be opened without a real source. Root navigation arrives with the first
                // feature slice that needs more than one destination.
                val provider = remember { FakePageProvider() }
                var readerOpen by rememberSaveable { mutableStateOf(false) }
                if (readerOpen) {
                    ReaderRoute(
                        chapter = ChapterKey("demo-1"),
                        provider = provider,
                        onBack = { readerOpen = false },
                    )
                } else {
                    HomeRoute(onOpenReader = { readerOpen = true })
                }
            }
        }
    }
}
