package com.notflix.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.notflix.tv.ui.NotflixRoot
import com.notflix.tv.ui.theme.NotflixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotflixTheme {
                NotflixRoot()
            }
        }
    }
}
