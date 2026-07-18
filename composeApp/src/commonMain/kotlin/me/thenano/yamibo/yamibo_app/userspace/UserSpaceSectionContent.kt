package me.thenano.yamibo.yamibo_app.userspace

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.dto.model.BlogSummary
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.ProfilePage
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboEmptyContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboPageNavigation
import me.thenano.yamibo.yamibo_app.forum.components.ThreadCard
import me.thenano.yamibo.yamibo_app.message.MessageCenterTab
import me.thenano.yamibo.yamibo_app.userspace.blog.components.BlogCard
import me.thenano.yamibo.yamibo_app.userspace.components.FriendCard
import me.thenano.yamibo.yamibo_app.userspace.components.ReplyGroupCard

@Composable
internal fun UserSpaceSectionContent(
    content: UserSpaceScreenContent,
    isSelf: Boolean,
    selectedTab: UserSpaceSubPage,
    currentPage: Int,
    onNavigateSection: (UserSpaceSection, UserSpaceSubPage) -> Unit,
    onOpenMessageCenter: (MessageCenterTab) -> Unit,
    onAddFriend: (ProfilePage) -> Unit,
    onPageChange: (Int) -> Unit,
    onThreadClick: (ThreadSummary) -> Unit,
    onUserClick: (User) -> Unit,
    onOpenPrivateMessage: (User) -> Unit,
    onBlogClick: (BlogSummary) -> Unit,
    onReplyQuoteClick: () -> Unit,
    onMessageAction: () -> Unit,
    onOpenWebView: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        when (content) {
            is UserSpaceScreenContent.Profile -> {
                item {
                    UserSpaceProfileHeader(
                        profile = content.page,
                        isSelf = isSelf,
                        onNavigateSection = onNavigateSection,
                        onOpenMessageCenter = { onOpenMessageCenter(MessageCenterTab.PrivateMessages) },
                        onAddFriend = onAddFriend,
                        onOpenWebView = onOpenWebView,
                    )
                }
            }
            is UserSpaceScreenContent.Threads -> {
                if (content.page.threads.isEmpty()) item { UserSpaceEmptyListMessage(emptyMessage(selectedTab, isSelf)) }
                items(content.page.threads, key = { it.tid.value }) { thread ->
                    ThreadCard(thread = thread, onClick = { onThreadClick(thread) }, onAuthorClick = onUserClick)
                }
                content.page.pageNav?.let { nav -> item { UserSpacePageNavigation(nav, currentPage, onPageChange) } }
            }
            is UserSpaceScreenContent.Replies -> {
                if (content.page.replies.isEmpty()) item { UserSpaceEmptyListMessage(emptyMessage(selectedTab, isSelf)) }
                items(content.page.replies, key = { it.tId.value }) { reply ->
                    ReplyGroupCard(reply, onThreadClick = { onThreadClick(it) }, onQuoteClick = onReplyQuoteClick)
                }
                content.page.pageNav?.let { nav -> item { UserSpacePageNavigation(nav, currentPage, onPageChange) } }
            }
            is UserSpaceScreenContent.Blogs -> {
                if (content.page.blogs.isEmpty()) item { UserSpaceEmptyListMessage(emptyMessage(selectedTab, isSelf)) }
                items(content.page.blogs, key = { it.bId.value }) { blog ->
                    BlogCard(
                        blog = blog,
                        onClick = { onBlogClick(blog) },
                        onUserClick = onUserClick,
                        onActionClick = { action -> onOpenWebView(action.name, action.url) },
                    )
                }
                content.page.pageNav?.let { nav -> item { UserSpacePageNavigation(nav, currentPage, onPageChange) } }
            }
            is UserSpaceScreenContent.Friends -> {
                if (content.page.users.isEmpty()) item { UserSpaceEmptyListMessage(emptyMessage(selectedTab, isSelf)) }
                items(content.page.users, key = { it.user.uid.value }) { item ->
                    FriendCard(
                        item = item,
                        onUserClick = { onUserClick(item.user) },
                        onMessageClick = item.pmUrl?.let { { onOpenPrivateMessage(item.user) } },
                        onDeleteClick = onMessageAction,
                    )
                }
                content.page.pageNav?.let { nav -> item { UserSpacePageNavigation(nav, currentPage, onPageChange) } }
            }
        }
    }
}

@Composable
private fun UserSpaceEmptyListMessage(message: String) {
    YamiboEmptyContent(message = message, modifier = Modifier.padding(horizontal = 24.dp, vertical = 80.dp))
}

private fun emptyMessage(tab: UserSpaceSubPage, isSelf: Boolean): String = when (tab) {
    UserSpaceSubPage.Profile -> ""
    UserSpaceSubPage.Threads -> if (isSelf) i18n("沒有找到主題") else i18n("沒有找到Ta的主題")
    UserSpaceSubPage.Replies -> if (isSelf) i18n("沒有找到回復") else i18n("沒有找到Ta的回復")
    UserSpaceSubPage.MyBlogs -> if (isSelf) i18n("沒有找到日志") else i18n("沒有找到Ta的日志")
    UserSpaceSubPage.FriendBlogs -> i18n("沒有找到好友的日志")
    UserSpaceSubPage.ViewAllBlogs -> i18n("沒有找到日志")
    UserSpaceSubPage.Friends -> i18n("沒有找到好友")
    UserSpaceSubPage.Online -> i18n("沒有找到在線成員")
    UserSpaceSubPage.Visitors -> i18n("沒有找到訪客")
    UserSpaceSubPage.Traces -> i18n("沒有找到足跡")
}

@Composable
private fun UserSpacePageNavigation(pageNav: PageNav, currentPage: Int, onPageChange: (Int) -> Unit) {
    YamiboPageNavigation(pageNav = pageNav, currentPage = currentPage, onPageChange = onPageChange)
}
