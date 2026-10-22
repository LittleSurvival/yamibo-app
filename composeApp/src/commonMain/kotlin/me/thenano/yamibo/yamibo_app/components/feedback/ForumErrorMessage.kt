package me.thenano.yamibo.yamibo_app.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import me.thenano.yamibo.yamibo_app.LocalForumErrorMessageResolver

/** Resolve persisted job errors without rewriting their stored diagnostic text. */
@Composable
fun forumErrorMessage(original: String): String {
    val resolver = LocalForumErrorMessageResolver.current
    val message by produceState(original, original, resolver) {
        value = resolver.resolve(original)
    }
    return message
}
