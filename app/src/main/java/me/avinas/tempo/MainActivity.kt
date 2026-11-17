package me.avinas.tempo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import me.avinas.tempo.data.analytics.OnboardingAction
import me.avinas.tempo.data.analytics.OnboardingStepName
import me.avinas.tempo.ui.navigation.AppNavigation
import me.avinas.tempo.ui.navigation.StreakVisibilityViewModel
import me.avinas.tempo.ui.components.LocalHideStreaks
import me.avinas.tempo.ui.onboarding.BatteryOptimizationScreen
import me.avinas.tempo.ui.onboarding.HowItWorksScreen
import me.avinas.tempo.ui.onboarding.isBatteryOptimizationDisabled
import me.avinas.tempo.ui.permissions.isNotificationListenerEnabled
import me.avinas.tempo.ui.onboarding.OnboardingViewModel
import me.avinas.tempo.ui.onboarding.PrivacyExplainerScreen
import me.avinas.tempo.ui.onboarding.WelcomeScreen
import me.avinas.tempo.ui.permissions.PermissionScreen
import me.avinas.tempo.ui.theme.TempoTheme
import me.avinas.tempo.utils.OemBackgroundHelper
import me.avinas.tempo.worker.LastFmSyncWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @javax.inject.Inject
    lateinit var walkthroughController: me.avinas.tempo.ui.components.WalkthroughController

    @javax.inject.Inject
    lateinit var analyticsDailyReporter: me.avinas.tempo.data.analytics.AnalyticsDailyReporter
    
    private val navigationTrigger = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkNavigationIntent(intent)

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
                    // Streak display is one app-wide switch. Providing it here means a screen, a
                    // dialog or a share canvas never has to load preferences just to know whether
                    // streaks belong on it — see LocalHideStreaks.
                    val streakVisibility: StreakVisibilityViewModel = hiltViewModel()
                    val hideStreaks by streakVisibility.hideStreaks.collectAsStateWithLifecycle()
                    CompositionLocalProvider(LocalHideStreaks provides hideStreaks) {
                        TempoApp(
                            walkthroughController = walkthroughController,
                            onSetupComplete = {
                                // Schedule the health worker after setup is complete
                                ServiceHealthWorker.schedule(this)
                            },
                            navigationTrigger = navigationTrigger.value
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Records that the user brought Tempo's UI to the foreground today.
     *
     * `onResume` and not `onCreate`. The tracking service is a foreground service,
     * so this process outlives the activity for hours or days — `onCreate` therefore fires once
     * per *process*, not once per app open. A user who reopens Tempo from Recents the next
     * morning, with the process still alive, would never reach `onCreate` and would be missing
     * from that day's active users. That is an undercount in the one figure this exists to
     * produce, and it would have looked like a plausible dip rather than a bug.
     *
     * Repeated calls are free: after the first of the day, the reporter is one atomic
     * comparison. Fire-and-forget, so a reporting write never sits in front of drawing a frame.
     */
    override fun onResume() {
        super.onResume()
        // Recover plays missed while an aggressive OEM had this process killed. Last.fm is the
        // only surviving record of them - the listener cannot report what it never saw. The work
        // decides for itself whether a gap exists: one request when there is one, nothing when
        // there is not, and no wakeup either way because the app is already awake.
        // The periodic health check only bounds the dead window to 30 minutes; while the user is
        // in the app that wait is pointless. Same check, run once now: it rebinds a listener the
        // OEM killed, or does nothing at all when the heartbeat is healthy.
        ServiceHealthWorker.enqueueCheckNow(this)
        LastFmSyncWorker.enqueueCatchUp(this)
        lifecycleScope.launch {
            analyticsDailyReporter.markUiOpened()
            analyticsDailyReporter.reportPresenceIfDue()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkNavigationIntent(intent)
    }
    
    private fun checkNavigationIntent(intent: Intent?) {
        if (intent?.hasExtra("navigate_to") == true) {
            val dest = intent.getStringExtra("navigate_to")
            if (dest != null) {
                navigationTrigger.value = dest
                intent.removeExtra("navigate_to")
            }
        }
    }
}

enum class OnboardingStep {
    WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, RESTORE, COMPLETED
}

/**
 * The onboarding step machine has no analytics equivalent for COMPLETED — it is the app, not
 * a setup screen — so that step reports nothing.
 */
private fun OnboardingStep.toAnalyticsStep(): OnboardingStepName? = when (this) {
    OnboardingStep.WELCOME -> OnboardingStepName.WELCOME
    OnboardingStep.HOW_IT_WORKS -> OnboardingStepName.HOW_IT_WORKS
    OnboardingStep.PRIVACY -> OnboardingStepName.PRIVACY
    OnboardingStep.PERMISSION -> OnboardingStepName.PERMISSION
    OnboardingStep.BATTERY -> OnboardingStepName.BATTERY
    OnboardingStep.RESTORE -> OnboardingStepName.RESTORE
    OnboardingStep.COMPLETED -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempoApp(
    walkthroughController: me.avinas.tempo.ui.components.WalkthroughController,
    onSetupComplete: () -> Unit,
    navigationTrigger: String? = null,
    viewModel: OnboardingViewModel = hiltViewModel()
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

    // Onboarding funnel reporting state. The step machine lives in composition rather than in
    // the ViewModel, so the timing state has to live here too — the ViewModel only receives
    // the finished measurements.
    var stepStartedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var onboardingStartedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var skippedSteps by remember { mutableIntStateOf(0) }

    /**
     * The single place a step transition happens. Routing every move through here is what
     * makes the funnel complete: a step cannot be reached without also reporting how it was
     * left and how long it was shown.
     */
    fun goTo(step: OnboardingStep, action: OnboardingAction) {
        val leaving = currentStep
        if (action == OnboardingAction.SKIP) skippedSteps++

        leaving.toAnalyticsStep()?.let { stepName ->
            viewModel.onStepLeft(
                step = stepName,
                action = action,
                stepMillis = System.currentTimeMillis() - stepStartedAt
            )
        }

        stepStartedAt = System.currentTimeMillis()
        currentStep = step
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

    // Xiaomi guidance popup state
    val isXiaomiDevice = remember { OemBackgroundHelper.isXiaomiDevice() }
    var showXiaomiGuidance by remember { mutableStateOf(false) }
    var xiaomiGuidanceDismissed by remember { mutableStateOf(false) }
    var localNavigationTrigger by remember { mutableStateOf<String?>(null) }
    // ponytail: one drift language app-wide — 8% width (~30px) + fade, enter
    // slower than exit; full-width pager slide fought every screen's entrance.
    val reducedMotion = me.avinas.tempo.ui.theme.rememberReducedMotion()

    // Show Xiaomi guidance popup after onboarding completes for first-time Xiaomi users
    LaunchedEffect(uiState.isOnboardingCompleted, uiState.xiaomiGuidanceShown) {
        if (uiState.isOnboardingCompleted && isXiaomiDevice && !uiState.xiaomiGuidanceShown && !xiaomiGuidanceDismissed) {
            showXiaomiGuidance = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(180))
                } else if (targetState == OnboardingStep.COMPLETED || initialState == OnboardingStep.COMPLETED) {
                    fadeIn(animationSpec = tween(320, easing = FastOutSlowInEasing)) togetherWith fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing))
                } else if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { (it * 0.08f).toInt() } + fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { -(it * 0.08f).toInt() } + fadeOut(animationSpec = tween(180)))
                } else {
                    (slideInHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { -(it * 0.08f).toInt() } + fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { (it * 0.08f).toInt() } + fadeOut(animationSpec = tween(180)))
                }
            },
            label = "onboarding_step_transition"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> {
                    WelcomeScreen(
                        onGetStarted = { goTo(OnboardingStep.HOW_IT_WORKS, OnboardingAction.NEXT) },
                        onSkip = {
                            // Skip educational intro directly to permissions, never bypass tracking
                            goTo(OnboardingStep.PERMISSION, OnboardingAction.SKIP)
                        }
                    )
                }
                OnboardingStep.HOW_IT_WORKS -> {
                    HowItWorksScreen(
                        onNext = { goTo(OnboardingStep.PRIVACY, OnboardingAction.NEXT) },
                        onSkip = {
                            goTo(OnboardingStep.PERMISSION, OnboardingAction.SKIP)
                        }
                    )
                }
                OnboardingStep.PRIVACY -> {
                    PrivacyExplainerScreen(
                        onNext = { goTo(OnboardingStep.PERMISSION, OnboardingAction.NEXT) },
                        onSkip = {
                            goTo(OnboardingStep.PERMISSION, OnboardingAction.SKIP)
                        }
                    )
                }
                OnboardingStep.PERMISSION -> {
                    PermissionScreen(
                        onPermissionGranted = {
                            goTo(OnboardingStep.BATTERY, OnboardingAction.NEXT)
                        },
                        onSkip = {
                            goTo(OnboardingStep.BATTERY, OnboardingAction.SKIP)
                        }
                    )
                }
                OnboardingStep.BATTERY -> {
                    BatteryOptimizationScreen(
                        onOptimize = {
                            goTo(OnboardingStep.RESTORE, OnboardingAction.NEXT)
                        },
                        onSkip = {
                            goTo(OnboardingStep.RESTORE, OnboardingAction.SKIP)
                        },
                        onBack = {
                            // ponytail: PERMISSION auto-forwards when granted — skip it then
                            goTo(
                                if (!isNotificationListenerEnabled(context)) {
                                    OnboardingStep.PERMISSION
                                } else {
                                    OnboardingStep.PRIVACY
                                },
                                OnboardingAction.BACK
                            )
                        }
                    )
                }
                OnboardingStep.RESTORE -> {
                    me.avinas.tempo.ui.onboarding.RestoreScreen(
                        onFinish = {
                            viewModel.completeOnboarding()
                            viewModel.onOnboardingFinished(
                                skippedCount = skippedSteps,
                                totalMillis = System.currentTimeMillis() - onboardingStartedAt
                            )
                            goTo(OnboardingStep.COMPLETED, OnboardingAction.NEXT)
                        },
                        onBack = {
                            // ponytail: BATTERY/PERMISSION auto-forward when already
                            // provisioned — land on the nearest step that stays put
                            goTo(
                                when {
                                    !isBatteryOptimizationDisabled(context) -> OnboardingStep.BATTERY
                                    !isNotificationListenerEnabled(context) -> OnboardingStep.PERMISSION
                                    else -> OnboardingStep.PRIVACY
                                },
                                OnboardingAction.BACK
                            )
                        }
                    )
                }
                OnboardingStep.COMPLETED -> {
                    AppNavigation(
                        walkthroughController = walkthroughController,
                        onResetToOnboarding = {
                            skippedSteps = 0
                            onboardingStartedAt = System.currentTimeMillis()
                            stepStartedAt = System.currentTimeMillis()
                            currentStep = OnboardingStep.WELCOME
                        },
                        navigationTrigger = localNavigationTrigger ?: navigationTrigger
                    )
                    // Clear local trigger after passing it
                    LaunchedEffect(localNavigationTrigger) {
                        if (localNavigationTrigger != null) {
                            localNavigationTrigger = null
                        }
                    }
                }
            }
        }

        // Xiaomi first-time guidance popup
        if (showXiaomiGuidance) {
            me.avinas.tempo.ui.components.XiaomiGuidancePopup(
                onDismiss = {
                    showXiaomiGuidance = false
                    xiaomiGuidanceDismissed = true
                    viewModel.markXiaomiGuidanceShown()
                },
                onConfigure = {
                    showXiaomiGuidance = false
                    xiaomiGuidanceDismissed = true
                    viewModel.markXiaomiGuidanceShown()
                    localNavigationTrigger = "background_protection"
                }
            )
        }
    }
}
