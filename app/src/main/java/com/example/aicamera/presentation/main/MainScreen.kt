package com.example.aicamera.presentation.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MainScreen() {

    CameraPreview(
        modifier = Modifier
            .fillMaxSize()
    )
}