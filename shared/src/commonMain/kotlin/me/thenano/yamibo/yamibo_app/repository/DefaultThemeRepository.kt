package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme

class DefaultThemeRepository : ThemeRepository {
    private var currentScheme: YamiboColorScheme = YamiboColorScheme.Default

    override fun getColorScheme(): YamiboColorScheme = currentScheme

    override fun setColorScheme(scheme: YamiboColorScheme) {
        currentScheme = scheme
    }

    override fun getAllThemes(): List<YamiboColorScheme> = YamiboColorScheme.all
}
