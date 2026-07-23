package me.thenano.yamibo.yamibo_app.repository.settings.core

import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.math.roundToInt

class IntSetting(
    name: String,
    description: String,
    default: Int,
    key: String,
    store: SettingsStore,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
    val interval: Int = 1
) : SettingItem<Int>(name, description, default, key, store) {
    
    override fun loadFromStore(): Int = store.getInt(storageKey, default)
    
    override fun saveToStore(newValue: Int) {
        store.putInt(storageKey, newValue)
    }

    override fun validate(value: Int): Boolean = value in min..max
    
    override fun setValue(newValue: Int): Boolean {
        if (!validate(newValue)) return false
        val steppedValue = if (interval > 1) {
            newValue - (newValue % interval) // snap to interval, a simple implementation
        } else newValue
        
        saveToStore(steppedValue)
        flow.value = steppedValue
        return true
    }
}

class FloatSetting(
    name: String,
    description: String,
    default: Float,
    key: String,
    store: SettingsStore,
    val min: Float = -Float.MAX_VALUE,
    val max: Float = Float.MAX_VALUE,
    val interval: Float = 0f
) : SettingItem<Float>(name, description, default, key, store) {

    override fun loadFromStore(): Float = store.getFloat(storageKey, default)
    
    override fun saveToStore(newValue: Float) {
        store.putFloat(storageKey, newValue)
    }

    override fun validate(value: Float): Boolean = value in min..max
    
    override fun setValue(newValue: Float): Boolean {
        if (!validate(newValue)) return false
        val steppedValue = if (interval > 0f) {
            // snap to the nearest interval value
            (newValue / interval).roundToInt() * interval
        } else newValue
        
        saveToStore(steppedValue)
        flow.value = steppedValue
        return true
    }
}

class BoolSetting(
    name: String,
    description: String,
    default: Boolean,
    key: String,
    store: SettingsStore
) : SettingItem<Boolean>(name, description, default, key, store) {

    override fun loadFromStore(): Boolean = store.getBoolean(storageKey, default)
    
    override fun saveToStore(newValue: Boolean) {
        store.putBoolean(storageKey, newValue)
    }

    @Suppress("unused")
    fun toggle() {
        setValue(!flow.value)
    }
}

class StringSetting(
    name: String,
    description: String,
    default: String,
    key: String,
    store: SettingsStore,
    val allowedValues: List<String> = emptyList() // If empty, any string is allowed
) : SettingItem<String>(name, description, default, key, store) {

    override fun loadFromStore(): String = store.getString(storageKey, default)
    
    override fun saveToStore(newValue: String) {
        store.putString(storageKey, newValue)
    }

    override fun validate(value: String): Boolean {
        return allowedValues.isEmpty() || allowedValues.contains(value)
    }
}

/** 
 * Note: Since Enum uses reflection (valueOf) depending on KType or just a string backend, 
 * we will persist it as String to easily leverage existing serialization mechanisms.
 */
class EnumSetting<T : Enum<T>>(
    name: String,
    description: String,
    default: T,
    key: String,
    store: SettingsStore,
    private val values: Array<T>
) : SettingItem<T>(name, description, default, key, store) {

    override fun loadFromStore(): T {
        val stringValue = store.getString(storageKey, default.name)
        return values.firstOrNull { it.name == stringValue } ?: default
    }
    
    override fun saveToStore(newValue: T) {
        store.putString(storageKey, newValue.name)
    }

    fun acceptsName(name: String): Boolean = values.any { it.name == name }

    fun setValueFromName(name: String): Boolean {
        val value = values.firstOrNull { it.name == name } ?: return false
        return setValue(value)
    }
}
