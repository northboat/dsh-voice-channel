package com.dsh.channel.whale

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.channel.whale.ui.DshWhaleTheme
import com.dsh.channel.whale.ui.WhaleScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: WhaleViewModel = viewModel()
            val state by vm.ui.collectAsState()
            var pendingCall by remember { mutableStateOf(false) }
            val requestPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                // 拒绝授权时保持待机（通话需要麦克风常开）。
                if (granted && pendingCall) vm.onCallToggle()
                pendingCall = false
            }

            DshWhaleTheme {
                WhaleScreen(
                    state = state,
                    onCall = {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) vm.onCallToggle()
                        else {
                            pendingCall = true
                            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onSettings = { vm.openSettings(true) },
                    onDismissSettings = { vm.openSettings(false) },
                    onClearMessage = vm::clearMessage,
                    onEndpoint = vm::editEndpoint,
                    onPin = vm::editPin,
                    onScan = vm::scan,
                    onConnect = vm::connect,
                    onChooseFound = { vm.editEndpoint(it.baseUrl) },
                )
            }
        }
    }
}
