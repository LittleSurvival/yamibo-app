package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncEnvelope
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteCategory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteItemCategoryRef
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavorites

class AppSyncValidatorTest {
    @Test
    fun danglingFavoriteRelationshipIsRejected() {
        val snapshot = AppSyncEnvelope(
            exportedAtEpochMillis = 1,
            sourceAppVersionCode = 4,
            favorites = AppSyncFavorites(
                categories = listOf(AppSyncFavoriteCategory(1, "A", 0, 1, 1)),
                itemCategoryRefs = listOf(AppSyncFavoriteItemCategoryRef(99, 1, 1)),
            ),
        )

        assertTrue(AppSyncValidator().validate(snapshot).any { "dangling" in it })
    }
}
