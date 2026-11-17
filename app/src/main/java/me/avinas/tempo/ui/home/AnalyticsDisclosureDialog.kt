package me.avinas.tempo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.TempoDialogBody
import me.avinas.tempo.ui.components.TempoDialogIcon
import me.avinas.tempo.ui.components.TempoDialogPrimaryButton
import me.avinas.tempo.ui.components.TempoDialogSecondaryButton
import me.avinas.tempo.ui.components.TempoDialogSurface
import me.avinas.tempo.ui.components.TempoDialogTitle
import me.avinas.tempo.ui.theme.TempoPrimary

/**
 * Overlapping dialog that Tempo reports anonymous app-health stats.
 *
 * Rendered inline in Home's root Box (not a platform [Dialog] window) so the
 * scrim reliably covers the feed. Light 0.35 dim keeps Home visible behind the
 * card while still distinguishing it. Nothing is transmitted until this has
 * been shown — see `AnalyticsGate.isCollectionAllowed`. Back press counts as
 * acknowledgement so the dialog cannot trap the user.
 */
@Composable
fun AnalyticsDisclosureDialog(
    viewModel: AnalyticsDisclosureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    if (!uiState.shouldShow) return

    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.onDisclosureShown()
        visible = true
    }

    fun dismissAnimated(action: () -> Unit) {
        visible = false
        scope.launch {
            delay(180)
            action()
        }
    }

    BackHandler(enabled = true) { dismissAnimated(viewModel::onAcknowledge) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* consume taps so the feed behind stays untouched */ },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(
                initialScale = 0.92f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) + fadeIn(tween(250)),
            exit = scaleOut(targetScale = 0.92f, animationSpec = tween(180)) + fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(horizontal = 24.dp),
            ) {
                TempoDialogSurface {
                    TempoDialogIcon(
                        icon = Icons.Default.Info,
                        tint = TempoPrimary,
                        size = 56,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    TempoDialogTitle(
                        text = stringResource(R.string.analytics_disclosure_title),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TempoDialogBody(
                        text = stringResource(R.string.analytics_disclosure_body),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TempoDialogBody(
                        text = stringResource(R.string.analytics_disclosure_settings_hint),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    TempoDialogPrimaryButton(
                        text = stringResource(R.string.analytics_disclosure_got_it),
                        onClick = { dismissAnimated(viewModel::onAcknowledge) },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TempoDialogSecondaryButton(
                        text = stringResource(R.string.analytics_disclosure_turn_off),
                        onClick = { dismissAnimated(viewModel::onTurnOff) },
                    )
                }
            }
        }
    }
}
