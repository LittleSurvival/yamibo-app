package me.thenano.yamibo.yamibo_app.userspace

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.thread.image.platformImageAnimation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import io.github.littlesurvival.dto.page.ProfilePage
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlRenderer
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest

@Composable
internal fun UserSpaceProfileHeader(
    profile: ProfilePage,
    isSelf: Boolean,
    onNavigateSection: (UserSpaceSection, UserSpaceSubPage) -> Unit,
    onOpenMessageCenter: () -> Unit,
    onAddFriend: (ProfilePage) -> Unit,
    onOpenWebView: (String, String) -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(174.dp)
                .background(colors.brownPrimary.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            val backgroundUrl = profile.avatarBackgroundUrl
            if (!backgroundUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(transform = ::platformImageAnimation,
                    model = rememberImageRequest(backgroundUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                UserAvatar(profile.avatarUrl, size = 64)
                Spacer(Modifier.height(10.dp))
                Text(profile.username, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }

        StatPanel(profile)
        ActionGrid(isSelf, profile, onNavigateSection, onOpenMessageCenter, onAddFriend)
        if (!profile.signatureHtml.isNullOrBlank()) {
            ProfileSignatureCard(profile.signatureHtml)
        }
        ProfileInfoTable(profile, onOpenHomepage = { url -> onOpenWebView(i18n("個人主頁"), url) })
    }
}

@Composable
private fun StatPanel(profile: ProfilePage) {
    val colors = YamiboTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = (-24).dp),
        shape = RoundedCornerShape(5.dp),
        color = colors.creamSurface,
        border = BorderStroke(0.5.dp, colors.brownLight.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            ProfileStat(i18n("總積分"), profile.totalPoints.toString(), Modifier.weight(1f))
            ProfileStat(i18n("積分"), i18n("{} 點", profile.points.toString()), Modifier.weight(1f))
            ProfileStat(i18n("對象"), profile.partner.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileStat(label: String, value: String, modifier: Modifier) {
    val colors = YamiboTheme.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.textDark, fontSize = 18.sp)
        Text(label, color = colors.brownPrimary.copy(alpha = 0.55f), fontSize = 11.sp)
    }
}

private data class SpaceAction(
    val group: UserSpaceSection?,
    val initialTab: UserSpaceSubPage,
    val label: String,
    val opensMessageCenter: Boolean = false,
    val addsFriend: Boolean = false,
)

@Composable
private fun ActionGrid(
    isSelf: Boolean,
    profile: ProfilePage,
    onNavigateSection: (UserSpaceSection, UserSpaceSubPage) -> Unit,
    onOpenMessageCenter: () -> Unit,
    onAddFriend: (ProfilePage) -> Unit,
) {
    val actions = if (isSelf) {
        listOf(
            SpaceAction(UserSpaceSection.Threads, UserSpaceSubPage.Threads, i18n("我的主題")),
            SpaceAction(UserSpaceSection.Blogs, UserSpaceSubPage.FriendBlogs, i18n("我的日志")),
            SpaceAction(UserSpaceSection.Friends, UserSpaceSubPage.Friends, i18n("我的好友")),
            SpaceAction(null, UserSpaceSubPage.Profile, i18n("消息提醒"), opensMessageCenter = true),
        )
    } else {
        listOf(
            SpaceAction(UserSpaceSection.Threads, UserSpaceSubPage.Threads, i18n("Ta的主題")),
            SpaceAction(UserSpaceSection.Blogs, UserSpaceSubPage.MyBlogs, i18n("Ta的日志")),
            SpaceAction(UserSpaceSection.Threads, UserSpaceSubPage.Replies, i18n("Ta的回覆")),
            SpaceAction(null, UserSpaceSubPage.Profile, i18n("加為好友"), addsFriend = true),
        )
    }
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = (-12).dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        border = BorderStroke(0.5.dp, colors.brownLight.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { action ->
                        Surface(
                            onClick = {
                                when {
                                    action.opensMessageCenter -> onOpenMessageCenter()
                                    action.addsFriend -> onAddFriend(profile)
                                    action.group != null -> onNavigateSection(action.group, action.initialTab)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            color = colors.creamBackground,
                        ) {
                            Text(
                                text = action.label,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                color = colors.textOnBackground,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfileSignatureCard(signatureHtml: String?) {
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        border = BorderStroke(0.5.dp, colors.brownLight.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(i18n("個人簽名"), color = colors.textStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            HtmlRenderer(html = signatureHtml.orEmpty(), modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class ProfileInfoRow(
    val label: String,
    val value: String,
    val url: String? = null,
)

@Composable
private fun ProfileInfoTable(profile: ProfilePage, onOpenHomepage: (String) -> Unit) {
    val rows = listOfNotNull(
        ProfileInfoRow("UID", profile.uid.value.toString()),
        ProfileInfoRow(i18n("用戶組"), profile.userGroup),
        profile.adminGroup?.let { ProfileInfoRow(i18n("管理組"), it) },
        ProfileInfoRow(i18n("性別"), profile.gender ?: "-"),
        ProfileInfoRow(i18n("生日"), profile.birthday ?: "-"),
        profile.birthplace?.let { ProfileInfoRow(i18n("出生地"), it) },
        profile.education?.let { ProfileInfoRow(i18n("學歷"), it) },
        
        profile.customTitle?.let { ProfileInfoRow(i18n("自定義頭銜"), it) },
        profile.homepage?.let { ProfileInfoRow(i18n("個人主頁"), it, it) },
        ProfileInfoRow(i18n("在線時間"), i18n("{} 小時", profile.onlineHours.toString())),
        ProfileInfoRow(i18n("注冊時間"), profile.registerTime?.text ?: "-"),
        ProfileInfoRow(i18n("最後訪問"), profile.lastVisit?.text ?: "-"),
    )
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        border = BorderStroke(0.5.dp, colors.brownLight.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(i18n("個人資料"), color = colors.textStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            rows.forEach { row ->
                val url = row.url
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, modifier = Modifier.weight(1f), color = colors.textDark, fontSize = 14.sp)
                    Text(
                        text = row.value,
                        modifier = if (url == null) Modifier else Modifier.clickable { onOpenHomepage(url) },
                        color = if (url == null) colors.brownPrimary.copy(alpha = 0.75f) else colors.orangeAccent,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = colors.brownLight.copy(alpha = 0.35f))
            }
        }
    }
}
