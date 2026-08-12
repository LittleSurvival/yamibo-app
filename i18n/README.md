# Build-time i18n auto merge

Use lowercase `i18n("...")` in Compose UI code:

```kotlin
Text(i18n("Login"))
Text(i18n("Hello, {}", userName))
Text(i18n("Visited {} times", times))
```

Parameters are positional and replace `{}` markers from left to right. Values can
be `Int`, `String`, or any nullable object; generated Android resources receive
the matching `%1$s`, `%2$s`, ... placeholders.

The Gradle task scans `composeApp/src` and `shared/src`, reads `i18n/glossary.csv`
and `i18n/base.csv`, and generates:

- `composeApp/build/generated/i18n/composeResources`
- `composeApp/build/generated/i18n/kotlin`
- `composeApp/build/reports/i18n`

Run manually with:

```powershell
.\gradlew.bat :composeApp:generateI18nResources
.\gradlew.bat :composeApp:checkI18n
```

The task is also wired before normal Kotlin compile, Compose resource generation,
Android `preBuild`, `assembleDebug`, `assembleRelease`, and `installDebug`.

`glossary.csv` is the primary three-language source for app UI translations, while
`base.csv` supplies fallback translations and repairs stale Simplified Chinese
mirrors. For `zh-cn`, a glossary value that merely repeats its `zh-tw` value yields
to a distinct non-empty base translation; explicit glossary Simplified translations
remain authoritative. Do not add UI strings to `composeResources/values*/strings.xml`;
Compose resources are reserved for non-i18n assets such as images and icons.

```csv
source,en,zh-tw,zh-cn
Login,Login,登入,登录
```

Generated resources use the app's existing language directories:

- `values` and `values-zh-rTW` for Traditional Chinese
- `values-zh-rCN` for Simplified Chinese
- `values-en` for English

This keeps the current `AppLanguage` and `AppLocaleProvider` language switching
path intact.
