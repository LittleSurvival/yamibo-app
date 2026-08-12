package me.thenano.yamibo.yamibo_app.repository.localnovel

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.LocalNovelFileType
import me.thenano.yamibo.yamibo_app.repository.LocalNovelInfo
import me.thenano.yamibo.yamibo_app.repository.LocalNovelChapterInfo
import me.thenano.yamibo.yamibo_app.repository.LocalNovelProgressInfo
import me.thenano.yamibo.yamibo_app.repository.LocalNovelRepository

class LocalNovelRepositoryImpl(
    db: Database,
) : LocalNovelRepository {
    private val novelQueries = db.localNovelQueries
    private val chapterQueries = db.localNovelChapterQueries
    private val progressQueries = db.localNovelProgressQueries

    override suspend fun getAllNovels(): List<LocalNovelInfo> {
        return novelQueries.getAllNovels().executeAsList().map { it.toInfo() }
    }

    override suspend fun getNovelById(id: Long): LocalNovelInfo? {
        return novelQueries.getNovelById(id).executeAsOneOrNull()?.toInfo()
    }

    override suspend fun insertNovel(novel: LocalNovelInfo): Long {
        novelQueries.insertNovel(
            fileType = novel.fileType.name.lowercase(),
            title = novel.title,
            author = novel.author,
            fileUri = novel.fileUri,
            coverPath = novel.coverPath,
            totalChars = novel.totalChars,
            encoding = novel.encoding,
            totalChapters = novel.totalChapters.toLong(),
            epubExtractDir = novel.epubExtractDir,
            createdAt = novel.createdAt,
            lastReadAt = novel.lastReadAt,
        )
        return novelQueries.getAllNovels().executeAsList().maxOfOrNull { it.id } ?: 0L
    }

    override suspend fun updateNovelMeta(
        novelId: Long,
        title: String,
        author: String,
        totalChars: Long,
        totalChapters: Int,
    ) {
        novelQueries.updateNovelMeta(
            title = title,
            author = author,
            totalChars = totalChars,
            totalChapters = totalChapters.toLong(),
            id = novelId,
        )
    }

    override suspend fun updateCoverPath(novelId: Long, coverPath: String) {
        novelQueries.updateCoverPath(coverPath = coverPath, id = novelId)
    }

    override suspend fun updateEpubExtractDir(novelId: Long, dir: String) {
        novelQueries.updateEpubExtractDir(epubExtractDir = dir, id = novelId)
    }

    override suspend fun updateLastReadAt(novelId: Long, time: Long) {
        novelQueries.updateLastReadAt(lastReadAt = time, id = novelId)
    }

    override suspend fun deleteNovel(novelId: Long) {
        chapterQueries.deleteChaptersByNovelId(novelId)
        progressQueries.deleteProgressByNovelId(novelId)
        novelQueries.deleteNovelById(novelId)
    }

    override suspend fun getChaptersByNovelId(novelId: Long): List<LocalNovelChapterInfo> {
        return chapterQueries.getChaptersByNovelId(novelId).executeAsList().map { it.toInfo() }
    }

    override suspend fun getChapterById(chapterId: Long): LocalNovelChapterInfo? {
        return chapterQueries.getChapterById(chapterId).executeAsOneOrNull()?.toInfo()
    }

    override suspend fun insertChapter(chapter: LocalNovelChapterInfo): Long {
        chapterQueries.insertChapter(
            novelId = chapter.novelId,
            title = chapter.title,
            chapterIndex = chapter.chapterIndex.toLong(),
            startOffset = chapter.startOffset,
            endOffset = chapter.endOffset,
            internalPath = chapter.internalPath,
        )
        return chapterQueries.getChaptersByNovelId(chapter.novelId).executeAsList().maxOfOrNull { it.id } ?: 0L
    }

    override suspend fun insertChapters(chapters: List<LocalNovelChapterInfo>) {
        chapters.forEach { insertChapter(it) }
    }

    override suspend fun deleteChaptersByNovelId(novelId: Long) {
        chapterQueries.deleteChaptersByNovelId(novelId)
    }

    override suspend fun getProgress(novelId: Long): LocalNovelProgressInfo? {
        return progressQueries.getProgressByNovelId(novelId).executeAsOneOrNull()?.toInfo()
    }

    override suspend fun saveProgress(progress: LocalNovelProgressInfo) {
        progressQueries.upsertProgress(
            novelId = progress.novelId,
            chapterId = progress.chapterId,
            charOffset = progress.charOffset,
        )
    }

    override suspend fun deleteProgress(novelId: Long) {
        progressQueries.deleteProgressByNovelId(novelId)
    }
}

private fun me.thenano.yamibo.yamibo_app.LocalNovel.toInfo(): LocalNovelInfo = LocalNovelInfo(
    id = id,
    fileType = LocalNovelFileType.fromString(fileType),
    title = title,
    author = author,
    fileUri = fileUri,
    coverPath = coverPath,
    totalChars = totalChars,
    encoding = encoding,
    totalChapters = totalChapters.toInt(),
    epubExtractDir = epubExtractDir,
    createdAt = createdAt,
    lastReadAt = lastReadAt,
)

private fun me.thenano.yamibo.yamibo_app.LocalNovelChapter.toInfo(): LocalNovelChapterInfo = LocalNovelChapterInfo(
    id = id,
    novelId = novelId,
    title = title,
    chapterIndex = chapterIndex.toInt(),
    startOffset = startOffset,
    endOffset = endOffset,
    internalPath = internalPath,
)

private fun me.thenano.yamibo.yamibo_app.LocalNovelProgress.toInfo(): LocalNovelProgressInfo = LocalNovelProgressInfo(
    novelId = novelId,
    chapterId = chapterId,
    charOffset = charOffset,
)
