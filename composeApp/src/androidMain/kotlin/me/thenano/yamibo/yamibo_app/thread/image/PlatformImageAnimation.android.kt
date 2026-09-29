package me.thenano.yamibo.yamibo_app.thread.image

import coil3.ComponentRegistry
import coil3.compose.AsyncImagePainter

// Android's coil-gif component is registered by Coil's service loader.
internal actual fun ComponentRegistry.Builder.addPlatformImageDecoders() = Unit
internal actual fun platformImageAnimation(state: AsyncImagePainter.State) = state
