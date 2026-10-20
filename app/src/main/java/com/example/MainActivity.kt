package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.call.CallViewModel
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.domain.model.CallState
import com.example.service.LocalChatService
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var networkManager: NetworkManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkManager = NetworkManager.getInstance(this)

        startLocalChatService()
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current

                val chatViewModel: ChatViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ChatViewModel(networkManager) as T
                        }
                    }
                )

                val callViewModel: CallViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return CallViewModel(networkManager) as T
                        }
                    }
                )

                // VULN-29 & VULN-30 FIX: Only keep screen on and show over lock screen during active/ringing calls
                val callUiState by callViewModel.uiState.collectAsState()
                DisposableEffect(callUiState.callState) {
                    val inCall = callUiState.callState != CallState.IDLE
                    if (inCall) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true)
                            setTurnScreenOn(true)
                        }
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(false)
                            setTurnScreenOn(false)
                        }
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Battery optimization & runtime permissions
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // Audio recording permission for active calls
                var pendingCallAction: (() -> Unit)? = null
                val recordAudioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        startLocalChatService()
                        pendingCallAction?.invoke()
                    }
                    pendingCallAction = null
                }

                val attemptCall = {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startLocalChatService()
                        callViewModel.initiateCall()
                    } else {
                        pendingCallAction = { callViewModel.initiateCall() }
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                val attemptAccept = {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startLocalChatService()
                        callViewModel.acceptCall()
                    } else {
                        pendingCallAction = { callViewModel.acceptCall() }
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                LaunchedEffect(Unit) {
                    val permissions = mutableListOf(
                        Manifest.permission.INTERNET,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                        Manifest.permission.RECORD_AUDIO
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())

                    chatViewModel.startHosting()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFFEF7FF)
                ) {
                    ChatScreen(
                        chatViewModel = chatViewModel,
                        callViewModel = callViewModel,
                        onStartCallRequested = attemptCall,
                        onAcceptCallRequested = attemptAccept
                    )
                }
            }
        }
    }

    private fun startLocalChatService() {
        try {
            val serviceIntent = Intent(this, LocalChatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
