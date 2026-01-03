package me.avinas.tempo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.ui.navigation.AppNavigation
import me.avinas.tempo.ui.onboarding.AdvancedSettingsScreen
import me.avinas.tempo.ui.onboarding.BatteryOptimizationScreen
import me.avinas.tempo.ui.onboarding.HowItWorksScreen
import me.avinas.tempo.ui.onboarding.OnboardingViewModel
import me.avinas.tempo.ui.onboarding.PrivacyExplainerScreen
import me.avinas.tempo.ui.onboarding.SpotifyConnectionBottomSheet
import me.avinas.tempo.ui.onboarding.WelcomeScreen
import me.avinas.tempo.ui.permissions.PermissionScreen
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import me.avinas.tempo.ui.theme.TempoTheme
import me.avinas.tempo.worker.ServiceHealthWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Handle POST_NOTIFICATIONS permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            TempoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TempoApp(
                        onSetupComplete = {
                            // Schedule the health worker after setup is complete
                            ServiceHealthWorker.schedule(this)
                        }
                    )
                }
            }
        }
    }
}

enum class OnboardingStep {
    WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, SETTINGS, RESTORE, COMPLETED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempoApp(
    onSetupComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Wait for onboarding status to be loaded before deciding the initial step
    val initialStep = remember(uiState.isLoading, uiState.isOnboardingCompleted) {
        when {
            uiState.isLoading -> null
            uiState.isOnboardingCompleted -> OnboardingStep.COMPLETED
            else -> OnboardingStep.WELCOME
        }
    }
    
    var currentStep by remember(initialStep) { 
        mutableStateOf(initialStep ?: OnboardingStep.WELCOME) 
    }

    // If onboarding is already completed in DataStore, jump to COMPLETED
    LaunchedEffect(uiState.isOnboardingCompleted) {
        if (uiState.isOnboardingCompleted) {
            currentStep = OnboardingStep.COMPLETED
            onSetupComplete()
        }
    }
    
    // Show nothing while loading to prevent welcome screen flash
    if (uiState.isLoading) {
        return
    }

    // Bottom Sheet for Spotify
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showSpotifySheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentStep) {
            OnboardingStep.WELCOME -> {
                WelcomeScreen(
                    onGetStarted = { currentStep = OnboardingStep.HOW_IT_WORKS },
                    onSkip = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    }
                )
            }
            OnboardingStep.HOW_IT_WORKS -> {
                HowItWorksScreen(
                    onNext = { currentStep = OnboardingStep.PRIVACY },
                    onSkip = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    }
                )
            }
            OnboardingStep.PRIVACY -> {
                PrivacyExplainerScreen(
                    onNext = { currentStep = OnboardingStep.PERMISSION },
                    onSkip = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    }
                )
            }
            OnboardingStep.PERMISSION -> {
                PermissionScreen(
                    onPermissionGranted = { currentStep = OnboardingStep.BATTERY },
                    onSkip = { currentStep = OnboardingStep.BATTERY }
                )
            }
            OnboardingStep.BATTERY -> {
                BatteryOptimizationScreen(
                    onOptimize = {
                        currentStep = OnboardingStep.SETTINGS
                    },
                    onSkip = {
                        currentStep = OnboardingStep.SETTINGS
                    }
                )
            }
            OnboardingStep.SETTINGS -> {
                val onboardingViewModel: me.avinas.tempo.ui.onboarding.OnboardingViewModel = hiltViewModel()
                val onboardingUiState by onboardingViewModel.uiState.collectAsState()
                
                AdvancedSettingsScreen(
                    extendedAnalysisEnabled = onboardingUiState.extendedAudioAnalysisEnabled,
                    onExtendedAnalysisChange = onboardingViewModel::setExtendedAudioAnalysis,
                    mergeVersionsEnabled = onboardingUiState.mergeAlternateVersions,
                    onMergeVersionsChange = onboardingViewModel::setMergeAlternateVersions,
                    onContinue = {
                        currentStep = OnboardingStep.RESTORE
                    }
                )
            }
            OnboardingStep.RESTORE -> {
                me.avinas.tempo.ui.onboarding.RestoreScreen(
                    onFinish = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                        showSpotifySheet = true
                    },
                    onBack = {
                        currentStep = OnboardingStep.SETTINGS
                    }
                )
            }
            OnboardingStep.COMPLETED -> {
                AppNavigation(
                    onResetToOnboarding = {
                        currentStep = OnboardingStep.WELCOME
                    }
                )
            }
        }

        if (showSpotifySheet) {
            ModalBottomSheet(
                onDismissRequest = { showSpotifySheet = false },
                sheetState = sheetState
            ) {
                SpotifyConnectionBottomSheet(
                    onConnect = {
                        // Launch Spotify OAuth flow
                        val intent = spotifyViewModel.startLogin()
                        context.startActivity(intent)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSpotifySheet = false
                            }
                        }
                    },
                    onMaybeLater = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSpotifySheet = false
                            }
                        }
                    }
                )
            }
        }
    }
}
