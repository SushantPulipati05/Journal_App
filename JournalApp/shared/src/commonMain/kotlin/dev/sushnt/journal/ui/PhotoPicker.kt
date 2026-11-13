package dev.sushnt.journal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The system's photo chooser. Returns a function that opens it; [onPicked] receives
 * the raw bytes of every image chosen (nothing if cancelled).
 * Desktop: a file dialog. Android: the system photo picker (no permission needed).
 */
@Composable
expect fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): () -> Unit

/**
 * Opens the camera to take one photo. Returns null on devices without a camera app
 * (desktop), so callers fall back to [rememberPhotoPicker]. [onTaken] gets the JPEG bytes.
 */
@Composable
expect fun rememberCameraCapture(onTaken: (ByteArray) -> Unit): (() -> Unit)?

/** "Take a photo" or "Choose from gallery", slid up from the bottom (phones). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSourceSheet(onCamera: () -> Unit, onGallery: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = JournalColors.Raised,
        contentColor = JournalColors.Text,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Add a photo",
                color = JournalColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            SourceRow(Glyph.Camera, "Take a photo", "Opens the camera", onCamera)
            SourceRow(Glyph.Image, "Choose from gallery", "Pick one or more photos", onGallery)
        }
    }
}

@Composable
private fun SourceRow(glyph: Glyph, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(glyph, JournalColors.Accent, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = JournalColors.TextStrong, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = JournalColors.Muted, fontSize = 13.sp)
        }
    }
}
