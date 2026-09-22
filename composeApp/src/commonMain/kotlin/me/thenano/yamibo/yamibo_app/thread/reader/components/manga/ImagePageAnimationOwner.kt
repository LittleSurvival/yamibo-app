package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The destination's old screen position becomes its base offset during a committed takeover. */
internal fun rebaseImagePageDragOffset(offset: Float, destinationSide: Int, axisSize: Int): Float =
    offset + destinationSide * axisSize

/** UI-thread owner: cancellation must not let old finally blocks clear a newer drag. */
internal class ImagePageAnimationOwner {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun launch(scope: CoroutineScope, onFinished: () -> Unit, animation: suspend () -> Unit) {
        cancel()
        val token = generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                animation()
            } finally {
                if (token == generation) {
                    job = null
                    onFinished()
                }
            }
        }.also { it.start() }
    }
}
