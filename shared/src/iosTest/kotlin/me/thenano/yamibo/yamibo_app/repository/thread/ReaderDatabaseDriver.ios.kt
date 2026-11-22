package me.thenano.yamibo.yamibo_app.repository.thread

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.uuid.Uuid
import me.thenano.yamibo.yamibo_app.Database

internal actual fun readerDatabaseDriver(): SqlDriver = NativeSqliteDriver(Database.Schema, "reader-qa-${Uuid.random()}.db")
