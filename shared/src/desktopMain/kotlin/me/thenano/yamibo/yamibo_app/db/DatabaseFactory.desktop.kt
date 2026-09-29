package me.thenano.yamibo.yamibo_app.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.desktop.DesktopDirectories
import java.nio.file.Files
import java.nio.file.Path

actual class DatabaseFactory(
    private val databasePath: Path = DesktopDirectories.dataRoot().resolve("yamibo.db"),
) {
    actual fun createDriver(): SqlDriver {
        Files.createDirectories(databasePath.toAbsolutePath().parent)
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}",
            schema = Database.Schema,
        )
    }
}
