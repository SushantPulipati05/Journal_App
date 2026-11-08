package dev.sushnt.journal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** The colors from the design mockups. One place to change the whole look. */
object JournalColors {
    val Background = Color(0xFF0B0E17)
    val Card = Color(0xFF121827)
    val Raised = Color(0xFF151B2B)
    val Inset = Color(0xFF0E1220)
    val Border = Color(0xFF1F2637)
    val BorderStrong = Color(0xFF232B40)
    val TextStrong = Color(0xFFF2F3F8)
    val Text = Color(0xFFE8EAF2)
    val Body = Color(0xFFD6D9E3)
    val Preview = Color(0xFFAEB5C6)
    val Muted = Color(0xFF8B93A7)
    val Accent = Color(0xFFA78BFA)
    val AccentContainer = Color(0xFF262046)
    val StreakCard = Color(0xFF161230)
    val StreakBorder = Color(0xFF2C2558)
    val Success = Color(0xFF6EE7B7)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFFCA5A5)
    val DangerBorder = Color(0xFF5B2333)
}

/** Serif for the words you write and read; the interface itself stays sans. */
fun writingFont(serif: Boolean): FontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif

@Composable
fun JournalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JournalColors.Accent,
            onPrimary = Color(0xFF1A1433),
            primaryContainer = JournalColors.AccentContainer,
            onPrimaryContainer = JournalColors.Accent,
            background = JournalColors.Background,
            onBackground = JournalColors.Text,
            surface = JournalColors.Background,
            onSurface = JournalColors.Text,
            surfaceVariant = JournalColors.Card,
            onSurfaceVariant = JournalColors.Muted,
            surfaceContainerHigh = JournalColors.Raised,
            outline = JournalColors.BorderStrong,
            outlineVariant = JournalColors.Border,
            error = JournalColors.Danger,
        ),
        content = content,
    )
}
