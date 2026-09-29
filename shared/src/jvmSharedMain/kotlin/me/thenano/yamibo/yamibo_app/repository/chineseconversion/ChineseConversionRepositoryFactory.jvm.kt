package me.thenano.yamibo.yamibo_app.repository.chineseconversion

import me.thenano.yamibo.yamibo_app.repository.AndroidChineseConversionRepository
import me.thenano.yamibo.yamibo_app.repository.ChineseConversionRepository

actual fun createChineseConversionRepository(): ChineseConversionRepository = AndroidChineseConversionRepository()
