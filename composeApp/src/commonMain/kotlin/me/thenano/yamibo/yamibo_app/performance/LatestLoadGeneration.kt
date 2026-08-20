package me.thenano.yamibo.yamibo_app.performance

internal class LatestLoadGeneration {
    private var current: Long = 0L

    fun begin(): Long = ++current

    fun isCurrent(generation: Long): Boolean = generation == current
}
