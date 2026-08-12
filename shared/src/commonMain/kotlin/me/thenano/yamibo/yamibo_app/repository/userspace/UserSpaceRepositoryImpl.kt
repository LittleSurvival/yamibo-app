package me.thenano.yamibo.yamibo_app.repository.userspace

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.AddFriendPopoutScreen
import io.github.littlesurvival.dto.page.PrivateMessagePage
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.page.UserSpaceFriendPage
import io.github.littlesurvival.dto.page.UserSpaceNoticePage
import io.github.littlesurvival.dto.page.UserSpacePrivateMessagePage
import io.github.littlesurvival.dto.page.UserSpaceThreadPage
import io.github.littlesurvival.dto.page.UserSpaceThreadReplyPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.PrivateMessageId
import io.github.littlesurvival.dto.value.UserId
import kotlin.time.Duration.Companion.hours
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class UserSpaceRepositoryImpl(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    diskCacheFactory: DiskCacheFactory
) : UserSpaceRepository {
    private val profileCache = diskCacheFactory.create<ProfilePage>("userspace_profile", maxSize = 20, expiration = 12.hours)
    private val threadCache = diskCacheFactory.create<UserSpaceThreadPage>("userspace_threads", maxSize = 30, expiration = 12.hours)
    private val replyCache = diskCacheFactory.create<UserSpaceThreadReplyPage>("userspace_replies", maxSize = 30, expiration = 12.hours)
    private val blogCache = diskCacheFactory.create<UserSpaceBlogPage>("userspace_blogs", maxSize = 30, expiration = 12.hours)
    private val friendCache = diskCacheFactory.create<UserSpaceFriendPage>("userspace_friends", maxSize = 20, expiration = 12.hours)
    private val messageCache = diskCacheFactory.create<UserSpacePrivateMessagePage>("userspace_messages", maxSize = 10, expiration = 2.hours)
    private val privateMessageCache = diskCacheFactory.create<PrivateMessagePage>("userspace_private_message", maxSize = 30, expiration = 2.hours)
    private val noticeCache = diskCacheFactory.create<UserSpaceNoticePage>("userspace_notices", maxSize = 10, expiration = 2.hours)

    override suspend fun fetchProfile(userId: UserId?): YamiboResult<ProfilePage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchProfileInfo(userId)
            .cacheSuccess(profileCache, profileKey(userId))
    }

    override suspend fun fetchThreads(userId: UserId?, page: Int): YamiboResult<UserSpaceThreadPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceThreads(userId, page)
            .cacheSuccess(threadCache, UserSpaceRepository.UserPageCacheKey(userId?.value, page).toCacheKey())
    }

    override suspend fun fetchReplies(userId: UserId?, page: Int): YamiboResult<UserSpaceThreadReplyPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceThreadReplies(userId, page)
            .cacheSuccess(replyCache, UserSpaceRepository.UserPageCacheKey(userId?.value, page).toCacheKey())
    }

    override suspend fun fetchMyBlogs(userId: UserId?, page: Int): YamiboResult<UserSpaceBlogPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceMyBlogs(userId = userId, page = page)
            .cacheSuccess(blogCache, UserSpaceRepository.TypedPageCacheKey("my", userId?.value, page).toCacheKey())
    }

    override suspend fun fetchFriendBlogs(page: Int): YamiboResult<UserSpaceBlogPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceFriendBlogs(page)
            .cacheSuccess(blogCache, UserSpaceRepository.TypedPageCacheKey("friend", null, page).toCacheKey())
    }

    override suspend fun fetchViewAllBlogs(type: YamiboRoute.UserSpace.Blog.ViewAllType, page: Int): YamiboResult<UserSpaceBlogPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceViewAllBlogs(type, page)
            .cacheSuccess(blogCache, UserSpaceRepository.TypedPageCacheKey("all_${type.name}", null, page).toCacheKey())
    }

    override suspend fun fetchFriends(type: YamiboRoute.UserSpace.FriendPageType, page: Int): YamiboResult<UserSpaceFriendPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceFriends(type, page)
            .cacheSuccess(friendCache, UserSpaceRepository.TypedPageCacheKey(type.name, null, page).toCacheKey())
    }

    override suspend fun fetchPrivateMessages(page: Int): YamiboResult<UserSpacePrivateMessagePage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpacePrivateMessages(page)
            .cacheSuccess(messageCache, page.toString())
    }

    override suspend fun fetchAddFriendPopoutScreen(userId: UserId): YamiboResult<AddFriendPopoutScreen> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchAddFriendPopoutScreen(userId)
    }

    override suspend fun addFriend(
        userId: UserId,
        formHash: FormHash,
        note: String,
        groupId: Int,
    ): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchAddFriend(userId, formHash, note, groupId)
    }

    override suspend fun fetchPrivateMessagePage(toUser: UserId, page: Int?): YamiboResult<PrivateMessagePage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        val result = yamiboClient.fetchPrivateMessagePage(toUser, page)
        if (result is YamiboResult.Success) {
            privateMessageCache.set(privateMessageKey(toUser, page), result.value)
            result.value.pageNav?.currentPage?.let { current ->
                privateMessageCache.set(privateMessageKey(toUser, current), result.value)
            }
        }
        return result
    }

    override suspend fun sendPrivateMessage(
        privateMessageId: PrivateMessageId,
        toUser: UserId,
        message: String,
        formHash: FormHash,
    ): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchSendPrivateMessage(privateMessageId, toUser, message, formHash)
    }

    override suspend fun fetchNotices(page: Int): YamiboResult<UserSpaceNoticePage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchUserSpaceNotices(page)
            .cacheSuccess(noticeCache, page.toString())
    }

    override fun getCachedProfile(userId: UserId?): ProfilePage? = profileCache.get(profileKey(userId))
    override fun getCachedThreads(userId: UserId?, page: Int): UserSpaceThreadPage? =
        threadCache.get(UserSpaceRepository.UserPageCacheKey(userId?.value, page).toCacheKey())
    override fun getCachedReplies(userId: UserId?, page: Int): UserSpaceThreadReplyPage? =
        replyCache.get(UserSpaceRepository.UserPageCacheKey(userId?.value, page).toCacheKey())
    override fun getCachedMyBlogs(userId: UserId?, page: Int): UserSpaceBlogPage? =
        blogCache.get(UserSpaceRepository.TypedPageCacheKey("my", userId?.value, page).toCacheKey())
    override fun getCachedFriendBlogs(page: Int): UserSpaceBlogPage? =
        blogCache.get(UserSpaceRepository.TypedPageCacheKey("friend", null, page).toCacheKey())
    override fun getCachedViewAllBlogs(type: YamiboRoute.UserSpace.Blog.ViewAllType, page: Int): UserSpaceBlogPage? =
        blogCache.get(UserSpaceRepository.TypedPageCacheKey("all_${type.name}", null, page).toCacheKey())
    override fun getCachedFriends(type: YamiboRoute.UserSpace.FriendPageType, page: Int): UserSpaceFriendPage? =
        friendCache.get(UserSpaceRepository.TypedPageCacheKey(type.name, null, page).toCacheKey())
    override fun getCachedPrivateMessages(page: Int): UserSpacePrivateMessagePage? = messageCache.get(page.toString())
    override fun getCachedPrivateMessagePage(toUser: UserId, page: Int?): PrivateMessagePage? =
        privateMessageCache.get(privateMessageKey(toUser, page))
    override fun getCachedNotices(page: Int): UserSpaceNoticePage? = noticeCache.get(page.toString())

    override fun clearUserPages(userId: UserId?) {
        val prefix = UserSpaceRepository.UserPageCacheKey.keyPrefix(userId?.value)
        threadCache.removeByPrefix(prefix)
        replyCache.removeByPrefix(prefix)
        blogCache.removeByPrefix(UserSpaceRepository.TypedPageCacheKey.keyPrefix("my", userId?.value))
        profileCache.remove(profileKey(userId))
    }

    override fun clearFriendPages(type: YamiboRoute.UserSpace.FriendPageType?) {
        if (type == null) {
            YamiboRoute.UserSpace.FriendPageType.entries.forEach { friendCache.removeByPrefix("${it.name}_") }
        } else {
            friendCache.removeByPrefix("${type.name}_")
        }
    }

    override fun clearMessagePages() {
        messageCache.clear()
    }

    override fun clearPrivateMessagePages(toUser: UserId) {
        privateMessageCache.removeByPrefix("${toUser.value}_")
    }

    override fun clearNoticePages() {
        noticeCache.clear()
    }

    private fun profileKey(userId: UserId?): String = userId?.value?.toString() ?: "self"
    private fun privateMessageKey(toUser: UserId, page: Int?): String = "${toUser.value}_${page ?: "latest"}"
}
