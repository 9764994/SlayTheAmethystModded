package io.stamethyst.ui.aimod

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/** Thumbnail of an image attachment; text attachments render nothing. */
@Composable
internal fun AiAttachmentThumbnail(
    attachment: AiAttachment,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    if (!attachment.isImage) return
    // Decode off the main thread: a full screenshot is several megabytes even after downscaling.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.data) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.getDecoder().decode(attachment.data)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = attachment.name,
        modifier = modifier.size(size).clip(RoundedCornerShape(6.dp)),
        contentScale = ContentScale.Crop,
    )
}
