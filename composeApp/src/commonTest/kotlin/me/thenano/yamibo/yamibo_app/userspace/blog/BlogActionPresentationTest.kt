package me.thenano.yamibo.yamibo_app.userspace.blog

import io.github.littlesurvival.dto.page.BlogInfo
import io.github.littlesurvival.dto.page.ManageButton
import io.github.littlesurvival.dto.value.BlogId
import kotlin.test.Test
import kotlin.test.assertEquals

class BlogActionPresentationTest {
    @Test
    fun parsedActionsTakePriorityOverLegacyBlogInfoUrls() {
        val parsed = listOf(
            ManageButton("收藏", "favorite"),
            ManageButton("編輯", "edit"),
            ManageButton("刪除", "delete"),
        )

        assertEquals(parsed, resolveRootBlogActions(parsed, blogInfo()))
    }

    @Test
    fun legacyBlogInfoUrlsRemainAvailableForCachedPages() {
        assertEquals(
            listOf("收藏", "分享", "邀請"),
            resolveRootBlogActions(emptyList(), blogInfo()).map { it.name },
        )
    }

    private fun blogInfo() = BlogInfo(
        blogId = BlogId(1),
        title = "Blog",
        collectUrl = "favorite",
        shareUrl = "share",
        inviteUrl = "invite",
    )
}
