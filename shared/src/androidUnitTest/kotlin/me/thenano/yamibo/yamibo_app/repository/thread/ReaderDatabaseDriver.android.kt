package me.thenano.yamibo.yamibo_app.repository.thread

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.thenano.yamibo.yamibo_app.Database

internal actual fun readerDatabaseDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
