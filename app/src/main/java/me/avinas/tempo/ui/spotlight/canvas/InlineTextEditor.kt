package me.avinas.tempo.ui.spotlight.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant

/**
 * Instagram-style inline text editor overlay.
 * Shows text input centered on screen with font chips and quick action toolbar.
 */
@Composable
fun InlineTextEditor(
    textItem: CanvasTextItem,
    onTextChanged: (CanvasTextItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var localText by remember { mutableStateOf(textItem.text.takeIf { it != "Tap to edit" } ?: "") }
    var localStyle by remember { mutableStateOf(textItem.style) }
    var showColorPicker by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Auto-focus the text field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
    ) {
        // Done button
        // Done button
        Text(
            text = "Done",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = {
                    val finalText = localText.ifEmpty { "Text" }
                    onTextChanged(textItem.copy(text = finalText, style = localStyle))
                    keyboardController?.hide()
                    onDismiss()
                })
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        
        // Centered text input
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Text with current styling shown as preview
            val previewBgModifier = if (localStyle.hasBackground) {
                Modifier
                    .background(localStyle.backgroundColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            } else {
                Modifier
            }
            
            BasicTextField(
                value = localText,
                onValueChange = { localText = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = localStyle.fontPreset.fontFamily,
                    fontSize = localStyle.fontSize.sp,
                    fontWeight = if (localStyle.isBold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (localStyle.isItalic) FontStyle.Italic else FontStyle.Normal,
                    color = localStyle.color,
                    textAlign = TextAlign.Center,
                    shadow = if (localStyle.hasShadow) Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    ) else null
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val finalText = localText.ifEmpty { "Text" }
                        onTextChanged(textItem.copy(text = finalText, style = localStyle))
                        keyboardController?.hide()
                        onDismiss()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(previewBgModifier)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (localText.isEmpty()) {
                            Text(
                                "Type something...",
                                fontFamily = localStyle.fontPreset.fontFamily,
                                fontSize = localStyle.fontSize.sp,
                                color = Color.White.copy(alpha = 0.3f),
                                textAlign = TextAlign.Center
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        
        // Bottom toolbar area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            
            // Color picker (Always visible if not showing font list, or separate mode)
            // Let's keep it simple: Color dots -> Tools -> Fonts
            
            // 1. Color Palette (Compact)
            AnimatedVisibility(
                visible = showColorPicker,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                 LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(TextColors.presets) { color ->
                        ColorDot(
                            color = color,
                            isSelected = localStyle.color == color,
                            onClick = { 
                                localStyle = localStyle.copy(color = color)
                            }
                        )
                    }
                }
            }
            
            // 2. Main Formatting Toolbar (Glassy Dock)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                variant = GlassCardVariant.HighProminence,
                contentPadding = PaddingValues(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Font Selection Toggle
                    IconButton(onClick = { /* Toggle font list visibility if we had a mode for it */ }) {
                         Text(
                            "Aa",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    
                    VerticalDivider()

                    // Color Toggle
                    IconButton(onClick = { showColorPicker = !showColorPicker }) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(localStyle.color)
                                .border(2.dp, Color.White, CircleShape)
                        )
                    }
                    
                     VerticalDivider()

                    // Alignment
                    IconButton(onClick = {
                        val newAlign = when (localStyle.alignment) {
                            TextAlign.Center -> TextAlign.Left
                            TextAlign.Left -> TextAlign.Right
                            else -> TextAlign.Center
                        }
                        localStyle = localStyle.copy(alignment = newAlign)
                    }) {
                        Icon(
                            imageVector = when(localStyle.alignment) {
                                TextAlign.Left -> Icons.AutoMirrored.Outlined.FormatAlignLeft
                                TextAlign.Right -> Icons.AutoMirrored.Outlined.FormatAlignRight
                                else -> Icons.Default.FormatAlignCenter
                            },
                            contentDescription = "Align",
                            tint = Color.White
                        )
                    }
                    
                    VerticalDivider()

                    // Background/Style Toggle (Cyclic)
                     IconButton(onClick = {
                        // Cycle: None -> Outline -> Filled -> Filled (Inverted)
                        if (!localStyle.hasBackground && !localStyle.hasOutline) {
                            // -> Outline
                            localStyle = localStyle.copy(hasOutline = true)
                        } else if (localStyle.hasOutline) {
                            // -> Filled
                            localStyle = localStyle.copy(hasOutline = false, hasBackground = true, backgroundColor = Color.Black)
                        } else if (localStyle.hasBackground && localStyle.backgroundColor == Color.Black) {
                             // -> Filled (White/Inverted)
                             localStyle = localStyle.copy(backgroundColor = Color.White, color = Color.Black)
                        } else {
                            // -> None
                            localStyle = localStyle.copy(hasBackground = false, hasOutline = false, color = Color.White) // Reset color to white for safety
                        }
                    }) {
                        Icon(
                            imageVector = if (localStyle.hasBackground) Icons.Default.AutoAwesome else Icons.Outlined.AutoAwesome,
                            contentDescription = "Style",
                            tint = if (localStyle.hasBackground || localStyle.hasOutline) Color(0xFFFDE047) else Color.White
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 3. Font Presets (Horizontal Scroll)
             LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                 items(FontPreset.values()) { preset ->
                    FontStyleChip(
                        name = preset.displayName,
                        isSelected = localStyle.fontPreset == preset,
                        onClick = { localStyle = localStyle.copy(fontPreset = preset) }
                    )
                 }
            }
        }
    }
}
 
@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(Color.White.copy(alpha = 0.1f))
    )
}

@Composable
private fun FontStyleChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        variant = if (isSelected) GlassCardVariant.HighProminence else GlassCardVariant.LowProminence,
        backgroundColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.05f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = name,
            color = if (isSelected) Color.Black else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isActive) Color.White.copy(alpha = 0.2f) else Color.Transparent
            )
            .border(
                1.dp,
                if (isActive) Color.White else Color.White.copy(alpha = 0.3f),
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ColorWheelButton(
    currentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.sweepGradient(
                    colors = listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                )
            )
            .border(2.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Inner circle showing current color
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(currentColor)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
private fun ColorDot(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = if (color == Color.White || color == Color(0xFFFEF08A)) Color.Black else Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
