package me.thenano.yamibo.yamibo_app.repository.appsync.remote

/** Absence permits a new write; failed discovery must never be mistaken for absence. */
internal class AppSyncReconciliationFailure(
    val reason: String,
    val terminal: Boolean = false,
    val authenticationRequired: Boolean = false,
) : Exception(reason)
