package me.thenano.yamibo.yamibo_app.repository.thread

import app.cash.sqldelight.db.SqlDriver
import me.thenano.yamibo.yamibo_app.Database
import kotlin.test.*

internal expect fun readerDatabaseDriver(): SqlDriver

class ReaderStorageIndexTest {
    private val driver = readerDatabaseDriver()
    private val db = Database(driver)
    @AfterTest fun close() { driver.close() }

    @Test fun copiesAreIsolatedByAccountStorageAndPagination() {
        val pages = db.readerDownloadQueries
        pages.putPage("disk-A","rss",7,140,7,1,"rss-item","generation-A")
        pages.putFloor("disk-A","rss",1402)
        pages.putPage("disk-A","full",7,140,0,2,"thread-item","generation-B")
        pages.putFloor("disk-A","full",1402)
        assertEquals(1L,pages.pageForPid("disk-A",7,140,7,1402).executeAsOne())
        assertEquals(2L,pages.pageForPid("disk-A",7,140,0,1402).executeAsOne())
        assertNull(pages.pageForPid("disk-A",8,140,7,1402).executeAsOneOrNull())
        assertNull(pages.pageForPid("disk-B",7,140,7,1402).executeAsOneOrNull())
        assertEquals("rss-item",pages.pageCopies("disk-A",7,140,7,1).executeAsOne().taskKey)
        assertTrue(pages.pageCopies("disk-A",7,140,0,1).executeAsList().isEmpty())
    }

    @Test fun deletingReadingHistoryClearsTheWorkResumeButRetainsMembershipAndDownload() {
        val works = db.smartComicWorkQueries
        works.createWork(7,30,"series")
        val id = works.newId().executeAsOne()
        works.saveMember(7,30,140,id,"summary",0)
        works.visit(140,9000,id,7)
        works.saveProgress(7,30,140,1,5,6,1,9000)
        db.readerDownloadQueries.putPage("disk","copy",7,140,7,1,"copy","G")
        db.imageReadingHistoryQueries.upsert(1401,140,5,6,null,null,9000)
        db.imageReadingHistoryQueries.saveAnchor(1401,9000,1402,"img",1,1,7)
        db.imageReadingHistoryQueries.deleteAll()
        assertNull(works.workById(id,7).executeAsOne().lastTid)
        assertTrue(works.progress(7,30,listOf(140L)).executeAsList().isEmpty())
        assertNull(db.imageReadingHistoryQueries.getAnchor(1401,9000).executeAsOneOrNull())
        assertEquals(id,works.memberWork(7,30,140).executeAsOne())
        assertEquals("G",db.readerDownloadQueries.pageCopies("disk",7,140,7,1).executeAsOne().generation)
    }
}
