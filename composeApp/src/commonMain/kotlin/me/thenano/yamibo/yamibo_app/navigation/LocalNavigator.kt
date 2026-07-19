package me.thenano.yamibo.yamibo_app.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.IMainScreen

@Serializable
enum class NavAction {
    Push,
    Pop,
    Replace
}

@Serializable
data class NavigatorSnapshot(
    val stack: List<RestorableScreenSnapshot>,
    val lastAction: NavAction = NavAction.Push,
)

class ComposableNavigator private constructor(
    private val startScreen: RestorableNavigatable,
    private val logger: NavigationRestoreLogger,
    initialLastAction: NavAction,
) {
    val stack = mutableStateListOf<Navigatable>()
    private val snapshotStack = mutableListOf<RestorableScreenSnapshot?>()
    var lastAction by mutableStateOf(initialLastAction)
    lateinit var stateHolder: SaveableStateHolder
    val backHandlers = mutableListOf<() -> Boolean>()

    /** Index of the screen currently animating out (-1 = none) */
    val poppingIndex = mutableIntStateOf(-1)

    constructor(
        start: RestorableNavigatable = IMainScreen(),
        logger: NavigationRestoreLogger = LoggerNavigationRestoreLogger,
    ) : this(
        startScreen = start,
        logger = logger,
        initialLastAction = NavAction.Push,
    ) {
        pushInitialScreen(start)
    }

    val currentScreen: Navigatable
        get() = stack.last()

    fun navigate(navigatable: Navigatable) {
        if (stack.isNotEmpty() && stack.last().id == navigatable.id) return

        removeExistingScreen(navigatable.id)
        lastAction = NavAction.Push
        stack.add(navigatable)
        snapshotStack.add(navigatable.toRestoreSnapshotOrNull())
    }

    fun replace(navigatable: Navigatable) {
        val replaceIndex = stack.lastIndex
        val existingIndex = stack.indexOfFirst { it.id == navigatable.id }
        if (existingIndex >= 0 && existingIndex != replaceIndex) {
            removeScreenAt(existingIndex)
        }

        lastAction = NavAction.Replace
        stack[stack.lastIndex] = navigatable
        snapshotStack[snapshotStack.lastIndex] = navigatable.toRestoreSnapshotOrNull()
    }

    fun dispatchBack(): Boolean {
        for (handler in backHandlers.reversed()) {
            if (handler()) return true
        }
        return pop()
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        if (poppingIndex.intValue >= 0) return false
        lastAction = NavAction.Pop
        poppingIndex.intValue = stack.lastIndex
        return true
    }

    fun completePop() {
        val idx = poppingIndex.intValue
        if (idx in stack.indices) {
            stack.removeAt(idx)
            snapshotStack.removeAt(idx)
        }
        poppingIndex.intValue = -1
    }

    fun popToRoot() {
        if (stack.size > 1) lastAction = NavAction.Pop
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            snapshotStack.removeAt(snapshotStack.lastIndex)
        }
    }

    @Deprecated("There's no longer a need for this.", ReplaceWith("stack.size > 1"))
    fun canGoBack(): Boolean = stack.size > 1

    fun snapshot(): NavigatorSnapshot {
        logger.onSnapshotStart(stack.size)
        val restorableStack = buildList {
            for (index in snapshotStack.indices) {
                val snapshot = snapshotStack[index]
                if (snapshot == null) {
                    logger.onSnapshotItemSkipped(
                        screenId = stack[index].id,
                        reason = "transient_screen",
                    )
                    break
                }
                add(snapshot)
            }
        }.ifEmpty { listOf(startScreen.toRestoreSnapshot()) }
        logger.onSnapshotBuilt(restorableStack.size)
        return NavigatorSnapshot(
            stack = restorableStack,
            lastAction = lastAction,
        )
    }

    private fun pushInitialScreen(screen: RestorableNavigatable) {
        stack.add(screen)
        snapshotStack.add(screen.toRestoreSnapshot())
    }

    private fun pushRestoredScreen(screen: RestorableNavigatable) {
        stack.add(screen)
        snapshotStack.add(screen.toRestoreSnapshot())
    }

    private fun removeExistingScreen(screenId: String) {
        val existingIndex = stack.indexOfFirst { it.id == screenId }
        if (existingIndex < 0) return

        removeScreenAt(existingIndex)
    }

    private fun removeScreenAt(index: Int) {
        stack.removeAt(index)
        snapshotStack.removeAt(index)

        val currentPoppingIndex = poppingIndex.intValue
        poppingIndex.intValue = when {
            currentPoppingIndex == index -> -1
            currentPoppingIndex > index -> currentPoppingIndex - 1
            else -> currentPoppingIndex
        }
    }

    companion object {
        fun fromSnapshot(
            snapshot: NavigatorSnapshot,
            start: RestorableNavigatable = IMainScreen(),
            logger: NavigationRestoreLogger = LoggerNavigationRestoreLogger,
        ): ComposableNavigator {
            logger.onRestoreStart(snapshot.stack.size)
            val navigator = ComposableNavigator(
                startScreen = start,
                logger = logger,
                initialLastAction = snapshot.lastAction,
            )
            navigator.stack.clear()
            navigator.snapshotStack.clear()

            val restoredScreens = snapshot.stack.mapNotNull { entry ->
                RestorableScreenRegistry.decode(entry, logger)
            }
                .dedupeByScreenId()
                .ifEmpty { listOf(start) }

            restoredScreens.forEach(navigator::pushRestoredScreen)
            logger.onRestoreFinished(restoredScreens.size)
            return navigator
        }

        fun saver(
            start: RestorableNavigatable = IMainScreen(),
            logger: NavigationRestoreLogger = LoggerNavigationRestoreLogger,
        ): Saver<ComposableNavigator, String> = Saver(
            save = { navigator ->
                val snapshot = navigator.snapshot()
                navigationRestoreJson.encodeToString(snapshot).also { encoded ->
                    logger.onSnapshotSaved(
                        restorableStackSize = snapshot.stack.size,
                        bytes = encoded.encodeToByteArray().size,
                    )
                }
            },
            restore = { encoded ->
                fromSnapshot(
                    snapshot = navigationRestoreJson.decodeFromString<NavigatorSnapshot>(encoded),
                    start = start,
                    logger = logger,
                )
            },
        )
    }
}

private fun List<RestorableNavigatable>.dedupeByScreenId(): List<RestorableNavigatable> {
    val result = mutableListOf<RestorableNavigatable>()
    for (screen in this) {
        result.removeAll { it.id == screen.id }
        result += screen
    }
    return result
}

@Composable
fun rememberRestorableNavigator(
    start: RestorableNavigatable = IMainScreen(),
    logger: NavigationRestoreLogger = LoggerNavigationRestoreLogger,
): ComposableNavigator {
    return rememberSaveable(saver = ComposableNavigator.saver(start, logger)) {
        ComposableNavigator(start, logger)
    }
}

val LocalNavigator =
    compositionLocalOf<ComposableNavigator> { error("LocalNavigator not provided") }
