package dev.sushnt.journal.ui

import androidx.compose.runtime.Composable

/** Android's back gesture/button. Desktop and iOS have no system back, so there it does nothing. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
