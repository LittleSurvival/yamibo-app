package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

object DesktopDirectories {
    fun dataRoot(
        osName: String = System.getProperty("os.name"),
        userHome: String = System.getProperty("user.home"),
        environment: Map<String, String> = System.getenv(),
    ): Path {
        val os = osName.lowercase(Locale.ROOT)
        val home = Paths.get(userHome).toAbsolutePath()
        fun absoluteEnvironmentPath(key: String): Path? = environment[key]
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?.takeIf { it.isAbsolute }
        val base = when {
            os.startsWith("windows") -> absoluteEnvironmentPath("LOCALAPPDATA") ?: home.resolve("AppData/Local")
            os.startsWith("mac") -> home.resolve("Library/Application Support")
            else -> absoluteEnvironmentPath("XDG_DATA_HOME") ?: home.resolve(".local/share")
        }
        return base.resolve("yamibo-app")
    }

    fun ensureDataRoot(): Path = Files.createDirectories(dataRoot())
}
