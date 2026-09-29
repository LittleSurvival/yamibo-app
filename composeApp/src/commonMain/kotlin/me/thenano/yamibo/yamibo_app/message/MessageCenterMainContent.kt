package me.thenano.yamibo.yamibo_app.message

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.controls.AppLazyColumn as LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboEmptyContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboPageNavigation

@Composable
internal fun MessageCenterMainContent(
    content: MessageCenterContent,
    selectedTab: MessageCenterTab,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    onUserClick: (User) -> Unit,
    onNoticeUserClick: (UserId) -> Unit,
    onOpenPrivateMessage: (User) -> Unit,
    onMessageAction: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        when (content) {
            is MessageCenterContent.PrivateMessages -> {
                if (content.page.messages.isEmpty()) item { MessageCenterEmptyListMessage(emptyMessage(selectedTab)) }
                items(content.page.messages, key = { "${it.user.uid.value}_${it.timeInfo.text}" }) { message ->
                    PrivateMessageCard(
                        message,
                        onUserClick = { onUserClick(message.user) },
                        onAction = { onOpenPrivateMessage(message.user) },
                    )
                }
                content.page.pageNav?.let { nav -> item { MessageCenterPageNavigation(nav, currentPage, onPageChange) } }
            }
            is MessageCenterContent.Notices -> {
                if (content.page.notices.isEmpty()) item { MessageCenterEmptyListMessage(emptyMessage(selectedTab)) }
                items(content.page.notices, key = { it.noticeId.value }) { notice ->
                    NoticeCard(
                        notice,
                        onUserClick = onNoticeUserClick,
                        onAction = onMessageAction,
                    )
                }
                content.page.pageNav?.let { nav -> item { MessageCenterPageNavigation(nav, currentPage, onPageChange) } }
            }
        }
    }
}

@Composable
private fun MessageCenterEmptyListMessage(message: String) {
    YamiboEmptyContent(message = message, modifier = Modifier.padding(horizontal = 24.dp, vertical = 80.dp))
}

private fun emptyMessage(tab: MessageCenterTab): String = when (tab) {
    MessageCenterTab.PrivateMessages -> i18n("沒有找到消息")
    MessageCenterTab.Notices -> i18n("沒有找到提醒")
}

@Composable
private fun MessageCenterPageNavigation(pageNav: PageNav, currentPage: Int, onPageChange: (Int) -> Unit) {
    YamiboPageNavigation(pageNav = pageNav, currentPage = currentPage, onPageChange = onPageChange)
}
