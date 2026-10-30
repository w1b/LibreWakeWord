package org.librewakeword

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.librewakeword.ui.HomeScreen
import org.librewakeword.ui.IntegrationsScreen
import org.librewakeword.ui.SettingsScreen
import org.librewakeword.ui.theme.LibreWakeWordTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HotwordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_REQUEST_CODE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refresh()
                    delay(1_000)
                }
            }
        }

        setContent {
            LibreWakeWordTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                state = uiState,
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenIntegrations = { navController.navigate("integrations") },
                                onRequestShizukuPermission = viewModel::requestShizukuPermission,
                                onOpenShizukuManager = viewModel::openShizukuManager,
                                onArm = viewModel::armNow,
                                onStop = viewModel::stopArming,
                            )
                        }
                        composable("integrations") {
                            IntegrationsScreen(
                                state = uiState,
                                onBack = { navController.popBackStack() },
                                onSetConsumerEnabled = viewModel::setConsumerEnabled,
                                onSetVoiceActivity = viewModel::setVoiceActivity,
                                onWebhookChanged = viewModel::updateWebhook,
                                onSaveWebhook = viewModel::saveWebhook,
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                state = uiState,
                                onBack = { navController.popBackStack() },
                                onFieldsChanged = viewModel::updateMetadataFields,
                                onSave = viewModel::saveMetadata,
                                onImport = viewModel::importModel,
                                onDismissImportMessage = viewModel::clearImportMessage,
                                onImportSecondStage = viewModel::importSecondStageModel,
                                onRefreshPublishedSecondStage =
                                    viewModel::refreshPublishedSecondStageModel,
                                onDismissSecondStageImportMessage =
                                    viewModel::clearSecondStageImportMessage,
                                onEnrollLocalVoiceMatch =
                                    viewModel::startLocalVoiceMatchEnrollment,
                                onVoiceMatchPermissionDenied =
                                    viewModel::localVoiceMatchPermissionDenied,
                                onSetLocalVoiceMatchEnabled =
                                    viewModel::setLocalVoiceMatchEnabled,
                                onDeleteLocalVoiceMatch =
                                    viewModel::deleteLocalVoiceMatch,
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val NOTIF_PERMISSION_REQUEST_CODE = 2
    }
}
