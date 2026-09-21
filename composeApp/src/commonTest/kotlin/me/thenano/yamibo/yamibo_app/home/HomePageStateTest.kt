package me.thenano.yamibo.yamibo_app.home

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class HomePageStateTest {
    @Test
    fun failedLoginRefreshWithoutContentShowsRetryableError() {
        assertIs<HomeState.Error>(HomeState.Loading.afterRefresh(YamiboResult.Failure("HOME_INVALID_CONTENT")))
        assertIs<HomeState.Error>(HomeState.Error("offline").afterRefresh(YamiboResult.NotLoggedIn))
    }

    @Test
    fun failedRefreshPreservesExistingContentAndRetryCanRecover() {
        val content = HomeState.Success(HomePage(emptyList(), emptyList()))
        assertSame(content, content.afterRefresh(YamiboResult.Failure("offline")))
        assertIs<HomeState.Success>(HomeState.Error("offline").afterRefresh(YamiboResult.Success(content.page)))
    }
}
