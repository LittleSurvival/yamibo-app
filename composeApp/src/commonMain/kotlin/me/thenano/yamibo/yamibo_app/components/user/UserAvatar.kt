package me.thenano.yamibo.yamibo_app.components.user

import YamiboIcons
import me.thenano.yamibo.yamibo_app.thread.image.platformImageAnimation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest

/**
 * Shared circular avatar renderer for Yamibo users.
 *
 * Use this anywhere a user avatar is displayed: forum thread cards, reader
 * author blocks, user-space pages, private messages, notices, blog comments,
 * and profile shortcuts. It provides the same circular crop, fallback person
 * icon, background tint, and Yamibo image-request headers across the app.
 *
 * @param url Remote avatar URL. When null or blank, a fallback person icon is
 * shown.
 * @param size Diameter in dp.
 * @param modifier Optional modifier applied before sizing and clipping.
 * @param contentDescription Accessibility description for the loaded image.
 */
@Composable
fun UserAvatar(
    url: String?,
    size: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = YamiboTheme.colors
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colors.brownPrimary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YamiboIcons.PersonFill,
            contentDescription = null,
            modifier = Modifier.size((size * 0.7f).dp),
            tint = colors.textDark.copy(alpha = 0.45f),
        )
        if (!url.isNullOrBlank()) {
            SubcomposeAsyncImage(transform = ::platformImageAnimation,
                model = rememberImageRequest(url),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
