package com.open360

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.theme.Open360Theme

class MainActivity : ComponentActivity() {
    private val cameraViewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Open360Theme {
                CameraScreen(viewModel = cameraViewModel)
            }
        }
    }
}
