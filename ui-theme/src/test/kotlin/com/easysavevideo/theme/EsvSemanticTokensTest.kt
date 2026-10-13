package com.easysavevideo.theme

import dev.skinkit.contract.*
import dev.skinkit.defaults.DefaultThemeValidator
import org.junit.Assert.*
import org.junit.Test

class EsvSemanticTokensTest {
    private val ids = setOf("color.surface.elevated", "dimension.corner.card", "dimension.corner.sheet",
        "typography.section", "dimension.spacing.extra.small", "dimension.spacing.compact", "dimension.spacing.extra.large")

    @Test fun exactRequiredSemanticIdsAndTypesAreRegisteredInTheExistingRegistry() {
        val theme = EsvTheme()
        val selected = theme.registry.tokens.filter { it.id in ids }
        assertEquals(ids, selected.map { it.id }.toSet())
        assertTrue(selected.all { it.required })
        assertEquals(ValueKind.COLOR, theme.elevated.kind)
        assertEquals(ValueKind.TYPOGRAPHY, theme.section.kind)
        assertEquals(ValueKind.DIMENSION, theme.cardCorner.kind)
        assertEquals(ValueKind.DIMENSION, theme.sheetCorner.kind)
        assertEquals("dimension.corner.control", theme.corner.id)
        assertEquals("0.0.1", theme.provider.snapshot.contractVersion)
    }
    @Test fun defaultsHaveDistinctCardSheetControlAndCompleteSpacingAndSectionType() {
        val theme = EsvTheme()
        for (mode in ColorMode.entries) {
            theme.provider.updateConfiguration(VisualConfiguration(mode))
            val frame = theme.readSnapshot()
            assertEquals(listOf(12.0, 16.0, 24.0), listOf(frame.corner.amount, frame.cardCorner.amount, frame.sheetCorner.amount))
            assertEquals(listOf(4.0, 8.0, 12.0, 16.0, 24.0, 32.0), listOf(frame.spacingExtraSmall.amount,
                frame.spacingSmall.amount, frame.spacingCompact.amount, frame.spacingMedium.amount,
                frame.spacingLarge.amount, frame.spacingExtraLarge.amount))
            assertEquals(20.0, frame.section.fontSize, 0.0)
            assertEquals(28.0, frame.section.lineHeight, 0.0)
            assertEquals(600, frame.section.weight)
            assertEquals((if (mode == ColorMode.LIGHT) 0xffedf4f6 else 0xff1c3440).toInt(), frame.colors.elevated.toArgb())
        }
    }
    @Test fun newTokensParticipateInDefaultCompletenessAcrossModeScaleAndMotion() {
        val theme = EsvTheme()
        val configs = ColorMode.entries.flatMap { mode -> listOf(.5, 1.0, 2.0, 5.0).flatMap { scale ->
            listOf(false, true).map { VisualConfiguration(mode, scale, it) }
        } }
        val report = DefaultThemeValidator.validate(theme.appDefaults, theme.registry, configs)
        assertTrue(report.diagnostics.toString(), report.isComplete)
        assertTrue(report.diagnostics.toString(), report.diagnostics.isEmpty())
    }
    @Test fun snapshotReadsEveryNewTokenThroughOnePinAndReleasesIt() {
        val theme = EsvTheme(); val source = SemanticSource(theme)
        val frame = theme.readSnapshot(source)
        assertTrue(source.readIds.containsAll(ids))
        assertEquals(1, source.pins); assertEquals(1, source.releases)
        assertEquals(theme.provider.snapshot, frame.snapshot)
        assertEquals(16.0, frame.cardCorner.amount, 0.0)
    }
    @Test fun newTokenReadFailureStillClosesTheOnlyPin() {
        val theme = EsvTheme(); val source = SemanticSource(theme, failId = theme.elevated.id)
        try { theme.readSnapshot(source); fail("Synthetic new-token read must fail") }
        catch (_: IllegalStateException) {
            assertTrue(source.readIds.contains(theme.elevated.id))
            assertEquals(1, source.pins); assertEquals(1, source.releases)
        }
    }
    @Test fun nonDefaultValuesAreReadInsteadOfReconstructedFromFixedNumbers() {
        val theme = EsvTheme(); val source = SemanticSource(theme, overrideValues = true)
        val frame = theme.readSnapshot(source)
        assertEquals(0xff294955.toInt(), frame.colors.elevated.toArgb())
        assertEquals(listOf(13.0, 21.0, 3.0, 11.0, 29.0), listOf(frame.cardCorner.amount,
            frame.sheetCorner.amount, frame.spacingExtraSmall.amount, frame.spacingCompact.amount, frame.spacingExtraLarge.amount))
        assertEquals(23.0, frame.section.fontSize, 0.0)
        assertEquals(31.0, frame.section.lineHeight, 0.0)
        assertEquals(12.0, frame.corner.amount, 0.0)
        assertEquals(1, source.pins); assertEquals(1, source.releases)
    }
    @Test fun oldPinAndDetachedNewFieldsStayStableAcrossConfigurationChange() {
        val theme = EsvTheme(); val light = theme.readSnapshot(); val pin = theme.provider.pin()
        try {
            theme.provider.updateConfiguration(VisualConfiguration(ColorMode.DARK, 2.0, true))
            val dark = theme.readSnapshot()
            assertTrue(dark.snapshot.generation > light.snapshot.generation)
            assertEquals(light.colors.elevated, pin.getColor(theme.elevated))
            assertNotEquals(light.colors.elevated, dark.colors.elevated)
            assertEquals(light.section, dark.section)
            assertEquals(light.cardCorner, dark.cardCorner)
            assertEquals(light.sheetCorner, dark.sheetCorner)
            assertEquals(20.0, dark.section.fontSize, 0.0)
            assertEquals(40.0, dark.section.effectiveSize(dark.configuration.fontScale), 0.0)
        } finally { pin.close() }
    }
    @Test fun accessiblePrimaryIsNotResetToTheLowerContrastConceptSwatch() {
        val frame = EsvTheme().readSnapshot()
        assertEquals(0xff087582.toInt(), frame.colors.accent.toArgb())
        assertNotEquals(0xff087f8c.toInt(), frame.colors.accent.toArgb())
    }

    private class SemanticSource(private val theme: EsvTheme, private val overrideValues: Boolean = false,
        private val failId: String? = null) : PinnedThemeSource {
        var pins = 0; var releases = 0
        val readIds = mutableSetOf<String>()
        override fun observeSnapshot(observer: SnapshotObserver): Subscription = theme.provider.observeSnapshot(observer)
        override fun pin(): ThemeReadView {
            pins++
            val view = theme.provider.pin()
            fun read(token: ThemeToken) { readIds += token.id; check(token.id != failId) }
            return object : ThemeReadView by view {
                override fun getColor(token: ColorToken): ColorValue {
                    read(token)
                    return if (overrideValues && token == theme.elevated) ColorValue.argb(0xff294955.toInt()) else view.getColor(token)
                }
                override fun getTypography(token: TypographyToken): TypographyValue {
                    read(token)
                    return if (overrideValues && token == theme.section) TypographyValue(FontRef.System(), 23.0, 700, 31.0) else view.getTypography(token)
                }
                override fun getDimension(token: DimensionToken): DimensionValue {
                    read(token)
                    if (overrideValues) {
                        val amount = when (token) { theme.cardCorner -> 13.0; theme.sheetCorner -> 21.0
                            theme.spacingExtraSmall -> 3.0; theme.spacingCompact -> 11.0; theme.spacingExtraLarge -> 29.0; else -> null }
                        if (amount != null) return DimensionValue(amount, LogicalUnit.LENGTH,
                            if (token == theme.cardCorner || token == theme.sheetCorner) DimensionPurpose.CORNER else DimensionPurpose.SPACING)
                    }
                    return view.getDimension(token)
                }
                override fun release() { releases++; view.release() }
                override fun close() = release()
            }
        }
    }
}
