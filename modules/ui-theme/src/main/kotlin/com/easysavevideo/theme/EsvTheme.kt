package com.easysavevideo.theme

import dev.skinkit.contract.AnimationValue
import dev.skinkit.contract.BuiltInIcon
import dev.skinkit.contract.ButtonStyle
import dev.skinkit.contract.ButtonVisual
import dev.skinkit.contract.ColorMode
import dev.skinkit.contract.ColorRole
import dev.skinkit.contract.ColorValue
import dev.skinkit.contract.DimensionPurpose
import dev.skinkit.contract.DimensionValue
import dev.skinkit.contract.DrawableValue
import dev.skinkit.contract.FontRef
import dev.skinkit.contract.IconValue
import dev.skinkit.contract.LogicalUnit
import dev.skinkit.contract.PinnedThemeSource
import dev.skinkit.contract.ThemeSnapshotInfo
import dev.skinkit.contract.TintPolicy
import dev.skinkit.contract.TokenRegistry
import dev.skinkit.contract.TypographyValue
import dev.skinkit.contract.VisualConfiguration
import dev.skinkit.contract.VisualState
import dev.skinkit.defaults.AppDefaultTheme
import dev.skinkit.defaults.DefaultThemeProvider
import dev.skinkit.defaults.StandardSchemas

/**
 * App-owned, offline defaults. SDK types come from the pinned SkinKit 1.0.0
 * Contract/Default artifacts (contract protocol 0.0.1); this module neither copies nor replaces the SDK.
 *
 * All seven categories have explicit light/dark values. Fonts, shapes and
 * built-in icons need no resource decoder, network, Core or animation runtime.
 */
class EsvTheme(initialConfiguration: VisualConfiguration = VisualConfiguration()) {
    private val tokens = TokenRegistry.builder(APP_ID)

    val background = tokens.color("color.background", ColorRole.BACKGROUND)
    val surface = tokens.color("color.surface", ColorRole.BACKGROUND)
    val elevated = tokens.color("color.surface.elevated", ColorRole.BACKGROUND)
    val text = tokens.color("color.text.primary")
    val muted = tokens.color("color.text.secondary")
    val accent = tokens.color("color.accent", ColorRole.ACCENT)
    val onAccent = tokens.color("color.accent.foreground")
    val error = tokens.color("color.error")
    val border = tokens.color("color.border", ColorRole.BORDER)
    val title = tokens.typography("typography.title")
    val section = tokens.typography("typography.section")
    val body = tokens.typography("typography.body")
    val caption = tokens.typography("typography.caption")
    val spacingExtraSmall = tokens.dimension("dimension.spacing.extra.small", DimensionPurpose.SPACING)
    val spacingCompact = tokens.dimension("dimension.spacing.compact", DimensionPurpose.SPACING)
    val spacingExtraLarge = tokens.dimension("dimension.spacing.extra.large", DimensionPurpose.SPACING)
    val spacingSmall = tokens.dimension("dimension.spacing.small", DimensionPurpose.SPACING)
    val spacingMedium = tokens.dimension("dimension.spacing.medium", DimensionPurpose.SPACING)
    val spacingLarge = tokens.dimension("dimension.spacing.large", DimensionPurpose.SPACING)
    val corner = tokens.dimension("dimension.corner.control", DimensionPurpose.CORNER)
    val cardCorner = tokens.dimension("dimension.corner.card", DimensionPurpose.CORNER)
    val sheetCorner = tokens.dimension("dimension.corner.sheet", DimensionPurpose.CORNER)
    val buttonPaddingHorizontal = tokens.dimension("dimension.padding.button.horizontal", DimensionPurpose.PADDING)
    val buttonPaddingVertical = tokens.dimension("dimension.padding.button.vertical", DimensionPurpose.PADDING)
    val borderWidth = tokens.dimension("dimension.border.width", DimensionPurpose.BORDER)
    val backdrop = tokens.drawable("drawable.background")
    val icon = tokens.icon("icon.action.add")
    val animation = tokens.animation("animation.decoration")
    val button = tokens.component("component.button.primary", StandardSchemas.BUTTON)

    val registry: TokenRegistry = tokens.apply {
        for (foreground in listOf(text, muted, accent, error)) {
            contrastPair(foreground, background)
            contrastPair(foreground, surface)
            contrastPair(foreground, elevated)
        }
    }.build()

    val appDefaults: AppDefaultTheme = makeDefaults()
    val provider = DefaultThemeProvider(appDefaults, registry, initialConfiguration)

    init {
        // A safe fallback keeps the SDK renderable, but is not an acceptable app default.
        provider.completenessReport.requireComplete()
    }

    /**
     * One generation supplies every read. The detached values are immutable and
     * all M1 resources are in-memory, so the pin can be released immediately.
     * use also releases on an exception or an abandoned Compose calculation.
     */
    fun readSnapshot(source: PinnedThemeSource = provider): EsvVisualSnapshot = source.pin().use { view ->
        EsvVisualSnapshot(
            snapshot = view.snapshot,
            configuration = view.configuration,
            colors = EsvColors(
                bg = view.getColor(background),
                surface = view.getColor(surface),
                elevated = view.getColor(elevated),
                text = view.getColor(text),
                muted = view.getColor(muted),
                accent = view.getColor(accent),
                onAccent = view.getColor(onAccent),
                error = view.getColor(error),
                border = view.getColor(border),
            ),
            title = view.getTypography(title),
            section = view.getTypography(section),
            body = view.getTypography(body),
            caption = view.getTypography(caption),
            spacingExtraSmall = view.getDimension(spacingExtraSmall),
            spacingCompact = view.getDimension(spacingCompact),
            spacingExtraLarge = view.getDimension(spacingExtraLarge),
            spacingSmall = view.getDimension(spacingSmall),
            spacingMedium = view.getDimension(spacingMedium),
            spacingLarge = view.getDimension(spacingLarge),
            corner = view.getDimension(corner),
            cardCorner = view.getDimension(cardCorner),
            sheetCorner = view.getDimension(sheetCorner),
            buttonPaddingHorizontal = view.getDimension(buttonPaddingHorizontal),
            buttonPaddingVertical = view.getDimension(buttonPaddingVertical),
            borderWidth = view.getDimension(borderWidth),
            backdrop = view.getDrawable(backdrop),
            icon = view.getIcon(icon),
            animation = view.getAnimation(animation),
            button = view.getComponentStyle(button),
        )
    }

    private fun makeDefaults(): AppDefaultTheme {
        val builder = AppDefaultTheme.builder(APP_ID, THEME_VERSION)
        for (mode in ColorMode.entries) {
            fun color(light: Long, dark: Long) = ColorValue.argb(
                (if (mode == ColorMode.DARK) dark else light).toInt(),
            )
            val canvas = color(0xfff5f8fa, 0xff0e1b22)
            val card = color(0xffffffff, 0xff152832)
            val ink = color(0xff142b36, 0xffe5f2f5)
            val secondaryInk = color(0xff485d66, 0xffa7bcc5)
            val primary = color(0xff087582, 0xff62d9df)
            val onPrimary = color(0xffffffff, 0xff07343a)
            val line = color(0xff6e7a90, 0xff8a97ad)
            val danger = color(0xffb42336, 0xffffb3bd)
            val normalType = TypographyValue(FontRef.System(), 16.0, 400, 24.0)
            val buttonType = TypographyValue(FontRef.System(), 16.0, 600, 24.0)
            val controlCorner = dimension(12.0, DimensionPurpose.CORNER)

            builder.put(mode, background, canvas)
            builder.put(mode, surface, card)
            builder.put(mode, elevated, color(0xffedf4f6, 0xff1c3440))
            builder.put(mode, text, ink)
            builder.put(mode, muted, secondaryInk)
            builder.put(mode, accent, primary)
            builder.put(mode, onAccent, onPrimary)
            builder.put(mode, error, danger)
            builder.put(mode, border, line)
            builder.put(mode, title, TypographyValue(FontRef.System(), 24.0, 700, 32.0))
            builder.put(mode, section, TypographyValue(FontRef.System(), 20.0, 600, 28.0))
            builder.put(mode, body, normalType)
            builder.put(mode, caption, TypographyValue(FontRef.System(), 12.0, 400, 16.0))
            builder.put(mode, spacingExtraSmall, dimension(4.0, DimensionPurpose.SPACING))
            builder.put(mode, spacingCompact, dimension(12.0, DimensionPurpose.SPACING))
            builder.put(mode, spacingExtraLarge, dimension(32.0, DimensionPurpose.SPACING))
            builder.put(mode, spacingSmall, dimension(8.0, DimensionPurpose.SPACING))
            builder.put(mode, spacingMedium, dimension(16.0, DimensionPurpose.SPACING))
            builder.put(mode, spacingLarge, dimension(24.0, DimensionPurpose.SPACING))
            builder.put(mode, corner, controlCorner)
            builder.put(mode, cardCorner, dimension(16.0, DimensionPurpose.CORNER))
            builder.put(mode, sheetCorner, dimension(24.0, DimensionPurpose.CORNER))
            builder.put(mode, buttonPaddingHorizontal, dimension(20.0, DimensionPurpose.PADDING))
            builder.put(mode, buttonPaddingVertical, dimension(12.0, DimensionPurpose.PADDING))
            builder.put(mode, borderWidth, dimension(1.0, DimensionPurpose.BORDER))
            builder.put(mode, backdrop, DrawableValue.Solid(canvas))
            builder.put(mode, icon, IconValue(
                BuiltInIcon.ADD,
                dimension(24.0, DimensionPurpose.ICON),
                TintPolicy.Color(primary),
            ))
            // The selected Default provider has no player. Disabled is a valid terminal value.
            builder.put(mode, animation, AnimationValue.Disabled)
            builder.put(mode, button, ButtonStyle.of(mapOf(
                VisualState.NORMAL to ButtonVisual(primary, onPrimary, buttonType),
                VisualState.PRESSED to ButtonVisual(color(0xff075e69, 0xff8fe4e8), onPrimary, buttonType),
                VisualState.FOCUSED to ButtonVisual(color(0xff064b55, 0xffa4ecef), onPrimary, buttonType),
                VisualState.DISABLED to ButtonVisual(
                    color(0xffe2e7f0, 0xff303b50),
                    color(0xff485d66, 0xffbec9df),
                    buttonType,
                ),
            ), controlCorner))
        }
        return builder.build()
    }

    companion object {
        const val APP_ID = "com.easysavevideo.app"
        const val THEME_VERSION = "0.1.0"

        private fun dimension(value: Double, purpose: DimensionPurpose) =
            DimensionValue(value, LogicalUnit.LENGTH, purpose)
    }
}

/** Immutable Contract values, without Android or Compose dependencies. */
data class EsvColors(
    val bg: ColorValue,
    val surface: ColorValue,
    val elevated: ColorValue,
    val text: ColorValue,
    val muted: ColorValue,
    val accent: ColorValue,
    val onAccent: ColorValue,
    val error: ColorValue,
    val border: ColorValue,
)

data class EsvVisualSnapshot(
    val snapshot: ThemeSnapshotInfo,
    val configuration: VisualConfiguration,
    val colors: EsvColors,
    val title: TypographyValue,
    val section: TypographyValue,
    val body: TypographyValue,
    val caption: TypographyValue,
    val spacingExtraSmall: DimensionValue,
    val spacingCompact: DimensionValue,
    val spacingExtraLarge: DimensionValue,
    val spacingSmall: DimensionValue,
    val spacingMedium: DimensionValue,
    val spacingLarge: DimensionValue,
    val corner: DimensionValue,
    val cardCorner: DimensionValue,
    val sheetCorner: DimensionValue,
    val buttonPaddingHorizontal: DimensionValue,
    val buttonPaddingVertical: DimensionValue,
    val borderWidth: DimensionValue,
    val backdrop: DrawableValue,
    val icon: IconValue,
    val animation: AnimationValue,
    val button: ButtonStyle,
)
