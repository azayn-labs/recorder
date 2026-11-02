package com.azayn.recorder

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Azayn mobile look, as used by Azayn Health and Azayn Pay (healthu mobile
 * src/shared/ui/tokens.ts): a cool grey ground with white hairline-bordered panels (near-black
 * navy in dark mode), Inter, and one blue -> indigo -> violet gradient reserved for primary
 * actions. Values are copied from those tokens; keep them in step rather than inventing new ones.
 */

data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primarySoft: Color,
    val primaryTint: Color,
    val error: Color,
    val errorBg: Color,
    val aurora: List<Color>,
)

private val LightPalette = Palette(
    dark = false,
    background = Color(0xFFEDEFF5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F1F6),
    surface3 = Color(0xFFE5E7F0),
    border = Color(0xFFE5E7F0),
    borderStrong = Color(0xFFD3D7E6),
    textPrimary = Color(0xFF1A1D29),
    textSecondary = Color(0xFF4A4F63),
    textTertiary = Color(0xFF6B7085),
    // A darker blue than the logo's for text and icons on white (4.9:1); fills keep the logo blue.
    primary = Color(0xFF1670D4),
    primarySoft = Color(0xFFDCEBFC),
    primaryTint = Color(0x1A1670D4),
    error = Color(0xFFD93A37),
    errorBg = Color(0xFFFDECEC),
    aurora = listOf(Color(0x3D2F9BFF), Color(0x1A7C3AED), Color(0x007C3AED)),
)

private val DarkPalette = Palette(
    dark = true,
    background = Color(0xFF060912),
    surface = Color(0xFF0A0E1A),
    surface2 = Color(0xFF12172A),
    surface3 = Color(0xFF1A2036),
    border = Color(0xFF171C2E),
    borderStrong = Color(0xFF252C47),
    textPrimary = Color(0xFFE7E9F3),
    textSecondary = Color(0xFFA9AEC2),
    textTertiary = Color(0xFF8A8FA3),
    primary = Color(0xFF2F9BFF),
    primarySoft = Color(0xFF10233F),
    primaryTint = Color(0x1F2F9BFF),
    error = Color(0xFFF25F5C),
    errorBg = Color(0xFF351717),
    aurora = listOf(Color(0x332F9BFF), Color(0x247C3AED), Color(0x007C3AED)),
)

/** The signature gradient: primary buttons and the logo tile only. */
val AzaynGradient = Brush.linearGradient(listOf(Color(0xFF2F9BFF), Color(0xFF4F46E5), Color(0xFF7C3AED)))

/** The gradient with a darker first stop, for surfaces that carry white text (>= 4.5:1). */
val AzaynStripGradient = Brush.linearGradient(listOf(Color(0xFF1670D4), Color(0xFF4F46E5), Color(0xFF7C3AED)))

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Theme choice, as in Health's Settings: follow the phone, or force light/dark. Stored per device. */
enum class ThemePref(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

object UiPrefs {
    var theme by mutableStateOf(ThemePref.SYSTEM)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        theme = runCatching { ThemePref.valueOf(prefs(ctx).getString("theme", null)!!) }.getOrDefault(ThemePref.SYSTEM)
    }

    fun setTheme(ctx: Context, t: ThemePref) {
        theme = t
        prefs(ctx).edit().putString("theme", t.name).apply()
    }
}

/** Current palette; follows the phone's light/dark setting. */
val az: Palette @Composable get() = LocalPalette.current

@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun interTypography(): Typography {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.5).sp),
        titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.3.sp),
        labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp),
        labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.3.sp),
    )
}

@Composable
fun AzaynTheme(content: @Composable () -> Unit) {
    val dark = when (UiPrefs.theme) {
        ThemePref.SYSTEM -> isSystemInDarkTheme()
        ThemePref.LIGHT -> false
        ThemePref.DARK -> true
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.primary,
        onPrimary = Color.White,
        primaryContainer = p.primarySoft,
        onPrimaryContainer = p.primary,
        secondaryContainer = p.primarySoft,
        onSecondaryContainer = p.primary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.textSecondary,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surface2,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.surface,
        outline = p.borderStrong,
        outlineVariant = p.border,
        error = p.error,
        errorContainer = p.errorBg,
        onErrorContainer = p.error,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = interTypography(), content = content)
    }
}

// ---- building blocks (mirroring Health's shared/ui components) ----

/** Screen ground with the aurora wash pooling at the top. */
@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(az.background)) {
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.verticalGradient(az.aurora)))
        content()
    }
}

/** Large left-aligned screen title, with optional leading navigation and trailing actions. */
@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    inset: Dp = 16.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = inset, end = inset / 2, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) {
            navigation()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = az.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = az.textTertiary)
        }
        actions()
    }
}

/** Round outlined 38dp button, as used in Health's screen headers (back, actions). */
@Composable
fun HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(az.surface).border(1.dp, az.border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = az.textPrimary, modifier = Modifier.size(20.dp)) }
}

/** White (navy in dark mode) hairline-bordered panel, radius 20. */
@Composable
fun Panel(modifier: Modifier = Modifier, fill: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(fill ?: az.surface).border(1.dp, az.border, shape),
        content = content,
    )
}

@Composable
fun PanelDivider(inset: Boolean = true) {
    HorizontalDivider(Modifier.padding(start = if (inset) 56.dp else 0.dp), thickness = 1.dp, color = az.border)
}

/** Small uppercase label above a panel. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = az.textTertiary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A row inside a panel: leading icon (or custom leading), title, hint, trailing content. */
@Composable
fun PanelRow(
    title: String,
    hint: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = az.textPrimary,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tint by animateColorAsState(if (selected) az.primaryTint else Color.Transparent, label = "rowSelection")
    Row(
        Modifier.fillMaxWidth()
            .background(tint)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick)
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(14.dp)) }
            icon != null -> {
                Icon(icon, null, tint = if (enabled) az.primary else az.textTertiary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (enabled) titleColor else az.textTertiary,
            )
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = az.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Multi-select marker in a row's leading slot: filled check when selected, an empty ring when not. */
@Composable
fun SelectionMark(selected: Boolean, size: Int = 40) {
    val fill by animateColorAsState(if (selected) az.primary else Color.Transparent, label = "markFill")
    val ring by animateColorAsState(if (selected) az.primary else az.textTertiary, label = "markRing")
    val scale by animateFloatAsState(if (selected) 1f else 0.85f, label = "markScale")
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size((size * 0.7).dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(fill).border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size((size * 0.45).dp))
        }
    }
}

@Composable
fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = az.textTertiary)
}

/** Primary action: pill filled with the signature gradient, white label. */
@Composable
fun GradientButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(AzaynGradient).clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
    }
}

/** Filter chip: pill, raised-control fill; the selected one takes the accent tint. */
@Composable
fun PillChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (selected) az.primarySoft else az.surface)
            .border(1.dp, if (selected) az.primary.copy(alpha = 0.35f) else az.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) az.primary else az.textSecondary,
        )
    }
}

// Health's bottom bar geometry (healthu mobile src/shared/ui/bottom-tab-bar.tsx), in dp: a rounded
// bar whose top edge dips into a bowl around a gradient ball. Here the ball doesn't hop between tabs;
// it stays in the middle as the Record button.
private const val BAR_HEIGHT = 64f
private const val BAR_CORNER = 20f
// Larger than Health's 25dp: here the ball is the Record button and should stand out.
private const val BALL_R = 31f
private const val BALL_RISE = 2f
private const val NOTCH_GAP = 6f
private const val SHOULDER = 10f
private const val NOTCH_DEPTH = BALL_R + NOTCH_GAP - BALL_RISE
private const val CLEARANCE = 10f

/** The bar outline: a rounded rectangle whose top edge dips into a bowl centred on [x] (all in px). */
private fun notchPath(w: Float, h: Float, x: Float, dp: Float): Path {
    val corner = BAR_CORNER * dp
    val shoulder = SHOULDER * dp
    val half = (BALL_R + NOTCH_GAP + SHOULDER) * dp
    val a = maxOf(corner, x - half)
    val b = minOf(w - corner, x + half)
    val bowl = (BALL_R + NOTCH_GAP) * dp * 0.95f
    val depth = NOTCH_DEPTH * dp
    return Path().apply {
        moveTo(corner, 0f)
        lineTo(a, 0f)
        cubicTo(a + shoulder * 0.9f, 0f, x - bowl, depth, x, depth)
        cubicTo(x + bowl, depth, b - shoulder * 0.9f, 0f, b, 0f)
        lineTo(w - corner, 0f)
        quadraticTo(w, 0f, w, corner)
        lineTo(w, h - corner)
        quadraticTo(w, h, w - corner, h)
        lineTo(corner, h)
        quadraticTo(0f, h, 0f, h - corner)
        lineTo(0f, corner)
        quadraticTo(0f, 0f, corner, 0f)
        close()
    }
}

/**
 * Health's notched bottom bar with two tabs and the gradient ball in the middle as a button.
 * Tabs show outline icons; only the active one is labelled. [centerLabel] sits under the ball.
 */
@Composable
fun NotchedNavBar(
    tabs: List<Pair<ImageVector, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    centerIcon: ImageVector,
    centerLabel: String,
    centerLabelColor: Color,
    centerDescription: String,
    onCenter: () -> Unit,
    modifier: Modifier = Modifier,
    centerColors: List<Color> = listOf(Color(0xFF2F9BFF), Color(0xFF4F46E5)),
    centerScale: Float = 1f,
    /** A soft pulsing halo behind the ball (while recording). */
    centerGlow: Color? = null,
) {
    val scale by animateFloatAsState(centerScale, animationSpec = spring(dampingRatio = 0.55f), label = "ballScale")
    val c0 by animateColorAsState(centerColors[0], label = "ball0")
    val c1 by animateColorAsState(centerColors[1], label = "ball1")
    val pulse = rememberInfiniteTransition(label = "glow")
    val glowT by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "glowT")
    val fill = az.surface
    val stroke = az.border
    Box(
        modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = (CLEARANCE + BALL_R + BALL_RISE).dp, bottom = 8.dp)
            .height(BAR_HEIGHT.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val path = notchPath(size.width, size.height, size.width / 2, density)
            drawPath(path, fill)
            drawPath(path, stroke, style = Stroke(width = 1.dp.toPx()))
        }
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, (icon, label) ->
                if (i == 1) {
                    // The space under the ball, holding its label at the bar's label line.
                    Box(Modifier.width(((BALL_R + NOTCH_GAP + SHOULDER) * 2).dp).height(BAR_HEIGHT.dp)) {
                        Text(
                            centerLabel, style = MaterialTheme.typography.labelSmall, color = centerLabelColor,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        )
                    }
                }
                val active = i == selected
                Box(
                    Modifier.weight(1f).height(BAR_HEIGHT.dp).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon, label, tint = if (active) az.primary else az.textTertiary,
                        modifier = Modifier.size(22.dp).offset(y = if (active) (-7).dp else 0.dp),
                    )
                    if (active) {
                        Text(
                            label, style = MaterialTheme.typography.labelSmall, color = az.textPrimary,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        )
                    }
                }
            }
        }
        if (centerGlow != null) {
            // Two expanding, fading rings, half a cycle apart.
            for (phase in listOf(0f, 0.5f)) {
                val t = (glowT + phase) % 1f
                Box(
                    Modifier.align(Alignment.TopCenter).offset(y = (-(BALL_R + BALL_RISE)).dp)
                        .size((BALL_R * 2).dp)
                        .graphicsLayer {
                            val sc = scale * (1f + 0.55f * t)
                            scaleX = sc; scaleY = sc; alpha = 0.45f * (1f - t)
                        }
                        .clip(CircleShape).background(centerGlow),
                )
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-(BALL_R + BALL_RISE)).dp)
                .size((BALL_R * 2).dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(if (centerGlow != null) 12.dp else 6.dp, CircleShape, spotColor = centerGlow ?: Color.Black)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c0, c1)))
                .clickable(onClickLabel = centerDescription, onClick = onCenter),
            contentAlignment = Alignment.Center,
        ) { Icon(centerIcon, centerDescription, tint = Color.White, modifier = Modifier.size(30.dp)) }
    }
}

/** Health's segmented tabs: a pill track with the selected segment raised on the panel colour. */
@Composable
fun <T> SegmentedTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(az.surface2).border(1.dp, az.border, CircleShape).padding(4.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (on) az.surface else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, az.borderStrong, CircleShape) else Modifier)
                    .clickable { onSelect(o) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) az.textPrimary else az.textSecondary,
                )
            }
        }
    }
}

/** Circular icon well tinted with the accent. */
@Composable
fun IconWell(icon: ImageVector, tint: Color = az.primary, fill: Color = az.primaryTint, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp))
    }
}

fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

object Links {
    const val REPO = "https://github.com/azayn-labs/recorder"
    const val KOFI = "https://ko-fi.com/azayn"
    const val LICENSE = "$REPO/blob/main/LICENSE"
}
