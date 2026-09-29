package me.thenano.yamibo.yamibo_app

actual fun getPlatform(): Platform = object : Platform {
    override val name: String = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
}
