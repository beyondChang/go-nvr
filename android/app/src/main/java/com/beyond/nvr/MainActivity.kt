package com.beyond.nvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.beyond.nvr.navigation.NavGraph
import com.beyond.nvr.ui.theme.GoNvrTheme
import com.beyond.nvr.data.repository.PreferencesRepository
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val prefsRepo: PreferencesRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val theme by prefsRepo.theme.collectAsState(initial = "system")

            GoNvrTheme(theme = theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NavGraph()
                }
            }
        }
    }
}
