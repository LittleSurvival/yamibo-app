package me.thenano.yamibo.yamibo_app.home

import kotlinx.coroutines.flow.Flow

/** iOS reports path changes independently of permission-sheet lifecycle events. */
internal expect fun homeNetworkAvailability(): Flow<Boolean>?
