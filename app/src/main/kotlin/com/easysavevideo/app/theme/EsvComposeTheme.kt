package com.easysavevideo.app.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easysavevideo.theme.EsvTheme
import com.easysavevideo.theme.EsvVisualSnapshot
import dev.skinkit.contract.AnimationValue
import dev.skinkit.contract.BuiltInIcon
import dev.skinkit.contract.ColorMode
import dev.skinkit.contract.ColorValue
import dev.skinkit.contract.DrawableValue
import dev.skinkit.contract.FontRef
import dev.skinkit.contract.LocalIcon
import dev.skinkit.contract.StateDomain
import dev.skinkit.contract.TintPolicy
import dev.skinkit.contract.TypographyValue
import dev.skinkit.contract.VisualConfiguration
import dev.skinkit.contract.VisualState

/** One detached SkinKit snapshot supplies Foundation and Material3 alike. */
data class EsvComposeColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val border: Color,
)

data class EsvComposeButtonVisual(
    val background: Color,
    val foreground: Color,
    val textStyle: TextStyle,
)

data class EsvComposeVisuals(
    val generation: Long,
    val colors: EsvComposeColors,
    val titleTextStyle: TextStyle,
    val bodyTextStyle: TextStyle,
    val captionTextStyle: TextStyle,
    val spacingSmall: Dp,
    val spacingMedium: Dp,
    val spacingLarge: Dp,
    val corner: Dp,
    val borderWidth: Dp,
    val buttonPaddingHorizontal: Dp,
    val buttonPaddingVertical: Dp,
    val buttonCorner: Dp,
    val buttonNormal: EsvComposeButtonVisual,
    val buttonDisabled: EsvComposeButtonVisual,
    val buttonPressed: EsvComposeButtonVisual,
    val buttonFocused: EsvComposeButtonVisual,
    val backdrop: Color,
    val icon: BuiltInIcon,
    val iconSize: Dp,
    val iconTint: Color,
    /** Null means deliberately disabled; otherwise the static fallback is ready to draw. */
    val animation: Color?,
)

val LocalEsvVisuals = staticCompositionLocalOf<EsvComposeVisuals> {
    error("Wrap the screen in EsvThemeHost before reading ESV visuals")
}

/**
 * Snapshot subscription is bound to composition lifetime. Each mapped frame is
 * detached from exactly one pin, released by use inside readSnapshot, including
 * failed/abandoned composition. No ThemeReadView escapes into a UI callback.
 *
 * Configuration publication happens after successful composition. The observer
 * invalidates the entire mapped generation, never individual visual categories.
 */
@Composable
fun EsvThemeHost(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    theme: EsvTheme? = null,
    content: @Composable () -> Unit,
) {
    val configuration = VisualConfiguration(
        colorMode = if (darkTheme) ColorMode.DARK else ColorMode.LIGHT,
        fontScale = LocalDensity.current.fontScale.toDouble().coerceIn(0.5, 5.0),
        reducedMotion = reducedMotion,
    )
    val currentTheme = theme ?: remember { EsvTheme(configuration) }
    var generation by remember(currentTheme) { mutableStateOf(currentTheme.provider.snapshot.generation) }

    DisposableEffect(currentTheme) {
        val subscription = currentTheme.provider.observeSnapshot { snapshot ->
            generation = snapshot.generation
        }
        onDispose { subscription.close() }
    }
    SideEffect { currentTheme.provider.updateConfiguration(configuration) }

    val visuals = remember(currentTheme, generation) { currentTheme.readSnapshot().toComposeVisuals() }
    val colors = visuals.colors
    val scheme = lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent,
        primaryContainer = colors.surface, onPrimaryContainer = colors.text,
        inversePrimary = colors.accent,
        secondary = colors.accent, onSecondary = colors.onAccent,
        secondaryContainer = colors.surface, onSecondaryContainer = colors.text,
        tertiary = colors.accent, onTertiary = colors.onAccent,
        tertiaryContainer = colors.surface, onTertiaryContainer = colors.text,
        background = colors.bg, onBackground = colors.text,
        surface = colors.surface, onSurface = colors.text,
        surfaceVariant = colors.bg, onSurfaceVariant = colors.muted,
        surfaceTint = colors.accent,
        inverseSurface = colors.text, inverseOnSurface = colors.surface,
        error = colors.error, onError = colors.surface,
        errorContainer = colors.surface, onErrorContainer = colors.error,
        outline = colors.border, outlineVariant = colors.border,
        scrim = colors.text,
        surfaceBright = colors.surface, surfaceDim = colors.bg,
        surfaceContainer = colors.surface, surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surface, surfaceContainerLow = colors.bg,
        surfaceContainerLowest = colors.bg,
    )
    val typography = Typography(
        displayLarge = visuals.titleTextStyle, displayMedium = visuals.titleTextStyle,
        displaySmall = visuals.titleTextStyle, headlineLarge = visuals.titleTextStyle,
        headlineMedium = visuals.titleTextStyle, headlineSmall = visuals.titleTextStyle,
        titleLarge = visuals.titleTextStyle, titleMedium = visuals.bodyTextStyle,
        titleSmall = visuals.bodyTextStyle, bodyLarge = visuals.bodyTextStyle,
        bodyMedium = visuals.bodyTextStyle, bodySmall = visuals.captionTextStyle,
        labelLarge = visuals.bodyTextStyle, labelMedium = visuals.captionTextStyle,
        labelSmall = visuals.captionTextStyle,
    )
    val shape = RoundedCornerShape(visuals.corner)
    CompositionLocalProvider(LocalEsvVisuals provides visuals) {
        MaterialTheme(colorScheme = scheme, typography = typography,
            shapes = Shapes(shape, shape, shape, shape, shape), content = content)
    }
}

@Composable
fun EsvText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalEsvVisuals.current.bodyTextStyle,
    color: Color = LocalEsvVisuals.current.colors.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Uses the SDK button state priority and all required state-specific visuals. */
@Composable
fun EsvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val visuals = LocalEsvVisuals.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val activeStates = buildSet {
        if (!enabled) add(VisualState.DISABLED)
        if (pressed) add(VisualState.PRESSED)
        if (focused) add(VisualState.FOCUSED)
    }
    val visual = when (StateDomain.BUTTON.resolve(activeStates)) {
        VisualState.DISABLED -> visuals.buttonDisabled
        VisualState.PRESSED -> visuals.buttonPressed
        VisualState.FOCUSED -> visuals.buttonFocused
        else -> visuals.buttonNormal
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        shape = RoundedCornerShape(visuals.buttonCorner),
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = visual.background, contentColor = visual.foreground,
            disabledContainerColor = visuals.buttonDisabled.background,
            disabledContentColor = visuals.buttonDisabled.foreground,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = visuals.buttonPaddingHorizontal, vertical = visuals.buttonPaddingVertical),
        modifier = modifier
            // Touch target policy belongs to the app, independent of skin dimensions.
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Text(text, style = visual.textStyle.copy(color = visual.foreground))
    }
}

/** In-memory drawable mapping; there is no resource load on the render path. */
@Composable
fun EsvBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(LocalEsvVisuals.current.backdrop)) { content() }
}

/** Static-only animation mapping. Disabled deliberately emits no decoration. */
@Composable
fun EsvThemeDecoration(modifier: Modifier = Modifier) {
    val color = LocalEsvVisuals.current.animation ?: return
    Box(modifier.background(color))
}

/** Small app-owned vector adapter for the six Contract built-ins; no icon pack. */
@Composable
fun EsvThemeIcon(contentDescription: String?, modifier: Modifier = Modifier) {
    val visuals = LocalEsvVisuals.current
    val accessible = if (contentDescription == null) modifier else modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(accessible.size(visuals.iconSize)) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = visuals.iconTint,
            start = point(x1, y1),
            end = point(x2, y2),
            strokeWidth = 2f * unit,
            cap = StrokeCap.Round,
        )
        when (visuals.icon) {
            BuiltInIcon.ADD -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            BuiltInIcon.CLOSE -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            BuiltInIcon.CHECK -> { line(4f, 12f, 9f, 17f); line(9f, 17f, 20f, 6f) }
            BuiltInIcon.SEARCH -> {
                drawCircle(visuals.iconTint, 6.5f * unit, point(10f, 10f), style = Stroke(2f * unit))
                line(15f, 15f, 21f, 21f)
            }
            BuiltInIcon.MORE -> for (x in listOf(5f, 12f, 19f)) {
                drawCircle(visuals.iconTint, 1.7f * unit, point(x, 12f))
            }
            BuiltInIcon.WARNING -> {
                val triangle = Path().apply {
                    moveTo(12f * unit, 3f * unit)
                    lineTo(22f * unit, 21f * unit)
                    lineTo(2f * unit, 21f * unit)
                    close()
                }
                drawPath(triangle, visuals.iconTint, style = Stroke(2f * unit))
                line(12f, 9f, 12f, 14f)
                drawCircle(visuals.iconTint, unit, point(12f, 18f))
            }
        }
    }
}

private fun EsvVisualSnapshot.toComposeVisuals(): EsvComposeVisuals {
    fun buttonVisual(state: VisualState): EsvComposeButtonVisual = button.forState(state).let {
        EsvComposeButtonVisual(it.background.toComposeColor(), it.foreground.toComposeColor(), it.typography.toTextStyle())
    }
    return EsvComposeVisuals(
        generation = snapshot.generation,
        colors = EsvComposeColors(
            bg = colors.bg.toComposeColor(),
            surface = colors.surface.toComposeColor(),
            text = colors.text.toComposeColor(),
            muted = colors.muted.toComposeColor(),
            accent = colors.accent.toComposeColor(),
            onAccent = colors.onAccent.toComposeColor(),
            error = colors.error.toComposeColor(),
            border = colors.border.toComposeColor(),
        ),
        titleTextStyle = title.toTextStyle(),
        bodyTextStyle = body.toTextStyle(),
        captionTextStyle = caption.toTextStyle(),
        spacingSmall = spacingSmall.amount.toFloat().dp,
        spacingMedium = spacingMedium.amount.toFloat().dp,
        spacingLarge = spacingLarge.amount.toFloat().dp,
        corner = corner.amount.toFloat().dp,
        borderWidth = borderWidth.amount.toFloat().dp,
        buttonPaddingHorizontal = buttonPaddingHorizontal.amount.toFloat().dp,
        buttonPaddingVertical = buttonPaddingVertical.amount.toFloat().dp,
        buttonCorner = button.corner.amount.toFloat().dp,
        buttonNormal = buttonVisual(VisualState.NORMAL),
        buttonDisabled = buttonVisual(VisualState.DISABLED),
        buttonPressed = buttonVisual(VisualState.PRESSED),
        buttonFocused = buttonVisual(VisualState.FOCUSED),
        backdrop = backdrop.toFallbackColor(),
        icon = when (val source = icon.source) {
            is BuiltInIcon -> source
            is LocalIcon -> source.fallback
        },
        iconSize = icon.size.amount.toFloat().dp,
        iconTint = when (val tint = icon.tint) {
            is TintPolicy.Color -> tint.color.toComposeColor()
            TintPolicy.Original -> colors.text.toComposeColor()
        },
        animation = when (val value = animation) {
            AnimationValue.Disabled -> null
            is AnimationValue.Static -> value.drawable.toFallbackColor()
            is AnimationValue.Declarative -> value.fallback.drawable.toFallbackColor()
        },
    )
}

private fun ColorValue.toComposeColor(): Color = Color(toArgb())

private fun DrawableValue.toFallbackColor(): Color = when (this) {
    is DrawableValue.Solid -> color.toComposeColor()
    is DrawableValue.Local -> fallback.color.toComposeColor()
}

private fun TypographyValue.toTextStyle(): TextStyle {
    val family = when (val value = font) {
        is FontRef.System -> value.family
        is FontRef.Local -> value.fallback.family
    }
    return TextStyle(
        fontFamily = when (family) {
            "serif" -> FontFamily.Serif
            "monospace" -> FontFamily.Monospace
            else -> FontFamily.SansSerif
        },
        // Compose applies the user's density/fontScale. Multiplying here would scale twice.
        fontSize = fontSize.toFloat().sp,
        lineHeight = lineHeight.toFloat().sp,
        fontWeight = FontWeight(weight),
    )
}
