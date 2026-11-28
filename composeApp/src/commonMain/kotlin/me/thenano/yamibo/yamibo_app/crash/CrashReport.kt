package me.thenano.yamibo.yamibo_app.crash

/** Reserved for the user-initiated upload action in the crash report dialog. */
fun interface CrashReportUploader {
    suspend fun upload(report: String)
}
