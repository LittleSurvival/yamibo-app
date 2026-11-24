package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.repository.thread.readerImages
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import no.synth.kmpzip.crypto.Crypto
import no.synth.kmpzip.okio.ZipOutputStream
import no.synth.kmpzip.okio.asSource
import no.synth.kmpzip.zip.ZipConstants
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipFile
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.SYSTEM
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlin.uuid.Uuid

internal val DownloadTaskKey.pageKey: ThreadPageDownloadKey get() = when (this) {
    is ThreadPageDownloadKey -> this
    is TagMangaChapterDownloadKey -> ThreadPageDownloadKey(tid, 1, authorId)
    is RssMangaChapterDownloadKey -> ThreadPageDownloadKey(tid, 1, authorId)
}

internal val DownloadManifest.savedAt: Long get() = when (this) {
    is ThreadPageDownloadManifest -> downloadedAt
    is TagMangaChapterManifest -> downloadedAt
    is RssMangaChapterManifest -> downloadedAt
}

internal fun DownloadManifest.sharedCopy(images: List<DownloadedImage>, owner: Int, generation: String): DownloadManifest = when (this) {
    is ThreadPageDownloadManifest -> copy(images = images, owner = owner, archiveId = generation, shared = true)
    is TagMangaChapterManifest -> copy(images = images, owner = owner, archiveId = generation, shared = true)
    is RssMangaChapterManifest -> copy(images = images, owner = owner, archiveId = generation, shared = true)
}

@Serializable
private data class Floor(val pid: Int, val floor: Int, val pinned: Boolean)

@Serializable
private data class Claim(val item: DownloadManifest, val header: ThreadPage?, val floors: List<Floor>)

@Serializable
private data class ThreadFile(
    val version: Int = 1,
    val claims: List<Claim> = emptyList(),
    val hashes: Map<Int, String> = emptyMap(),
    val generation: String? = null,
    val batches: Map<Int, String> = emptyMap(),
    val edits: Map<Int, Long> = emptyMap(),
    val observations: Map<Int, Long> = emptyMap(),
)

private fun ByteArray.digest() = toByteString().sha256().hex()

/** Portable thread manifests are authoritative. SQL contains only rebuildable lookups. */
internal class OfflineLibrary(
    val legacy: DownloadStorageProvider,
    private val database: Database,
    private val cache: Path,
    private val json: Json,
    private val account: () -> Int,
) : DownloadStorageProvider by legacy {
    private val files get() = legacy.offlineFiles
    private val index get() = database.readerDownloadQueries
    private val blobLock = Mutex()
    private val writer = Mutex()
    private val digestPattern = Regex("[0-9a-f]{64}")
    private fun dir(owner: Int, tid: Int) = "$owner/threads/$tid"
    private fun blob(owner: Int, digest: String): String {
        require(digestPattern.matches(digest))
        return "$owner/images/${digest.take(2)}/$digest"
    }
    private fun state(storage: String, owner: Int, tid: Int): ThreadFile {
        val bytes = files.open(storage, dir(owner, tid) + "/manifest.json")?.buffer()?.use { it.readUtf8() } ?: return ThreadFile()
        return json.decodeFromString<ThreadFile>(bytes).also { state ->
            check(state.version == 1 && state.claims.all { it.item.owner == owner && it.item.key.pageKey.tid == tid })
        }
    }
    private fun all(storage: String, owner: Int) = files.names(storage, "$owner/threads").mapNotNull { it.toIntOrNull() }
        .flatMap { state(storage, owner, it).claims }
    suspend fun inventory(): List<DownloadManifest> {
        val storage = getStorageKey() ?: return emptyList()
        return writer.withLock { all(storage, account()).map { it.item } }
    }

    private fun indexUses(storage: String, owner: Int, tid: Int, digests: Set<String>) {
        index.clearThreadUses(storage, owner.toLong(), tid.toLong())
        digests.forEach { index.putThreadUse(storage, owner.toLong(), tid.toLong(), it) }
    }

    private fun ThreadFile.digests() = claims.flatMapTo(mutableSetOf()) { it.item.images.mapNotNull(DownloadedImage::digest) }

    private fun retireImages(storage: String, owner: Int, candidates: Set<String>) {
        if (candidates.isEmpty() || !index.inventoryReady(storage, owner.toLong()).executeAsOne() ||
            index.dirtyLibraryThreads(storage, owner.toLong()).executeAsList().isNotEmpty()) return
        for (digest in candidates) {
            if (!index.hasImageUse(storage, owner.toLong(), digest).executeAsOne()) {
                files.remove(storage, blob(owner, digest))
                index.removeDigestAliases(storage, owner.toLong(), digest)
            }
        }
    }

    private fun imageFile(storage: String, owner: Int, digest: String) = files.open(storage, blob(owner, digest))
    fun openImage(storage: String, owner: Int, digest: String): Source {
        check(owner == account())
        return checkNotNull(imageFile(storage, owner, digest)) { "下載的圖片檔案遺失" }
    }
    suspend fun storeImage(storage: String, owner: Int, url: String, bytes: ByteArray, repair: Boolean = false, verifyExisting: Boolean = false): DownloadedImage {
        val digest = bytes.digest()
        blobLock.withLock {
            check(owner == account() && storage == getStorageKey())
            val path = blob(owner, digest)
            val exists = files.open(storage, path)?.use { input ->
                if (verifyExisting) runCatching { input.verifyDigest(digest) }.isSuccess else true
            } ?: false
            if (!exists || repair) files.install(storage, path, digest) { Buffer().write(bytes) }
        }
        return DownloadedImage(url, url.encodeUtf8().sha256().hex(), bytes.size.toLong(), digest = digest)
    }

    fun knownImage(storage: String, owner: Int, url: String): DownloadedImage? {
        val hit = index.imageAlias(storage, owner.toLong(), url).executeAsOneOrNull() ?: return null
        if (imageFile(storage, owner, hit.digest)?.use { true } != true) return null
        return DownloadedImage(url, url.encodeUtf8().sha256().hex(), hit.bytes, digest = hit.digest)
    }

    private fun claim(state: ThreadFile, key: DownloadTaskKey) = state.claims.firstOrNull { it.item.key == key }

    private fun ThreadFile.locations() = batches.ifEmpty { hashes.mapValues { checkNotNull(generation) } }

    private fun bodyPath(owner: Int, tid: Int, generation: String): String {
        require(generation.matches(Regex("[0-9a-f-]{36}")))
        return dir(owner, tid) + "/posts-$generation.zip"
    }

    private inline fun <T> withPosts(storage: String, owner: Int, tid: Int, generation: String?, block: (ZipFile?) -> T): T {
        if (generation == null) return block(null)
        val src = checkNotNull(files.seek(storage, bodyPath(owner, tid, generation)))
        val zip = try { ZipFile(src) } catch (e: Throwable) { src.close(); throw e }
        return try { block(zip) } finally { zip.close() }
    }
    private fun ZipFile.bytes(pid: Int) = getInputStream("$pid.json").asSource().buffer().use { it.readByteArray() }

    private fun page(storage: String, owner: Int, state: ThreadFile, claim: Claim): ThreadPage? {
        val header = claim.header ?: return null
        val locations = state.locations()
        val posts = mutableMapOf<Int, Post>()
        for ((batch, floors) in claim.floors.groupBy { locations.getValue(it.pid) }) {
            withPosts(storage, owner, claim.item.key.pageKey.tid, batch) { zip ->
                for (floor in floors.distinctBy { it.pid }) {
                    posts[floor.pid] = json.decodeFromString<Post>(checkNotNull(zip).bytes(floor.pid).decodeToString())
                }
            }
        }
        return header.copy(posts = claim.floors.map { posts.getValue(it.pid).copy(floor = it.floor, isPinned = it.pinned) })
    }

    private suspend fun current(key: DownloadTaskKey): DownloadManifest? {
        val storage = getStorageKey() ?: return null
        return writer.withLock { claim(state(storage, account(), key.pageKey.tid), key)?.item }
    }

    override suspend fun readManifest(key: ThreadPageDownloadKey) =
        current(key) as? ThreadPageDownloadManifest ?: legacy.readManifest(key)
    override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey) =
        current(key) as? TagMangaChapterManifest ?: legacy.readTagMangaManifest(key)
    override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey) =
        current(key) as? RssMangaChapterManifest ?: legacy.readRssMangaManifest(key)
    override suspend fun readThreadPage(key: DownloadTaskKey): ByteArray? {
        val storage = getStorageKey() ?: return null
        return writer.withLock {
            val owner = account()
            val state = state(storage, owner, key.pageKey.tid)
            val saved = claim(state, key) ?: return@withLock legacy.readThreadPage(key)
            page(storage, owner, state, saved)?.let { json.encodeToString(it).encodeToByteArray() }
        }
    }

    suspend fun covered(key: ThreadPageDownloadKey): Pair<ThreadPage, DownloadManifest>? {
        val storage = getStorageKey() ?: return null
        return writer.withLock {
            val owner = account()
            val state = state(storage, owner, key.tid)
            val saved = state.claims.filter { it.header != null && it.item.key.pageKey == key }
                .maxWithOrNull(compareBy<Claim> { it.item.missingImageCount == 0 }.thenBy { it.item.savedAt }) ?: return@withLock null
            checkNotNull(page(storage, owner, state, saved)) to saved.item
        }
    }
    suspend fun pageForPost(storage: String, owner: Int, tid: Int, author: Int?, pid: Int): Int? = writer.withLock {
        state(storage, owner, tid).claims.firstOrNull { claim ->
            claim.item.key.pageKey.authorId == author && claim.floors.any { it.pid == pid }
        }?.item?.key?.pageKey?.page
    }

    override suspend fun readPageRecord(key: ThreadPageDownloadKey): Pair<ByteArray, ThreadPageDownloadManifest?>? {
        val saved = current(key) as? ThreadPageDownloadManifest ?: return legacy.readPageRecord(key)
        return readThreadPage(key)?.let { it to saved }
    }

    private suspend inline fun <reified T : DownloadManifest> list(old: List<T>): List<T> {
        val storage = getStorageKey() ?: return old
        return writer.withLock {
            (all(storage, account()).mapNotNull { it.item as? T } + old).distinctBy { it.owner to it.key }
        }
    }
    override suspend fun listManifests() = list(legacy.listManifests())
    override suspend fun listTagMangaManifests() = list(legacy.listTagMangaManifests())
    override suspend fun listRssMangaManifests() = list(legacy.listRssMangaManifests())

    // Pages added to a long thread get a new batch. Editing/removing a PID rewrites only its old batch.
    private fun stagePosts(storage: String, owner: Int, tid: Int, old: ThreadFile, next: ThreadFile,
                           changed: Map<Int, ByteArray>): ThreadFile {
        if (old.hashes == next.hashes) return next.copy(generation = old.generation, batches = old.batches)
        val locations = old.locations()
        val affected = (changed.keys + (old.hashes.keys - next.hashes.keys)).mapNotNullTo(mutableSetOf(), locations::get)
        val batches = mutableMapOf<Int, String>()
        for ((previous, pids) in next.hashes.keys.groupBy(locations::get)) {
            val batch = if (previous != null && previous !in affected) previous else {
                val id = Uuid.random().toString()
                writeBatch(storage, owner, tid, previous, id, pids.associateWith(next.hashes::getValue), changed)
                id
            }
            pids.forEach { batches[it] = batch }
        }
        return next.copy(batches = batches, generation = batches.values.firstOrNull())
    }

    private fun retireBatches(storage: String, owner: Int, tid: Int, old: ThreadFile, next: ThreadFile) {
        for (batch in old.locations().values.toSet() - next.locations().values.toSet()) {
            files.remove(storage, bodyPath(owner, tid, batch))
        }
    }

    private fun writeBatch(storage: String, owner: Int, tid: Int, previousId: String?, id: String,
                           hashes: Map<Int, String>, changed: Map<Int, ByteArray>) {
        val fs = FileSystem.SYSTEM
        fs.createDirectories(cache)
        val tmp = cache / "posts-$id.tmp"
        try {
            withPosts(storage, owner, tid, previousId) { previous ->
                val zip = ZipOutputStream(fs.sink(tmp).buffer())
                try {
                    for ((pid, hash) in hashes) {
                        val data = changed[pid] ?: checkNotNull(previous).bytes(pid)
                        check(data.digest() == hash)
                        zip.putNextEntry(ZipEntry("$pid.json").apply {
                            method = ZipConstants.STORED
                            size = data.size.toLong()
                            crc = Crypto.crc32(data)
                        })
                        zip.write(data)
                        zip.closeEntry()
                    }
                } finally { zip.close() }
            }
            val hash = HashingSink.sha256(blackholeSink())
            hash.buffer().use { sink -> fs.source(tmp).use(sink::writeAll) }
            files.install(storage, bodyPath(owner, tid, id), hash.hash.hex()) { fs.source(tmp) }
        } finally { fs.delete(tmp) }
    }

    private fun writeState(storage: String, owner: Int, tid: Int, next: ThreadFile) {
        check(account() == owner)
        val data = json.encodeToString(next).encodeToByteArray()
        val path = dir(owner, tid) + "/manifest.json"
        files.install(storage, path, data.digest()) { Buffer().write(data) }
    }

    private fun indexClaim(storage: String, owner: Int, claim: Claim) {
        val manifest = claim.item
        val key = manifest.key
        val page = key.pageKey
        val encoded = json.encodeToString<DownloadTaskKey>(key)
        val generation = checkNotNull(manifest.archiveId)
        val indexId = "$owner/${key.stableId}"
        index.clearFloors(storage, indexId)
        index.clearPage(storage, indexId)
        index.clearImages(storage, indexId)
        if (claim.header != null) {
            index.putPage(storage, indexId, owner.toLong(), page.tid.toLong(), page.authorId?.toLong() ?: 0L,
                page.page.toLong(), encoded, generation)
            claim.floors.forEach { index.putFloor(storage, indexId, it.pid.toLong()) }
        }
        for (image in manifest.images) {
            index.putAlias(storage, owner.toLong(), image.sourceUrl, checkNotNull(image.digest), image.bytes)
        }
    }

    /** Call after the image payloads have been installed and validated. */
    suspend fun save(storage: String, item: DownloadManifest, source: ThreadPage?, migrating: Boolean = false, observedAt: Long = currentTimeMillis()) {
        val owner = checkNotNull(item.owner)
        val key = item.key
        val tid = key.pageKey.tid
        check(item.shared && item.images.all { it.digest?.matches(digestPattern) == true })
        writer.withLock {
            check(owner == account() && getStorageKey() == storage)
            val old = state(storage, owner, tid)
            val incoming = source?.posts.orEmpty()
            require(source == null || source.thread.tid.value == tid)
            val changed = mutableMapOf<Int, ByteArray>()
            val hashes = old.hashes.toMutableMap()
            val edits = old.edits.toMutableMap()
            val observations = old.observations.toMutableMap()
            for (post in incoming) {
                val bytes = json.encodeToString(post.copy(floor = 0, isPinned = false)).encodeToByteArray()
                val hash = bytes.digest()
                val id = post.pid.value
                val before = hashes.put(id, hash)
                val lastEdit = post.lastEditedTime?.epoch ?: 0L
                if (before != null && before != hash) {
                    check(lastEdit >= (edits[id] ?: 0L) && (lastEdit > (edits[id] ?: 0L) ||
                        observedAt >= (observations[id] ?: 0L))) { "較新的樓層內容已儲存" }
                }
                edits[id] = maxOf(lastEdit, edits[id] ?: 0L)
                observations[id] = maxOf(observedAt, observations[id] ?: 0L)
                if (hash != before) {
                    check(!migrating || before == null) { "同一樓層的舊下載版本不同，已保留原檔" }
                    changed[post.pid.value] = bytes
                }
            }
            val incomingClaim = Claim(item, source?.copy(posts = emptyList()), incoming.map { Floor(it.pid.value, it.floor, it.isPinned) })
            val available = (old.claims.flatMap { it.item.images } + item.images).associateBy { it.sourceUrl }
            val byPid = incoming.associateBy { it.pid.value }
            val claims = old.claims.filterNot { it.item.key == key }.map { other ->
                if (source == null || other.header == null) return@map other
                val samePage = other.item.key.pageKey == key.pageKey
                if (!samePage && other.floors.none { it.pid in changed }) return@map other
                val snapshot = if (samePage) source else {
                    val previous = checkNotNull(page(storage, owner, old, other))
                    previous.copy(posts = previous.posts.map { p -> byPid[p.pid.value]?.copy(floor = p.floor, isPinned = p.isPinned) ?: p })
                }
                val order = when (val otherKey = other.item.key) {
                    is ThreadPageDownloadKey -> snapshot.threadImageUrls().toList()
                    else -> snapshot.readerImages(otherKey.pageKey.authorId?.let(::UserId)).map { it.url }
                }
                val urls = order.distinct().filter { it.startsWith("https://") || it.startsWith("http://") }
                val assets = urls.mapNotNull(available::get)
                val missing = urls.size - assets.size
                check(missing == 0 || other.item.missingImageCount > 0) { "更新缺少圖片，已保留完整下載" }
                val updated = when (val m = other.item) {
                    is ThreadPageDownloadManifest -> m.copy(title = snapshot.thread.title, images = assets, missingImageCount = missing)
                    is TagMangaChapterManifest -> m.copy(title = snapshot.thread.title, images = assets, missingImageCount = missing, imageCount = order.size, sourceOrder = order)
                    is RssMangaChapterManifest -> m.copy(title = snapshot.thread.title, images = assets, missingImageCount = missing, imageCount = order.size, sourceOrder = order)
                }
                Claim(updated, snapshot.copy(posts = emptyList()), snapshot.posts.map { Floor(it.pid.value, it.floor, it.isPinned) })
            } + incomingClaim
            val live = claims.flatMapTo(mutableSetOf()) { it.floors.map(Floor::pid) }
            hashes.keys.retainAll(live)
            val draft = ThreadFile(claims = claims, hashes = hashes, edits = edits.filterKeys(live::contains),
                observations = observations.filterKeys(live::contains))
            index.markLibraryDirty(storage, owner.toLong(), tid.toLong())
            val next = stagePosts(storage, owner, tid, old, draft, changed)
            writeState(storage, owner, tid, next)
            index.transaction {
                claims.filter { it != claim(old, it.item.key) }.forEach { indexClaim(storage, owner, it) }
                indexUses(storage, owner, tid, next.digests())
                index.clearLibraryDirty(storage, owner.toLong(), tid.toLong())
            }
            retireImages(storage, owner, old.digests() - next.digests())
            retireBatches(storage, owner, tid, old, next)
        }
    }

    override suspend fun updateThreadPage(key: ThreadPageDownloadKey, manifestBytes: ByteArray, threadPageBytes: ByteArray) {
        val item = json.decodeFromString<ThreadPageDownloadManifest>(manifestBytes.decodeToString())
        if (!item.shared) return legacy.updateThreadPage(key, manifestBytes, threadPageBytes)
        save(checkNotNull(getStorageKey()), item, json.decodeFromString<ThreadPage>(threadPageBytes.decodeToString()))
    }

    override suspend fun groupKeys(storageKey: String, group: DownloadTaskKey): Set<DownloadTaskKey> = writer.withLock {
        legacy.groupKeys(storageKey, group) + all(storageKey, account()).map { it.item.key }.filter { key ->
            when (group) {
                is ThreadPageDownloadKey -> key is ThreadPageDownloadKey && group.tid == key.tid && group.authorId == key.authorId
                is TagMangaChapterDownloadKey -> key is TagMangaChapterDownloadKey && group.tagId == key.tagId
                is RssMangaChapterDownloadKey -> key is RssMangaChapterDownloadKey && group.subscriptionId == key.subscriptionId
            }
        }
    }

    override suspend fun deleteItems(storageKey: String, keys: Set<DownloadTaskKey>, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> {
        val owner = account()
        val ownedLegacy = keys.filterTo(mutableSetOf()) { key ->
            val saved = when (key) {
                is ThreadPageDownloadKey -> legacy.readManifest(key)
                is TagMangaChapterDownloadKey -> legacy.readTagMangaManifest(key)
                is RssMangaChapterDownloadKey -> legacy.readRssMangaManifest(key)
            }
            saved != null && (saved.owner == null || saved.owner == owner)
        }
        val errors = if (ownedLegacy.isEmpty()) mutableMapOf() else legacy.deleteItems(storageKey, ownedLegacy) {}.toMutableMap()
        writer.withLock {
            for ((tid, removed) in (keys - errors.keys).groupBy { it.pageKey.tid }) {
                try {
                    val old = state(storageKey, owner, tid)
                    val claims = old.claims.filterNot { it.item.key in removed }
                    if (claims.size == old.claims.size) continue
                    val live = claims.flatMapTo(mutableSetOf()) { it.floors.map(Floor::pid) }
                    val hashes = old.hashes.filterKeys(live::contains)
                    index.markLibraryDirty(storageKey, owner.toLong(), tid.toLong())
                    val draft = old.copy(claims = claims, hashes = hashes, edits = old.edits.filterKeys(live::contains),
                        observations = old.observations.filterKeys(live::contains))
                    val next = stagePosts(storageKey, owner, tid, old, draft, emptyMap())
                    writeState(storageKey, owner, tid, next)
                    index.transaction {
                        indexUses(storageKey, owner, tid, next.digests())
                        index.clearLibraryDirty(storageKey, owner.toLong(), tid.toLong())
                        for (key in removed) {
                            val indexId = "$owner/${key.stableId}"
                            index.clearFloors(storageKey, indexId)
                            index.clearPage(storageKey, indexId)
                            index.clearImages(storageKey, indexId)
                        }
                    }
                    retireImages(storageKey, owner, old.digests() - next.digests())
                    retireBatches(storageKey, owner, tid, old, next)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { removed.forEach { errors[it] = e } }
            }
        }
        onProgress(keys.size)
        return errors
    }

    /** Run only with no download/repair/migration staging image files. A folder rescan repairs a lost index. */
    suspend fun reconcile(storage: String, owner: Int, collect: Boolean) = writer.withLock {
        val claims = all(storage, owner)
        index.transaction {
            index.clearAliases(storage, owner.toLong())
            index.clearStorageUses(storage, owner.toLong())
            claims.groupBy { it.item.key.pageKey.tid }.forEach { (tid, group) ->
                indexUses(storage, owner, tid, group.flatMapTo(mutableSetOf()) { it.item.images.mapNotNull(DownloadedImage::digest) })
            }
            index.setInventoryReady(storage, owner.toLong())
            claims.sortedBy { it.item.savedAt }.forEach { indexClaim(storage, owner, it) }
            index.dirtyLibraryThreads(storage, owner.toLong()).executeAsList().forEach { index.clearLibraryDirty(storage, owner.toLong(), it) }
        }
        if (collect) {
            for (name in files.names(storage, "$owner/threads")) {
                val tid = name.toIntOrNull() ?: continue
                val folder = dir(owner, tid)
                val liveBatches = state(storage, owner, tid).locations().values.toSet()
                for (file in files.names(storage, folder)) {
                    val batch = file.removePrefix("posts-").removeSuffix(".zip")
                    if (file == "posts-$batch.zip" && batch.matches(Regex("[0-9a-f-]{36}")) && batch !in liveBatches) {
                        files.remove(storage, bodyPath(owner, tid, batch))
                    }
                }
            }
            val live = claims.flatMapTo(mutableSetOf()) { c -> c.item.images.mapNotNull { it.digest } }
            for (bucket in files.names(storage, "$owner/images").filter { it.matches(Regex("[0-9a-f]{2}")) }) {
                for (digest in files.names(storage, "$owner/images/$bucket")) {
                    if (digest.matches(digestPattern) && digest !in live) files.remove(storage, blob(owner, digest))
                }
            }
        }
    }
}
