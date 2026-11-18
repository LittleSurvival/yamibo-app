package me.thenano.yamibo.yamibo_app.home

import kotlinx.coroutines.flow.Flow

// This recovery handles iOS's first-launch networking authorization.
internal actual fun homeNetworkAvailability(): Flow<Boolean>? = null
