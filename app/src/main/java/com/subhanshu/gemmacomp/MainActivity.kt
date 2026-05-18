package com.subhanshu.gemmacomp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.subhanshu.gemmacomp.ui.theme.*

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GemmaCompTheme {
                val viewModel: TriageViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()

                val permissionState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                )

                LaunchedEffect(Unit) {
                    permissionState.launchMultiplePermissionRequest()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        !permissionState.allPermissionsGranted -> {
                            PermissionScreen()
                        }
                        uiState.isDownloading -> {
                            DownloadScreen(
                                progress = uiState.downloadProgress,
                                downloadedMB = uiState.downloadedMB,
                                totalMB = uiState.totalMB,
                                speedMBps = uiState.downloadSpeedMBps,
                                error = uiState.downloadError
                            )
                        }
                        uiState.isModelLoading -> {
                            LoadingScreen(status = uiState.loadingStatus)
                        }
                        else -> {
                            TriageScreen(
                                uiState = uiState,
                                onSpeak = { viewModel.startListening() },
                                onNextPatient = { viewModel.resetForNextPatient() },
                                onFrame = { viewModel.onFrameCaptured(it) },
                                onCall108 = {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:108"))
                                    startActivity(intent)
                                    viewModel.onCallMade()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Permission Screen
// ══════════════════════════════════════════════════════════

@Composable
fun PermissionScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TriageDark),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🏥", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Triage AI needs camera and\nmicrophone access to operate.",
                color = TriageTextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Please grant permissions in Settings.",
                color = TriageTextMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Loading Screen — shows model init progress
// ══════════════════════════════════════════════════════════

@Composable
fun LoadingScreen(status: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(TriageDark, Color(0xFF0F1A2E))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Pulsing medical cross
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Text(
                "⚕",
                fontSize = 64.sp,
                modifier = Modifier.scale(scale)
            )

            Spacer(Modifier.height(32.dp))

            Text(
                "GOLDEN AID",
                style = MaterialTheme.typography.headlineLarge,
                color = TriageAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Field Medical Triage Assistant",
                style = MaterialTheme.typography.titleMedium,
                color = TriageTextMuted
            )

            Spacer(Modifier.height(48.dp))

            CircularProgressIndicator(
                color = TriageAccent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = TriageTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Download Screen — model download progress
// ══════════════════════════════════════════════════════════

@Composable
fun DownloadScreen(
    progress: Int,
    downloadedMB: String,
    totalMB: String,
    speedMBps: String,
    error: String?
) {
    // Prevent the screen from turning off while downloading
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(TriageDark, Color(0xFF0F1A2E))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Pulsing download icon
            val infiniteTransition = rememberInfiniteTransition(label = "downloadPulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "downloadScale"
            )

            Text(
                "⬇\uFE0F",
                fontSize = 56.sp,
                modifier = Modifier.scale(scale)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                "GOLDEN AID",
                style = MaterialTheme.typography.headlineLarge,
                color = TriageAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Downloading AI Model",
                style = MaterialTheme.typography.titleMedium,
                color = TriageTextMuted
            )

            Spacer(Modifier.height(40.dp))

            // Progress bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = TriageBorder,
                shape = RoundedCornerShape(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress / 100f)
                        .height(8.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(TriageAccent, TriageGreen)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }

            Spacer(Modifier.height(16.dp))

            // Progress text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "$downloadedMB / $totalMB MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TriageTextSecondary
                )
                Text(
                    "$progress%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TriageAccent,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            // Speed indicator
            Text(
                "$speedMBps MB/s",
                style = MaterialTheme.typography.bodySmall,
                color = TriageTextMuted
            )

            // Error message
            if (error != null) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    color = Color(0x33FF4444),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "⚠ $error",
                        color = Color(0xFFFF6B6B),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "This is a one-time download (~2.6 GB).\nThe AI model will run entirely on your device.",
                style = MaterialTheme.typography.bodySmall,
                color = TriageTextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Main Triage Screen
// ══════════════════════════════════════════════════════════

@Composable
fun TriageScreen(
    uiState: TriageState,
    onSpeak: () -> Unit,
    onNextPatient: () -> Unit,
    onFrame: (android.graphics.Bitmap) -> Unit,
    onCall108: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Layer 1: Camera Preview (full screen background)
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onFrame = onFrame
        )

        // Layer 2: Dark gradient overlay for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f),
                            Color.Black.copy(alpha = 0.85f),
                        )
                    )
                )
        )

        // Layer 3: UI Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top: Triage Level Banner ──
            TriageBanner(
                triageLevel = uiState.triageLevel,
                hasTriageResult = uiState.gemmaResponse.isNotBlank(),
                personDetected = uiState.personDetected
            )

            Spacer(Modifier.height(8.dp))

            // ── Pose Data Bar ──
            if (uiState.posture != "unknown" && uiState.torsoAngle > 0f) {
                PoseInfoBar(
                    posture = uiState.posture,
                    angle = uiState.torsoAngle,
                    orientation = uiState.bodyOrientation
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Voice Transcript ──
            AnimatedVisibility(
                visible = uiState.voiceTranscript.isNotBlank(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TriageBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎤", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "\"${uiState.voiceTranscript}\"",
                            color = TriageTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Voice Error ──
            AnimatedVisibility(visible = uiState.voiceError != null) {
                Text(
                    text = uiState.voiceError ?: "",
                    color = TriageRed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // ── Clinical Advice Card ──
            ClinicalAdviceCard(
                response = uiState.gemmaResponse,
                isThinking = uiState.isGemmaThinking,
                isSpeaking = uiState.isSpeaking
            )

            Spacer(Modifier.height(12.dp))

            // ── Bottom Controls ──
            BottomControls(
                isListening = uiState.isListening,
                isThinking = uiState.isGemmaThinking,
                onSpeak = onSpeak,
                onNextPatient = onNextPatient,
                onCall108 = onCall108,
                callMade = uiState.callMade
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Triage Level Banner — color-coded header with pulse
// ══════════════════════════════════════════════════════════

@Composable
fun TriageBanner(triageLevel: TriageLevel, hasTriageResult: Boolean, personDetected: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = triageLevel.color,
        animationSpec = tween(500),
        label = "bannerColor"
    )

    // Show triage level label if Gemma has assigned one, otherwise show scanning status
    val statusText = when {
        hasTriageResult && triageLevel != TriageLevel.UNKNOWN -> triageLevel.label
        personDetected -> "PATIENT DETECTED"
        else -> "SCANNING FOR PATIENT…"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = statusText,
                color = triageLevel.textColor,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            if (hasTriageResult && triageLevel != TriageLevel.UNKNOWN) {
                Text(
                    text = triageLevel.description,
                    color = triageLevel.textColor.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Pose Info Bar — shows posture, angle, orientation
// ══════════════════════════════════════════════════════════

@Composable
fun PoseInfoBar(posture: String, angle: Float, orientation: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TriageBorder)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PoseChip(label = "POSTURE", value = posture.replace("_", " ").uppercase())
            PoseChip(label = "ANGLE", value = "${angle.toInt()}°")
            if (orientation.isNotBlank()) {
                PoseChip(label = "ORIENT", value = orientation.uppercase().take(12))
            }
        }
    }
}

@Composable
fun PoseChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TriageTextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = TriageYellow,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

// ══════════════════════════════════════════════════════════
//  Clinical Advice Card — glassmorphism-style card
// ══════════════════════════════════════════════════════════

@Composable
fun ClinicalAdviceCard(response: String, isThinking: Boolean, isSpeaking: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        color = TriageCard.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TriageBorder),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚕ DO THIS NOW",
                    style = MaterialTheme.typography.labelLarge,
                    color = TriageAccent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.weight(1f))
                if (isSpeaking) {
                    Text("🔊", fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "SPEAKING",
                        style = MaterialTheme.typography.labelSmall,
                        color = TriageGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = TriageBorder
            )

            // Content
            if (isThinking) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = TriageAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Analyzing patient…",
                            color = TriageTextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                Text(
                    text = response.ifEmpty { "Tap the microphone and describe what you see. GoldenAid will guide you." },
                    color = if (response.isEmpty()) TriageTextMuted else TriageTextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  Bottom Controls — Mic button + Next Patient
// ══════════════════════════════════════════════════════════

@Composable
fun BottomControls(
    isListening: Boolean,
    isThinking: Boolean,
    onSpeak: () -> Unit,
    onNextPatient: () -> Unit,
    onCall108: () -> Unit = {},
    callMade: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Next Patient button
        Button(
            onClick = onNextPatient,
            colors = ButtonDefaults.buttonColors(
                containerColor = TriageCard,
                contentColor = TriageTextSecondary
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, TriageBorder)
        ) {
            Text("↻ NEXT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        Spacer(Modifier.width(16.dp))

        // Main Mic Button with pulse animation
        MicButton(
            isListening = isListening,
            isThinking = isThinking,
            onClick = onSpeak
        )

        Spacer(Modifier.width(16.dp))

        // Call 108 Emergency button
        Button(
            onClick = onCall108,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (callMade) Color(0xFF27AE60) else TriageRed,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (callMade) "✓ 108" else "📞 108",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun MicButton(
    isListening: Boolean,
    isThinking: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isListening -> TriageRed
            isThinking -> TriageTextMuted
            else -> TriageAccent
        },
        animationSpec = tween(300),
        label = "micColor"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer pulse ring (visible when listening)
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .border(2.dp, TriageRed.copy(alpha = 0.4f), CircleShape)
            )
        }

        // Main button
        Button(
            onClick = onClick,
            enabled = !isThinking,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                disabledContainerColor = TriageTextMuted.copy(alpha = 0.3f)
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 6.dp,
                pressedElevation = 2.dp
            )
        ) {
            Text(
                text = when {
                    isListening -> "●"
                    isThinking -> "⏳"
                    else -> "🎤"
                },
                fontSize = 22.sp
            )
        }
    }
}