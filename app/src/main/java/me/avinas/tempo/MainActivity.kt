package me.avinas.tempo

import android.Manifest
import android.content.Intent
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

    @javax.inject.Inject
    lateinit var walkthroughController: me.avinas.tempo.ui.components.WalkthroughController
    
    // Observable state for triggering Spotify import after auth callback
    private val spotifyImportTrigger = mutableStateOf(0)
    private val spotifyAuthFailed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check if we're returning from Spotify auth callback
        checkSpotifyAuthIntent(intent)

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
                        walkthroughController = walkthroughController,
                        onSetupComplete = {
                            // Schedule the health worker after setup is complete
                            ServiceHealthWorker.schedule(this)
                        },
                        spotifyImportTrigger = spotifyImportTrigger.value,
                        spotifyAuthFailed = spotifyAuthFailed.value
                    )
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle when activity is already running and receives new intent
        checkSpotifyAuthIntent(intent)
    }
    
    private fun checkSpotifyAuthIntent(intent: Intent?) {
        val hasSpotifyExtra = intent?.hasExtra(
            me.avinas.tempo.ui.spotify.SpotifyCallbackActivity.EXTRA_SPOTIFY_AUTH_SUCCESS
        ) ?: false
        
        if (!hasSpotifyExtra) return
        
        val spotifyAuthSuccess = intent?.getBooleanExtra(
            me.avinas.tempo.ui.spotify.SpotifyCallbackActivity.EXTRA_SPOTIFY_AUTH_SUCCESS,
            false
        ) ?: false
        
        if (spotifyAuthSuccess) {
            // Increment to trigger LaunchedEffect in Compose
            spotifyImportTrigger.value++
        } else {
            // Auth failed - signal to clear pending state
            spotifyAuthFailed.value = true
        }
    }
}

enum class OnboardingStep {
    WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, SETTINGS, RESTORE, COMPLETED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempoApp(
    walkthroughController: me.avinas.tempo.ui.components.WalkthroughController,
    onSetupComplete: () -> Unit,
    spotifyImportTrigger: Int = 0,
    spotifyAuthFailed: Boolean = false,
    viewModel: OnboardingViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Trigger Spotify history reconstruction when returning from successful auth
    // Using trigger counter to handle both onCreate and onNewIntent cases
    // This uses the "honest data only" approach: liked tracks + yearly playlists + top tracks
    LaunchedEffect(spotifyImportTrigger) {
        if (spotifyImportTrigger > 0) {
            android.util.Log.i("MainActivity", "Triggering Spotify history reconstruction after auth callback (trigger=$spotifyImportTrigger)")
            // Clear pending auth state since we're now starting the actual import
            spotifyViewModel.clearPendingAuth()
            // Use reconstructHistory() for honest data - only creates events when we have real timing data
            spotifyViewModel.reconstructHistory()
        }
    }
    
    // Clear pending auth when auth fails
    LaunchedEffect(spotifyAuthFailed) {
        if (spotifyAuthFailed) {
            android.util.Log.i("MainActivity", "Spotify auth failed, clearing pending state")
            spotifyViewModel.clearPendingAuth()
        }
    }
    
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                        // Only show Spotify sheet if not already connected
                        if (!spotifyViewModel.isConnected()) {
                            showSpotifySheet = true
                        }
                    },
                    onBack = {
                        currentStep = OnboardingStep.SETTINGS
                    }
                )
            }
            OnboardingStep.COMPLETED -> {
                AppNavigation(
                    walkthroughController = walkthroughController,
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
