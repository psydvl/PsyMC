# PsyMC

Шаблоны для Minecraft: мод, датапак и серверный плагин, каждый на несколько версий игры сразу.

| Папка | Что внутри | Состояние |
| --- | --- | --- |
| `templatemod/` | Мод для NeoForge, Fabric и NilLoader | Рабочий пример |
| `templatedatapack/` | Датапак | Заготовка, план в README |
| `templateplugin/` | Плагин для серверов | Заготовка, план в README |

Лицензия: MIT (`LICENSE`).

## `templatemod/` — мод на несколько загрузчиков и версий сразу

Рабочий пример-мод `examplemod`, который собирается под NeoForge, Fabric и NilLoader на пяти пинах
и проверяется на всех релизах их линий.

### Идея

- **Пин** — один релиз Minecraft, под который компилируется мод. Jar пина запускается на всех
  релизах своей линии. Пины шаблона: `1.21.1` (1.20.5–1.21.1), `1.21.4` (1.21.2–1.21.4),
  `1.21.10` (1.21.5–1.21.10), `1.21.11`, `26.2` (26.1–26.3, необфусцированный jar).
- **Gradle-проект на пин и загрузчик**: `:mod:<pin>:<loader>` в `mod/versions/<pin>/<loader>`.
  Leaf-файл только выбирает плагины (ModDevGradle / Fabric Loom с remap или без) и подключает
  общий скрипт. Версии плагинов — в одном месте, `settings.gradle.kts`.
- **Таблица пинов** — `buildSrc/src/main/kotlin/psymc/Versions.kt` (версии NeoForge, Fabric API,
  диапазоны для метаданных, библиотеки).
- **Релизы для проверок** — `versionProperties/<release>.properties`: какой пин на нём запускается,
  версии загрузчиков, id модов для `-Pcompat`, переопределения библиотек.
- **Слои исходников** в `mod/src`: `common` → линия (`rl` = ResourceLocation, `id` = Identifier)
  → пин → загрузчик → загрузчик/линия → загрузчик/пин. Файл верхнего слоя заменяет файл с тем же
  путём. Так различия API живут в маленьких файлах, а не в `if`.
- **Ленивое подключение проектов**: settings включает только пины, нужные запрошенным задачам
  (`:mod:1.21.4:fabric:runClient` поднимает один пин; `-Ppins=...` — выбранные).

### Что умеет сборка

| Задача | Что делает |
| --- | --- |
| `compileAll`, `:mod:<pin>:<loader>:compileJava` | Компиляция (javac lint + Error Prone). |
| `spotlessApply` / `spotlessCheck` | Google Java Format, ktlint для `*.kts`/`*.kt`, хвосты в текстах. |
| `mergeJars` | Forgix: один jar на пин для обоих загрузчиков. |
| `versionSmoke -Ploader=nil` | nil-jar через `-javaagent:NilLoader` в чистой ванилле (клиент из piston-meta). |
| `:mod:<pin>:<loader>:runClient` | Без флагов — headless smoke (HeadlessMc), сам останавливается. |
| `... runClient -Pplay` / `runServer -Pplay` | Окно / сервер для человека; `-PtestWorld=<name>` сразу открывает подготовленный мир. |
| `versionSmoke` | Jar пина на каждом релизе линии; отчёт в `build/version-smoke/`. |
| `versionPlay -Pmc=<release>` | Jar пина на другом релизе линии с окном. |
| `serverSmoke` | Выделенный сервер + headless клиент по TCP + сценарий консоли. |
| `nilCheck` | Цели Mini-патчей и ссылки nil-мода на игру против jar каждого релиза линии + наборы `mappings.json`, без запуска. |
| `mixinCheck` | Сверка селекторов и сигнатур обработчиков миксинов с jar игры каждого релиза, без запуска; заглушки классов; ожидаемые промахи с причиной (`expected.txt`) — ворота в CI. |
| `mcInspect`, `mcLambdas` | Сигнатуры, вызовы, байткод метода игры и имена его лямбд по всем релизам, ваниль и NeoForge. |
| `lastRun` | Последний краш-репорт и ошибки из `latest.log` без шума офлайн-запуска. |
| `makeTestWorld` | Тестовый мир (плоский, креатив, читы) через ванильный сервер 1.20.5 в `testworlds/` и сохранённый хотбар `testworlds/hotbar.nbt` (X+1 в мире). |
| `mixinAudit` | Мод-аудитор грузит все цели миксинов в живом клиенте. |

Общие флаги: `-Ploader=fabric|neoforge`, `-Ppin=...`, `-Pmc=...`, `-PmainOthers`, `-Pline`
(serverSmoke), `-Pheaded`, `-PrealGl` (реальный OpenGL в скрытом окне), `-Pcompat=sodium,iris,dh`
(+ шейдерпак, `-PnoShaderpack`, `-PcompatMods`).

Подробно: `templatemod/docs/architecture.md`, `templatemod/docs/testing.md`.

### Агентская обвязка

- `AGENTS.md` — короткая карта: команды, ссылки, правила (слои, «правка на одном пине — правка на
  всех», STE-комментарии, скрипты только на Kotlin DSL, черновики в `.local/work/`).
- `.local/` (в `.gitignore`) — машинные заметки: `.local/AGENTS.md` (текущая цель, живой клиент,
  открытый баг), `.local/TODO.md` (по строке на пункт), `.local/work/` (логи, черновики).
- `.editorconfig`, `.gitattributes` (LF, CRLF для `.bat`), `mise.toml` (JDK 21/25 через mise,
  Gradle находит их как toolchains).
- CI: шаги (spotlessCheck, compileAll, mergeJars, mixinCheck и serverSmoke на обоих загрузчиках, артефакты и отчёты) — composite action
  `templatemod/.github/actions/build`. Её вызывают `templatemod/.github/workflows/build.yml` (когда
  мод живёт в своём репозитории) и корневой `.github/workflows/templatemod.yml` (в PsyMC, на
  изменения в `templatemod/**`). GitHub читает workflows только из корня, а action — из любой папки.

### Новый мод из шаблона

1. Скопировать `templatemod/` в новый репозиторий.
2. `gradle.properties`: `mod_id`, `mod_name`, `mod_group_id`, авторы, лицензия, `mixin_configs`.
3. Переименовать пакет `dev.psydvl.mc.example`, `ExampleMod.MOD_ID`, файл
   `mod/src/common/resources/<mod_id>.mixins.json`, `assets/<mod_id>`, классы entry в
   `fabric.mod.json`.
4. Обновить заголовок `AGENTS.md`, шаги `serverScenario` в `gradle/version-smoke.gradle.kts`.
5. Лишние пины — удалить из `Versions.kt` и `mod/versions/`; новые — по
   `docs/architecture.md`, «Add a pin».
6. После `git init`: `git update-index --chmod=+x gradlew` (Windows не ставит бит исполнения).

### NilLoader

Третий лист `:mod:<pin>:nil` — мод для [NilLoader](https://git.sleeping.town/Nil/NilLoader): Java-агент
с патчами байткода (Mini) вместо Mixin. NilGradle на Gradle 9 не применяется, поэтому сборка своя
(`gradle/mod-nil.gradle.kts`, `buildSrc/.../NilMappings.kt`): jar игры из piston-meta ремапится в
Mojang-имена для компиляции, а в мод кладётся `META-INF/nil/mappings.json` с наборами на каждый
обфусцированный релиз линии пина, плюс `intermediary` (рядом с Fabric) и `mojmap` (рядом с NeoForge).
Набор выбирается в premain. Проверки: `nilCheck` (статически), `versionSmoke` и `serverSmoke` с
`-Ploader=nil` (ванильный клиент и сервер с NilLoader; строки патчей обязательны). NilLoader опционален: убрать `nil` из `loaders` в `gradle.properties` (полное удаление — список файлов в `docs/architecture.md`). Миксины лежат в пакете `mixin` и в nil-лист не попадают, их работу
делают Mini-патчи в `src/nil`. Подробно: `docs/architecture.md`, раздел NilLoader.

### Ограничения

- Пины 1.20.2–1.20.4 не поддерживаются: там `mods.toml` вместо `neoforge.mods.toml` и NeoForge
  без метаданных ModDevGradle. Релиз 1.20.5 проверяется только на Fabric.
- Ванильный NilLoader-лаунч 1.21.2, 1.21.3 и 26.1.x требует HeadlessMc 3.0.0-RC8+ (исправление
  [headlessmc#471](https://github.com/headlesshq/headlessmc/issues/471) и
  [#472](https://github.com/headlesshq/headlessmc/issues/472)).
