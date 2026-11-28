package app.kitna.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.kitna.core.GroupAppearance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the surfaces actually used by text, actionable outlines, budget badges and category icons. */
class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }

    private fun readable(label: String, foreground: Color, background: Color, minimum: Double = 4.5) {
        val actual = contrast(foreground, background)
        assertTrue("$label needs $minimum:1; got $actual", actual >= minimum)
    }

    @Test fun bothThemesProvideReadableTextAndVisibleControlOutlines() {
        listOf(false, true).forEach { dark ->
            val colors = kitnaColorScheme(dark)
            val pairs = mapOf(
                "primary button" to (colors.onPrimary to colors.primary),
                "primary panel" to (colors.onPrimaryContainer to colors.primaryContainer),
                "secondary button" to (colors.onSecondary to colors.secondary),
                "secondary panel" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "tertiary button" to (colors.onTertiary to colors.tertiary),
                "tertiary panel" to (colors.onTertiaryContainer to colors.tertiaryContainer),
                "error action" to (colors.onError to colors.error),
                "error message" to (colors.onErrorContainer to colors.errorContainer),
                "inverse message" to (colors.inverseOnSurface to colors.inverseSurface),
                "screen text" to (colors.onBackground to colors.background),
                "variant text" to (colors.onSurfaceVariant to colors.surfaceVariant)
            )
            pairs.forEach { (role, pair) -> readable("dark=$dark $role", pair.first, pair.second) }
            val surfaces = listOf(colors.background, colors.surface, colors.surfaceContainerLow, colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest)
            surfaces.forEach { surface ->
                readable("dark=$dark primary text action", colors.primary, surface)
                readable("dark=$dark main text", colors.onSurface, surface)
                readable("dark=$dark supporting text", colors.onSurfaceVariant, surface)
                readable("dark=$dark text field outline", colors.outline, surface, 3.0)
                readable("dark=$dark owed balance", balanceColor(owed = true, dark = dark), surface)
                readable("dark=$dark owing balance", balanceColor(owed = false, dark = dark), surface)
            }
        }
    }

    @Test fun budgetLabelsAndProgressRemainReadableAtEverySeverity() {
        listOf(false, true).forEach { dark ->
            val surface = kitnaColorScheme(dark).surface
            BudgetMood.entries.forEach { mood ->
                val accent = mood.ink(dark)
                readable("dark=$dark ${mood.label} amount", accent, surface)
                readable("dark=$dark ${mood.label} badge", accent, accent.copy(alpha = .12f).compositeOver(surface))
                readable("dark=$dark ${mood.label} progress", accent, accent.copy(alpha = .15f).compositeOver(surface), 3.0)
            }
        }
    }

    @Test fun categoryIconsHaveNonTextContrastOnPlainAndEmphasizedExpenseRows() {
        val categories = listOf("General", "Food", "Groceries", "Transport", "Travel", "Rent", "Accommodation", "Entertainment", "Utilities", "Shopping", "Health", "Other")
        listOf(false, true).forEach { dark ->
            val colors = kitnaColorScheme(dark)
            val rowSurfaces = listOf(colors.surface, colors.background, colors.primaryContainer.copy(alpha = .32f).compositeOver(colors.background))
            categories.forEach { category ->
                val ink = categoryAppearance(category, dark).second
                rowSurfaces.forEach { row ->
                    val iconSurface = ink.copy(alpha = if (dark) .14f else .10f).compositeOver(row)
                    readable("dark=$dark $category icon", ink, iconSurface, 3.0)
                }
            }
        }
    }

    @Test fun groupColorsKeepReadableInkUnderTheirMangaShading() {
        GroupAppearance.colors.forEach { (name, value) ->
            val fill = Color(value)
            val ink = groupInk(fill)
            val shade = if (ink == Color.White) Color.Black else Color.White
            readable("$name group text", ink, fill)
            readable("$name group text on diagonal", ink, shade.copy(alpha = .08f).compositeOver(shade.copy(alpha = .05f).compositeOver(fill)))
        }
    }

    @Test fun screenSurfacesAndPrimaryAccentsHaveACoolNeutralOrBlueCast() {
        listOf(false, true).forEach { dark ->
            val colors = kitnaColorScheme(dark)
            listOf(colors.background, colors.surface, colors.surfaceVariant, colors.primaryContainer, colors.secondaryContainer).forEach { color ->
                assertTrue("dark=$dark has a warm surface cast", color.blue >= color.red)
            }
            assertTrue("dark=$dark primary should be blue or indigo", colors.primary.blue > colors.primary.red && colors.primary.blue > colors.primary.green)
        }
    }
}
