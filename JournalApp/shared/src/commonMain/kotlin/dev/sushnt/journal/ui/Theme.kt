package dev.sushnt.journal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import dev.sushnt.journal.data.ThemeMode

/** Every colour the app uses. There are two sets: [DarkPalette] and [PaperPalette]. */
class Palette(
    val isDark: Boolean,
    /** The page behind everything. */
    val Background: Color,
    /** Entry cards. */
    val Card: Color,
    /** Menus, tab bar, snackbars. */
    val Raised: Color,
    /** Search boxes, fields. */
    val Inset: Color,
    /** Bottom sheets and side sheets. */
    val Sheet: Color,
    /** Card edges. */
    val Border: Color,
    /** Stronger edges, outlines. */
    val BorderStrong: Color,
    /** Thin lines between things. */
    val Divider: Color,
    /** Slightly stronger lines. */
    val DividerStrong: Color,
    /** Edges of hovered cards and avatars. */
    val Hover: Color,
    /** Titles. */
    val TextStrong: Color,
    /** Normal text. */
    val Text: Color,
    /** What you write. */
    val Body: Color,
    /** Previews on cards. */
    val Preview: Color,
    /** Dates, hints, secondary text. */
    val Muted: Color,
    /** Empty-field placeholders. */
    val Placeholder: Color,
    /** Small helper text. */
    val Hint: Color,
    /** Things that are not there yet (future days). */
    val Faint: Color,
    /** Icons. */
    val Icon: Color,
    /** Inactive tabs and icons. */
    val IconDim: Color,
    /** The app's purple. */
    val Accent: Color,
    /** Text on a purple background. */
    val OnAccent: Color,
    /** Purple tint: selected tab, highlights. */
    val AccentContainer: Color,
    /** The entry open on the right, in the list. */
    val SelectedCard: Color,
    /** Streak card. */
    val StreakCard: Color,
    /** Streak card edge. */
    val StreakBorder: Color,
    /** Sign-in screen glow, top. */
    val GlowStart: Color,
    /** Sign-in screen glow, bottom. */
    val GlowEnd: Color,
    /** Loading placeholders. */
    val Skeleton: Color,
    /** The shimmer moving across them. */
    val SkeletonShine: Color,
    /** Track of a switch that's off. */
    val SwitchOff: Color,
    /** Darkens the page behind a sheet. */
    val Scrim: Color,
    /** Saved. */
    val Success: Color,
    /** Not synced yet. */
    val Warning: Color,
    /** Delete. */
    val Danger: Color,
    /** Edge of delete buttons. */
    val DangerBorder: Color,
    /** Behind a card being swiped away. */
    val DangerContainer: Color,
)

/** The original dark look. */
val DarkPalette = Palette(
    isDark = true,
    Background = Color(0xFF0B0E17),
    Card = Color(0xFF121827),
    Raised = Color(0xFF151B2B),
    Inset = Color(0xFF0E1220),
    Sheet = Color(0xFF10152A),
    Border = Color(0xFF1F2637),
    BorderStrong = Color(0xFF232B40),
    Divider = Color(0xFF1A2132),
    DividerStrong = Color(0xFF262E45),
    Hover = Color(0xFF343D57),
    TextStrong = Color(0xFFF2F3F8),
    Text = Color(0xFFE8EAF2),
    Body = Color(0xFFD6D9E3),
    Preview = Color(0xFFAEB5C6),
    Muted = Color(0xFF8B93A7),
    Placeholder = Color(0xFF7C859B),
    Hint = Color(0xFF6B7389),
    Faint = Color(0xFF3A4258),
    Icon = Color(0xFFC9CEDB),
    IconDim = Color(0xFF9AA3B8),
    Accent = Color(0xFFA78BFA),
    OnAccent = Color(0xFF1A1433),
    AccentContainer = Color(0xFF262046),
    SelectedCard = Color(0xFF171B33),
    StreakCard = Color(0xFF161230),
    StreakBorder = Color(0xFF2C2558),
    GlowStart = Color(0xFF231B45),
    GlowEnd = Color(0xFF0F1222),
    Skeleton = Color(0xFF1A2132),
    SkeletonShine = Color(0xFF262E45),
    SwitchOff = Color(0xFF2A3247),
    Scrim = Color(0x9E05070C),
    Success = Color(0xFF6EE7B7),
    Warning = Color(0xFFFBBF24),
    Danger = Color(0xFFFCA5A5),
    DangerBorder = Color(0xFF5B2333),
    DangerContainer = Color(0xFF3A1622),
)

/**
 * "Paper": the light look. Warm book paper instead of white, sepia-black ink, and the
 * purple made deeper so it reads well on paper. Every text colour has at least 4.5:1
 * contrast on the page.
 */
val PaperPalette = Palette(
    isDark = false,
    Background = Color(0xFFF4EDE0),
    Card = Color(0xFFFBF6EC),
    Raised = Color(0xFFFFFBF4),
    Inset = Color(0xFFEFE6D5),
    Sheet = Color(0xFFFBF6EC),
    Border = Color(0xFFE2D6C2),
    BorderStrong = Color(0xFFD3C3A8),
    Divider = Color(0xFFE6DCCB),
    DividerStrong = Color(0xFFDCCFB9),
    Hover = Color(0xFFCDBFA6),
    TextStrong = Color(0xFF2A2017),
    Text = Color(0xFF352A20),
    Body = Color(0xFF3F3329),
    Preview = Color(0xFF6B5D4C),
    Muted = Color(0xFF735F4C),
    Placeholder = Color(0xFF8C7B67),
    Hint = Color(0xFF7A6A57),
    Faint = Color(0xFFC9BBA3),
    Icon = Color(0xFF5E5040),
    IconDim = Color(0xFF7A6A57),
    Accent = Color(0xFF6A4BC4),
    OnAccent = Color(0xFFFFFFFF),
    AccentContainer = Color(0xFFE9E0F4),
    SelectedCard = Color(0xFFEFE7F7),
    StreakCard = Color(0xFFEFE4F2),
    StreakBorder = Color(0xFFD9C7E6),
    GlowStart = Color(0xFFE9E0F4),
    GlowEnd = Color(0xFFF4EDE0),
    Skeleton = Color(0xFFEAE1D0),
    SkeletonShine = Color(0xFFF7F1E6),
    SwitchOff = Color(0xFFDCCFB9),
    Scrim = Color(0x592A2017),
    Success = Color(0xFF2F7656),
    Warning = Color(0xFF865710),
    Danger = Color(0xFFA8402C),
    DangerBorder = Color(0xFFE8C4B8),
    DangerContainer = Color(0xFFF3D9D0),
)

/**
 * The colours of the current theme. Screens use these (JournalColors.Accent...), and
 * everything redraws when the theme changes because [palette] is Compose state.
 */
object JournalColors {
    internal var palette by mutableStateOf(DarkPalette)

    val isDark: Boolean get() = palette.isDark
    val Background: Color get() = palette.Background
    val Card: Color get() = palette.Card
    val Raised: Color get() = palette.Raised
    val Inset: Color get() = palette.Inset
    val Sheet: Color get() = palette.Sheet
    val Border: Color get() = palette.Border
    val BorderStrong: Color get() = palette.BorderStrong
    val Divider: Color get() = palette.Divider
    val DividerStrong: Color get() = palette.DividerStrong
    val Hover: Color get() = palette.Hover
    val TextStrong: Color get() = palette.TextStrong
    val Text: Color get() = palette.Text
    val Body: Color get() = palette.Body
    val Preview: Color get() = palette.Preview
    val Muted: Color get() = palette.Muted
    val Placeholder: Color get() = palette.Placeholder
    val Hint: Color get() = palette.Hint
    val Faint: Color get() = palette.Faint
    val Icon: Color get() = palette.Icon
    val IconDim: Color get() = palette.IconDim
    val Accent: Color get() = palette.Accent
    val OnAccent: Color get() = palette.OnAccent
    val AccentContainer: Color get() = palette.AccentContainer
    val SelectedCard: Color get() = palette.SelectedCard
    val StreakCard: Color get() = palette.StreakCard
    val StreakBorder: Color get() = palette.StreakBorder
    val GlowStart: Color get() = palette.GlowStart
    val GlowEnd: Color get() = palette.GlowEnd
    val Skeleton: Color get() = palette.Skeleton
    val SkeletonShine: Color get() = palette.SkeletonShine
    val SwitchOff: Color get() = palette.SwitchOff
    val Scrim: Color get() = palette.Scrim
    val Success: Color get() = palette.Success
    val Warning: Color get() = palette.Warning
    val Danger: Color get() = palette.Danger
    val DangerBorder: Color get() = palette.DangerBorder
    val DangerContainer: Color get() = palette.DangerContainer
}

/** Text and icons drawn on top of a photo (on a dark badge), the same in both themes. */
val OnPhoto = Color(0xFFF2F3F8)

/** Serif for the words you write and read; the interface itself stays sans. */
fun writingFont(serif: Boolean): FontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif

/** Phone: makes the status and navigation bar icons dark on paper, light on dark. */
@Composable
expect fun ApplySystemBars(darkTheme: Boolean)

@Composable
fun JournalTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Paper -> false
        ThemeMode.Dark -> true
    }
    val palette = if (dark) DarkPalette else PaperPalette
    // Set before anything below reads a colour, so the whole tree draws with it.
    if (JournalColors.palette !== palette) JournalColors.palette = palette
    ApplySystemBars(dark)

    val p = palette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.Accent, onPrimary = p.OnAccent, primaryContainer = p.AccentContainer, onPrimaryContainer = p.Accent,
            background = p.Background, onBackground = p.Text, surface = p.Background, onSurface = p.Text,
            surfaceVariant = p.Card, onSurfaceVariant = p.Muted, surfaceContainerHigh = p.Raised,
            outline = p.BorderStrong, outlineVariant = p.Border, error = p.Danger,
        )
    } else {
        lightColorScheme(
            primary = p.Accent, onPrimary = p.OnAccent, primaryContainer = p.AccentContainer, onPrimaryContainer = p.Accent,
            background = p.Background, onBackground = p.Text, surface = p.Background, onSurface = p.Text,
            surfaceVariant = p.Card, onSurfaceVariant = p.Muted, surfaceContainerHigh = p.Raised,
            surfaceContainer = p.Raised, surfaceContainerLow = p.Card, surfaceContainerHighest = p.Raised,
            outline = p.BorderStrong, outlineVariant = p.Border, error = p.Danger,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
