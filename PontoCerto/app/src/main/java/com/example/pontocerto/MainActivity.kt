package com.example.pontocerto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pontocerto.ui.MainViewModel
import com.example.pontocerto.ui.screens.HomeScreen
import com.example.pontocerto.ui.theme.PontoCertoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PontoCertoTheme {
                val viewModel: MainViewModel = viewModel()
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}