package me.thenano.yamibo.yamibo_app.store.settings

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Non-secret preferences. Authentication must use the desktop credential store instead. */
class DesktopSettingsStore(private val file: Path) : SettingsStore {
    private val values = Properties().apply {
        if (Files.exists(file)) Files.newBufferedReader(file).use { load(it) }
    }

    @Synchronized
    private fun read(key: String): String? = values.getProperty(key)

    @Synchronized
    private fun write(key: String, value: String?) {
        val next = Properties().apply { putAll(this@DesktopSettingsStore.values) }
        if (value == null) next.remove(key) else next.setProperty(key, value)
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".settings-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { next.store(it, null) }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            values.clear()
            values.putAll(next)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun getInt(key: String, defaultValue: Int): Int = read(key)?.toIntOrNull() ?: defaultValue
    override fun putInt(key: String, value: Int) = write(key, value.toString())
    override fun getFloat(key: String, defaultValue: Float): Float = read(key)?.toFloatOrNull() ?: defaultValue
    override fun putFloat(key: String, value: Float) = write(key, value.toString())
    override fun getString(key: String, defaultValue: String): String = read(key) ?: defaultValue
    override fun putString(key: String, value: String) = write(key, value)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = read(key)?.toBooleanStrictOrNull() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) = write(key, value.toString())
    override fun remove(key: String) = write(key, null)
    override fun hasKey(key: String): Boolean = read(key) != null
}
