package me.thenano.yamibo.yamibo_app.repository.download

import no.synth.kmpzip.io.SeekableSource
import no.synth.kmpzip.okio.asSeekableSource
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.use

/** Paths are relative to the selected folder's library. Never accept a path from a reader URI. */
interface OfflineFiles {
    fun open(storage: String, path: String): Source?
    fun seek(storage: String, path: String): SeekableSource?
    fun names(storage: String, path: String): List<String>
    fun install(storage: String, path: String, checksum: String, source: () -> Source)
    fun remove(storage: String, path: String)
}

internal fun libraryParts(path: String): List<String> = path.split('/').also { parts ->
    require(parts.all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." &&
        segment.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } })
}

internal fun Source.verifyDigest(expected: String) = use { input ->
    val hash = HashingSink.sha256(blackholeSink())
    hash.buffer().use { it.writeAll(input) }
    check(hash.hash.hex() == expected) { "下載資料寫入後校驗失敗" }
}

/** Native filesystem adapter. Android document trees supply the same operations through SAF. */
internal class PathOfflineFiles(private val fs: FileSystem, private val root: (String) -> Path) : OfflineFiles {
    private fun target(storage: String, path: String) = libraryParts(path).fold(root(storage)) { dir, part -> dir / part }

    override fun open(storage: String, path: String): Source? = target(storage, path).let {
        if (fs.exists(it)) fs.source(it) else null
    }

    override fun seek(storage: String, path: String): SeekableSource? = target(storage, path).let {
        if (fs.exists(it)) fs.openReadOnly(it).asSeekableSource() else null
    }

    override fun names(storage: String, path: String): List<String> = target(storage, path).let {
        if (fs.exists(it)) fs.list(it).map { child -> child.name } else emptyList()
    }

    override fun install(storage: String, path: String, checksum: String, source: () -> Source) {
        val target = target(storage, path)
        val parent = checkNotNull(target.parent)
        fs.createDirectories(parent)
        val tmp = parent / (target.name + ".tmp")
        try {
            fs.sink(tmp).buffer().use { sink -> source().use(sink::writeAll) }
            fs.source(tmp).verifyDigest(checksum)
            fs.atomicMove(tmp, target)
        } finally { fs.delete(tmp) }
    }

    override fun remove(storage: String, path: String) = fs.delete(target(storage, path))
}
