package com.easysavevideo.theme

import dev.skinkit.contract.AnimationToken
import dev.skinkit.contract.AnimationValue
import dev.skinkit.contract.BuiltInIcon
import dev.skinkit.contract.ColorMode
import dev.skinkit.contract.ColorToken
import dev.skinkit.contract.ColorValue
import dev.skinkit.contract.ComponentStyle
import dev.skinkit.contract.ComponentToken
import dev.skinkit.contract.DimensionToken
import dev.skinkit.contract.DimensionValue
import dev.skinkit.contract.DrawableToken
import dev.skinkit.contract.DrawableValue
import dev.skinkit.contract.HostContractException
import dev.skinkit.contract.IconToken
import dev.skinkit.contract.IconValue
import dev.skinkit.contract.PinnedThemeSource
import dev.skinkit.contract.SnapshotObserver
import dev.skinkit.contract.StateDomain
import dev.skinkit.contract.Subscription
import dev.skinkit.contract.ThemeReadView
import dev.skinkit.contract.TypographyToken
import dev.skinkit.contract.TypographyValue
import dev.skinkit.contract.ValueKind
import dev.skinkit.contract.VisualConfiguration
import dev.skinkit.contract.VisualState
import dev.skinkit.defaults.DefaultThemeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.pow

class EsvThemeTest {
    @Test
    fun allSevenCategoriesHaveRequiredAppOwnedTokens() {
        val theme = EsvTheme()
        assertEquals(EsvTheme.APP_ID, theme.registry.appId)
        assertEquals(ValueKind.entries.toSet(), theme.registry.tokens.map { it.kind }.toSet())
        assertTrue(theme.registry.tokens.all { it.required })
        assertEquals("0.0.1", theme.provider.snapshot.contractVersion)
        assertEquals("default:${EsvTheme.APP_ID}", theme.provider.snapshot.themeId)
        assertEquals(EsvTheme.THEME_VERSION, theme.provider.snapshot.themeVersion)
    }

    @Test
    fun defaultsAreCompleteWithoutSafeFallbackAcrossConfigurationMatrix() {
        val theme = EsvTheme()
        val configurations = ColorMode.entries.flatMap { mode ->
            listOf(0.5, 1.0, 2.0, 5.0).flatMap { scale ->
                listOf(false, true).map { motion -> VisualConfiguration(mode, scale, motion) }
            }
        }
        val report = DefaultThemeValidator.validate(theme.appDefaults, theme.registry, configurations)
        assertTrue(report.diagnostics.toString(), report.isComplete)
        assertTrue("No missing, unsafe or unavailable app values: ${report.diagnostics}", report.diagnostics.isEmpty())
        for (configuration in configurations) {
            theme.provider.updateConfiguration(configuration)
            val snapshot = theme.readSnapshot()
            assertEquals(configuration, snapshot.configuration)
            assertTrue(snapshot.backdrop is DrawableValue.Solid)
            assertEquals(BuiltInIcon.ADD, snapshot.icon.source)
            assertEquals(AnimationValue.Disabled, snapshot.animation)
            assertTrue(theme.provider.diagnostics.isEmpty())
        }
    }

    @Test
    fun frameReadsAllSevenCategoriesThroughExactlyOnePinAndReleasesIt() {
        val theme = EsvTheme()
        val tracked = TrackingSource(theme.provider)
        val snapshot = theme.readSnapshot(tracked)
        assertEquals(ValueKind.entries.toSet(), tracked.readKinds)
        assertEquals(1, tracked.pins)
        assertEquals(1, tracked.releases)
        assertEquals(theme.provider.snapshot, snapshot.snapshot)
        // Detached values remain usable after the read lease is closed.
        assertEquals(16.0, snapshot.body.fontSize, 0.0)
        assertTrue(snapshot.button.forState(VisualState.NORMAL).typography.fontSize > 0)
    }

    @Test
    fun readFailureStillReleasesThePin() {
        val first = EsvTheme()
        val second = EsvTheme()
        val tracked = TrackingSource(second.provider)
        try {
            first.readSnapshot(tracked)
            fail("A provider from a different token registry must reject the read")
        } catch (_: HostContractException) {
            assertEquals(1, tracked.pins)
            assertEquals(1, tracked.releases)
        }
    }

    @Test
    fun oldPinnedGenerationAndDetachedFrameStayStableAcrossLightDarkChanges() {
        val theme = EsvTheme()
        val light = theme.readSnapshot()
        val oldView = theme.provider.pin()
        try {
            theme.provider.updateConfiguration(VisualConfiguration(ColorMode.DARK, 2.0, true))
            val dark = theme.readSnapshot()
            assertTrue(dark.snapshot.generation > light.snapshot.generation)
            assertNotEquals(light.colors.bg, dark.colors.bg)
            assertNotEquals(light.colors.text, dark.colors.text)
            assertEquals(light.snapshot, oldView.snapshot)
            assertEquals(light.configuration, oldView.configuration)
            assertEquals(light.colors.bg, oldView.getColor(theme.background))
            assertEquals(ColorMode.LIGHT, light.configuration.colorMode)
            assertEquals(ColorMode.DARK, dark.configuration.colorMode)
        } finally {
            oldView.release()
        }
        oldView.close() // Contract release is idempotent.
        try {
            oldView.getColor(theme.background)
            fail("A released render view must not be reused")
        } catch (_: IllegalStateException) {
            // Expected; the detached frame did not retain this view.
        }
    }

    @Test
    fun observerReplaysPublishesOnlyChangesAndStopsAfterClose() {
        val theme = EsvTheme()
        val generations = mutableListOf<Long>()
        val subscription = theme.provider.observeSnapshot { generations += it.generation }
        assertEquals(listOf(0L), generations)
        theme.provider.updateConfiguration(VisualConfiguration())
        assertEquals(listOf(0L), generations)
        theme.provider.updateConfiguration(VisualConfiguration(ColorMode.DARK))
        assertEquals(listOf(0L, 1L), generations)
        theme.provider.updateConfiguration(VisualConfiguration(ColorMode.DARK, 2.0))
        assertEquals(listOf(0L, 1L, 2L), generations)
        subscription.close()
        subscription.close()
        theme.provider.updateConfiguration(VisualConfiguration())
        assertEquals(listOf(0L, 1L, 2L), generations)
    }

    @Test
    fun scaledTextRemainsLogicalAndSpacingDoesNotScale() {
        val theme = EsvTheme()
        val normal = theme.readSnapshot()
        theme.provider.updateConfiguration(VisualConfiguration(fontScale = 2.0))
        val large = theme.readSnapshot()
        // The Compose adapter must use raw sp; system font scaling is applied only there.
        assertEquals(normal.body, large.body)
        assertEquals(32.0, large.body.effectiveSize(large.configuration.fontScale), 0.0)
        assertEquals(48.0, large.body.effectiveLineHeight(large.configuration.fontScale), 0.0)
        assertEquals(normal.spacingMedium, large.spacingMedium)
        assertEquals(normal.corner, large.corner)
        assertEquals(normal.icon.size, large.icon.size)
    }

    @Test
    fun textAndEveryButtonStateMeetNormalTextContrast() {
        val theme = EsvTheme()
        for (mode in ColorMode.entries) {
            theme.provider.updateConfiguration(VisualConfiguration(mode))
            val frame = theme.readSnapshot()
            for (foreground in listOf(frame.colors.text, frame.colors.muted, frame.colors.accent, frame.colors.error)) {
                for (background in listOf(frame.colors.bg, frame.colors.surface)) {
                    val ratio = contrast(foreground, background)
                    assertTrue("$mode foreground/background contrast: $ratio", ratio >= 4.5)
                }
            }
            for (state in StateDomain.BUTTON.requiredStates) {
                val visual = frame.button.forState(state)
                val ratio = contrast(visual.foreground, visual.background)
                assertTrue("$mode $state button contrast: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun buttonStatesAreDistinctAndDisabledTakesPrecedence() {
        val frame = EsvTheme().readSnapshot()
        val normal = frame.button.forState(VisualState.NORMAL)
        assertNotEquals(normal.background, frame.button.forState(VisualState.PRESSED).background)
        assertNotEquals(normal.background, frame.button.forState(VisualState.FOCUSED).background)
        assertNotEquals(normal.background, frame.button.forState(VisualState.DISABLED).background)
        assertEquals(
            VisualState.DISABLED,
            StateDomain.BUTTON.resolve(setOf(VisualState.DISABLED, VisualState.PRESSED, VisualState.FOCUSED)),
        )
        assertEquals(
            VisualState.PRESSED,
            StateDomain.BUTTON.resolve(setOf(VisualState.PRESSED, VisualState.FOCUSED)),
        )
        assertEquals(VisualState.FOCUSED, StateDomain.BUTTON.resolve(setOf(VisualState.FOCUSED)))
        assertEquals(VisualState.NORMAL, StateDomain.BUTTON.resolve(emptySet()))
        assertFalse(frame.button.fields.fields.isEmpty())
    }

    private class TrackingSource(private val delegate: PinnedThemeSource) : PinnedThemeSource {
        var pins = 0
        var releases = 0
        val readKinds = mutableSetOf<ValueKind>()

        override fun observeSnapshot(observer: SnapshotObserver): Subscription = delegate.observeSnapshot(observer)

        override fun pin(): ThemeReadView {
            pins++
            val view = delegate.pin()
            return object : ThemeReadView by view {
                override fun getColor(token: ColorToken): ColorValue {
                    readKinds += ValueKind.COLOR
                    return view.getColor(token)
                }
                override fun getTypography(token: TypographyToken): TypographyValue {
                    readKinds += ValueKind.TYPOGRAPHY
                    return view.getTypography(token)
                }
                override fun getDimension(token: DimensionToken): DimensionValue {
                    readKinds += ValueKind.DIMENSION
                    return view.getDimension(token)
                }
                override fun getDrawable(token: DrawableToken): DrawableValue {
                    readKinds += ValueKind.DRAWABLE
                    return view.getDrawable(token)
                }
                override fun getIcon(token: IconToken): IconValue {
                    readKinds += ValueKind.ICON
                    return view.getIcon(token)
                }
                override fun getAnimation(token: AnimationToken): AnimationValue {
                    readKinds += ValueKind.ANIMATION
                    return view.getAnimation(token)
                }
                override fun <S : ComponentStyle> getComponentStyle(token: ComponentToken<S>): S {
                    readKinds += ValueKind.COMPONENT
                    return view.getComponentStyle(token)
                }
                override fun release() {
                    releases++
                    view.release()
                }
                override fun close() = release()
            }
        }
    }

    private fun contrast(foreground: ColorValue, background: ColorValue): Double {
        fun linear(value: Double): Double = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        fun luminance(value: ColorValue): Double =
            0.2126 * linear(value.rgba.red) + 0.7152 * linear(value.rgba.green) + 0.0722 * linear(value.rgba.blue)
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
