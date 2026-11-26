# FrameScale 0.6.0-alpha.4

Экспериментальный client-side мод Minecraft 1.21.1 / NeoForge / Java 21. Использует штатный временной апскейлер Photon (не NVIDIA DLSS). Не содержит нативных библиотек и не меняет рендер до явной команды игрока. Iris используется через проверяемую рефлексию; API не гарантирован, при несовместимости команда сообщает об ошибке.

## 0.6.0-alpha.4 — Vulkan external resource probe

Added a standalone Linux Vulkan → OpenGL opaque FD image/semaphore smoke test in native/interop-probe. /framescale gpu now reports GL device UUIDs when available. The diagnostic matches Vulkan device/driver UUIDs, checks export support, creates a dedicated RGBA8 image, transfers queue ownership to external, and verifies imported pixels after GL semaphore wait. No code is loaded into Minecraft from the native probe. CI builds the tool and distinguishes a capability skip from a verified pass. Hardware test required. See native/interop-probe/README.md. No active Photon integration, reverse GL → VK path or DLSS yet.

## 0.6.0-alpha.3 — Direct GPU path

Spatial probes compute access to mutable RGBA8 textures attached to disposable FBOs on the current GPU. If validated, the reduced color texture is read directly and native color written directly: two staging copies and two staging textures are omitted. Otherwise the original immutable staging path remains. Depth blit is retained. The probe uses a small synchronous readback at target creation, not per frame. Java failures during presentation stop Spatial and attempt fallback blit. The probe does not prove all possible driver/renderer combinations are safe.

Iris API v0 is checked for both explicitly disabled shaders and no active shaderpack. Spatial may run with Iris installed only in that state. Unknown state, GPUTape, DH and competing framebuffer owners remain blocked. Enabling shaders stops Spatial before the next scoped world render. Active Photon continues to use TAAU; this is not active shaderpack integration.

Use `/framescale upscale cost start` after the first active world frame, `/framescale upscale cost report` and `/framescale upscale cost stop`. GPU timestamps cover color reconstruction/transfer and depth blit, sampled once per eight active frames, not the world render. Timing stops when resources are recreated; completed results remain until a new capture. Pending results may be discarded. EGL tests also cover mutable FBO textures. No measured RTX or Minecraft FPS improvement yet.

## 0.6.0-alpha.2 — Experimental world integration

В отдельном тестовом инстансе NeoForge/Sodium без Iris, GPUTape, DH и конкурирующих framebuffer/upscaler mods войдите в мир, выберите обычную графику (не Fabulous), затем `/framescale upscale spatial 75`. `/framescale upscale spatial 100` отключает режим; `/framescale upscale` показывает состояние. Также нативный слайдер Spatial в Sodium; изменения применяются штатным auto-apply FrameScale. Настройка действует только в текущем сеансе.

GameRenderer.renderLevel обёрнут MixinExtras WrapMethod, getter main target перенаправляется только в пределах этого вызова на собственный уменьшенный TextureTarget. Размер окна и исходный framebuffer не изменяются. После world render scope снимается в finally, цвет копируется в immutable RGBA8 input, compute kernel масштабирует в output, затем GPU copy возвращает его в нативный color target; depth переносится nearest blit. HUD вне этого scope остаётся в исходном разрешении по архитектуре. Выделения переиспользуются, освобождаются при отключении/выходе из мира, при resize пересоздаются. При Java/GL-wrapper exception предусмотрено отключение и fallback blit, но GL errors, несовместимые чужие hooks и корректность картинки ещё не подтверждены runtime-тестом. Никакого CPU readback в игровом пути. Стоимость staging copies, compute и depth blit измерить перед выводом о FPS.

Это предварительная интеграция, не замена Photon. Iris (даже выключенный), GPUTape, Distant Horizons и известные конкурирующие рендереры блокируют Spatial до проверки их API. Меню, Fabulous и активный vanilla post effect приостанавливают его. Гарантия совместимости со всеми остальными модами отсутствует; тестируйте копию инстанса. При активном Photon используйте прежний TAAU/Auto. DLSS отсутствует.

## 0.6.0-alpha.1 — Independent spatial GPU backend

Добавлен собственный GLSL compute kernel (4 исходных texel taps, адаптация веса интерполяции по направлению контраста, без overshoot). Это пространственная реконструкция LDR RGBA8, не FSR, не DLSS и не temporal upscaling. Работа с двумя отдельными immutable GL_TEXTURE_2D, OpenGL 4.5 DSA, группами 8×8, с проверкой формата и размеров, явным memory barrier и восстановлением program/image bindings. Алгоритм не требует CPU readback в обычном dispatch. Для реальных HDR/sRGB/temporal материалов нужны отдельные решения.

`/framescale upscale test` запускает небольшой явный self-test на текущем GPU Minecraft и проверяет пиксели в отдельной паре тестовых текстур: постоянный цвет, identity, контрастные края, альфа и нечётные размеры. Только в self-test выполняется синхронное чтение тестовых пикселей. Не трогает framebuffer игры, не меняет Iris, не запускается при активном Auto/профилировании или конфликтующем рендерере. `/framescale gpu` показывает наличие OpenGL 4.5. При отсутствии поддержки штатные функции сохраняются.

GLSL проверяется независимо через EGL/Mesa: `LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless python3 tests/spatial_gl_check.py src/main/resources/assets/framescale/shaders/spatial.comp`. Тест не подтверждает Java state restoration, реальный RTX, производительность или качество в Minecraft. В alpha.1 бэкенд ещё не был подключён к мировому кадру: сначала требуется проверить место интеграции, source/output формат и владение буферами с Iris/Sodium/GPUTape. Не добавляет фиктивный переключатель алгоритма. Действующий TAAU/Auto и меню Sodium сохранены. Сам тестовый kernel пока не увеличивает игровой FPS.

## 0.5.0 — Effect budgets

В нативной странице Sodium добавлен параметр «Бюджет эффектов»: Original / Shadows / Clouds / Reflections / Volumetrics / Balanced. При выборе меняются только распознанные числовые опции Photon, на одно изменение пресета приходится не более одного reload. Balanced не уменьшает shadow map и render scale. Для отдельных эффектов действуют нижние границы и разрешённые значения из активного шейдерпака; если допустимого умеренного снижения нет, значение сохраняется. Отсутствующие опции пропускаются; наличие опции не доказывает, что эффект активен. Не изменяются TAA/TAAU, дальность теней, фильтрация и освещение в целом. Это управляемые статические бюджеты sampling, а не runtime adaptive sampling или shadow caching. Качество изображения может измениться; FPS зависит от сцены.

Команды: `/framescale effects preview balanced` показывает точные изменения без записи; `/framescale effects apply balanced` применяет; `/framescale effects restore` возвращает исходные tracked-значения текущего сеанса. Доступны также shadows, clouds, reflections, volumetrics, original. Переключение между пресетами рассчитывается от исходного снимка, не накапливая снижение. При стороннем изменении tracked-опций восстановление и применение блокируются; `/framescale effects forget` забывает снимок без изменения шейдеров. Снимок существует только до закрытия игры; Iris сохраняет изменённые значения, поэтому перед использованием экспортируйте профиль Photon. Ошибка reload не гарантирует откат. Auto и профилирование выключаются перед изменением пресета. Можно включить Auto повторно. При одновременном изменении масштаба и пресета Sodium возможны две перезагрузки.

Для сравнения: измерить одинаковую прогретую сцену `/framescale passes start 240`, экспортировать CSV, применить один preset, повторить capture и общий FPS benchmark; вернуть исходные значения, если картинка или FPS хуже. Оценка реального GPU-прироста и совместимость модпака требуют запуска. DLSS не реализован.

## 0.4.0 — Iris GPU stage profiler

- `/framescale passes start 240` — после закрытия чата и двух секунд прогрева собрать до 240 полных кадров (60–1200).
- `/framescale passes report` — среднее, p95 и доля времени этапа; результаты отсортированы по среднему времени.
- `/framescale passes stop` — остановить и вывести собранные данные.
- `/framescale passes export` — CSV с mean, median, p95 и долей в каталоге инстанса.

Измеряются десять непересекающихся интервалов от начала до конца Iris level rendering: setup/clear/begin, pre-shadow gap, shadows (включая shadow composite), prepare, sky/opaque geometry, hand/depth copy, deferred/depth copy, translucent/hand, composite, final. Не отдельные GLSL программы: отражения, AO, облака и освещение могут выполняться в общих стадиях. Доля относится только к измеренному интервалу, не ко всему кадру или задержке экрана. GL timestamp intervals могут включать ожидания и GPU idle; не эквивалентны чистому shader execution time.

Iris reference: https://github.com/IrisShaders/Iris/tree/12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3 . В отличие от предыдущих версий добавлен один опциональный наблюдающий Mixin к IrisRenderingPipeline, без redirects, отмены вызовов, изменения буферов и shader state. Плагин проверяет структуру байткода, require=0; неполная/повторная/неупорядоченная последовательность маркеров отбрасывается. При неизвестной структуре профилировщик недоступен. Поддержка конкретного JAR Iris не подтверждена запуском. Для этой функции 8 слотов × 11 timestamp queries, опрос доступности перед чтением, без glFinish. Ресурсы освобождаются при остановке; незавершённые результаты могут быть потеряны. При открытом меню измерение приостанавливается, при изменении мира/разрешения завершается, общий таймаут 120 секунд. Auto и обычный benchmark выключаются при старте capture; ручное изменение масштаба останавливает capture. Снятие данных добавляет небольшой overhead, поэтому сравнивайте одинаковый режим профилирования. Не повышает FPS сам по себе. DLSS не реализован.

## 0.3.1 — Auto safety

- Сравнение замеров учитывает позицию и направление камеры (8 блоков, 35° yaw, 25° pitch). При движении за пределы допуска и загрузке чанков измерения сбрасываются.
- Понижение масштаба остаётся пробным до двух последовательных стабильных окон с измеренным улучшением. Если сцена изменилась или два окна не подтвердили пользу, предыдущее качество восстанавливается после cooldown и стабильного игрового состояния. Ручное выключение Auto оставляет текущий масштаб; при смене мира Auto останавливается без автоматической перезаписи чужого состояния.
- Целевой FPS и нижний предел сохраняются в config/framescale.properties атомарной заменой файла; сам Auto по-прежнему включается явно после запуска игры.
- /framescale gpu — реальные GL vendor, renderer, версия, framebuffer и расширения memory object / semaphore для оценки возможности GL/Vulkan interop. Это только диагностический отчёт: Vulkan/NGX не инициализируются и DLSS отсутствует.
- Проверки политики и сохранения настроек входят в CI. Реальный запуск в модпаке ещё требуется.

## 0.3.0 — Auto quality

Native Sodium page: choose Auto at the left end of Render Scale, or `/framescale auto on`. Target defaults to 80 FPS and minimum downward scale to 75%; change both using native sliders or `/framescale auto target 80`, `/framescale auto floor 75`. `/framescale auto` shows status; `/framescale auto off` stops adaptation and keeps the current scale. Auto is session-only; since 0.3.1 target and floor persist, and Photon persists the last applied shader scale. Manual scale and manual benchmark stop Auto.

The governor waits for focused gameplay, a 10-second warmup after menus/resizes/world changes/reload and chunk bursts (4+ client chunk-load events per second). Six-second windows use median, p90 and outlier fraction; unstable windows are discarded. It requires three sustained slow GPU-heavy windows to reduce by at most five percentage points and four headroom windows to increase quality. Lowering is bounded by the floor; if a manually chosen starting scale is already lower, Auto will not lower it further. GL timestamps are a heuristic, not definitive GPU-utilization measurements. No GPU timing means no automatic scale changes. VSync or FPS limits below the target can prevent adaptation. Only one reload per 60 seconds and at most six per activation, including rollback; a reduction without measured gain in a comparable area can restore prior TAA/TAAU/scale and block further reductions. Camera/scene changes can invalidate comparisons. Menu pause, external settings changes, unsupported shader/API and failed reload stop or pause safely. Failed Iris reload is not guaranteed to roll back. No continuous per-frame dynamic resolution.

Quality comes first: native or higher scale is preferred when measured headroom exists. Temporal reconstruction is not mathematically lossless. DLSS is not implemented; see docs/DLSS.md for integration requirements. No new Mixin, GL framebuffer override, native libraries or third-party upscaler. Complete modpack/runtime tests remain required.

## 0.2.0-alpha.3 — Нативные настройки Sodium

В настройках графики Sodium находится собственная категория FrameScale, зарегистрированная через OptionPageBuilder и IntegerOptionBuilder. Это настоящий слайдер Sodium, не внешний экран. Крайнее левое положение отключает TAAU, остальные значения — 50–100%. Текущая версия рассчитана на Photon с полным рядом значений 0.50…1.00; иначе слайдер отключается. Фактический scale считывается из Iris; при отсутствии поддержки доступен запасной экран Mods → FrameScale → Config.

Слайдер отображает расчётное внутреннее → выходное разрешение, используя физические размеры framebuffer окна Minecraft, а не GUI scale и не фиксированное 1920×1080. Например, 75% на 1920×1200: 1440×900 → 1920×1200. Для отдельных shader passes размеры могут отличаться. Процент доступен в подсказке.

Автоприменение: публичный Sodium Config API не предоставляет live apply. Для Sodium 0.8.13 проверяются сигнатуры ConfigManager.CONFIG.getOption / StatefulOption.hasChanged / Config.applyOption. После отпускания мыши и трёх стабильных клиентских тиков вызывается applyOption только для framescale:render_scale; чужие настройки не применяются. Если API недоступен, остаётся стандартная кнопка Apply. При ошибке Iris журналируется причина и слайдер синхронизируется с фактическим состоянием. При открытии меню настройки не изменяются. Перекомпиляция Photon всё ещё необходима; настоящего динамического разрешения и DLSS нет.

Исправлена ошибка компиляции предыдущей версии: нельзя переопределять final renderWidget у AbstractSliderButton. Запасной экран использует штатный слайдер Minecraft. Добавлены независимые тесты вычисления разрешения. Runtime совместимость с точными версиями модпака ещё не проверена.

## Сборка

Требуются JDK 21 и Gradle 8.14.x. В Arch можно использовать установленный совместимый Gradle либо официальный дистрибутив https://gradle.org/releases/. Wrapper не включён, поскольку в среде подготовки нет доступа к Maven/Gradle.

```bash
java -version
gradle --version
gradle build
```

Версия NeoForge по умолчанию 21.1.267, можно указать версию своей сборки: `gradle build -Pneo_version=21.1.XXX` (заменить на фактическую версию). JAR в `build/libs/`. Установить только в копию инстанса Modrinth, с уже работающими Iris/Sodium и Photon. Не сочетать с внешним апскейлером одновременно. Рекомендуется сохранить копию настроек Photon через экспорт Iris перед экспериментом.

## Команды

- `/framescale status` — проверяет текущий шейдерпак и наличие опций TAA/TAAU.
- `/framescale scale 75` — запрашивает включение TAA/TAAU и масштаб 75%, один раз перезагружает шейдеры. Меняет три опции через Iris; сам профиль освещения не меняется, но Iris может показать профиль Custom. Проверить фактические значения в GUI. Не выполняется автоматически. Если API не совместим, установить масштаб через GUI Photon.
- `/framescale scale 100` — нативный масштаб с включённым TAAU (не восстановление всех старых настроек). Для полного возврата импортировать экспорт настроек Iris.
- `/framescale bench start` — очистить метрики и начать измерение.
- `/framescale bench report` — показать текущие метрики.
- `/framescale bench stop` — остановить, освободить query objects и показать метрики.

## Тестирование

Одинаковая сцена, разрешение, дальность, время суток и Medium/High. Дождаться компиляции и прогрева чанков после каждого изменения; начать измерения, закрыть чат, постоять или повторить один маршрут 30 секунд, затем остановить. Повторить 3 раза для каждого масштаба 100%, 85%, 75%, 67%, 60%. Для исходной сцены с выключенным TAAU установить настройки вручную. Временно убрать ограничитель FPS и VSync для диагностики, затем вернуть. Последние 1800 кадров: средний FPS, p95 frame time, 1% low (обратная величина среднего 1% самых медленных кадров). GPU timestamp queries измеряют интервал команд GL между render events, не являются разбивкой отдельных проходов, не включают всю задержку дисплея и могут включать простои. Результаты GPU могут быть недоступны или приходить с задержкой; оставшиеся к моменту stop queries не учитываются. CPU-side frame interval включает ожидания и ограничитель кадров; это не чистое CPU time.

Мод не гарантирует 80 FPS. Если снижение масштаба не улучшает FPS/GPU time, исследовать shadow pass, power limit, VRAM, CPU и presentation. Автоматическое изменение масштаба ограничено cooldown: Photon использует compile-time defines, а reload во время игры вызывает фризы.

## Статус

Проверена компиляция независимых классов FrameStats и PhotonBridge через javac 21, тест FrameStats. Gradle-сборка 0.3.0 прошла GitHub Actions; запуск Minecraft не проверен. Iris API и рендер-события требуют проверки с точными версиями установленной сборки. DLSS, FSR, dynamic resolution и профилирование отдельных shader passes пока не реализованы.

## Следующие этапы

1. Реальный benchmark и проверка кадров с Photon Medium/High.
2. Проверка совместимости с Super Resolution на том же инстансе (без одновременного TAAU), сравнение DLSS/FSR и Photon.
3. При необходимости — shaderpack-specific adapter с motion vectors, depth, jitter, exposure, корректным сбросом history. GL/Vulkan interop на одном GPU, без CPU readback, проверка лицензий NVIDIA SDK. Dynamic resolution — только при поддержке изменения размера без reload.

Ссылки: https://github.com/sixthsurge/photon ; https://github.com/IrisShaders/Iris ; https://github.com/IReallyWantToSleep/superresolution ; https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle

## alpha.2 — HARD-NEW-v3

Последний известный состав: NeoForge 21.1.267, Iris 1.8.14-beta.1, Sodium 0.8.13, GPUTape 1.0.x, Super Resolution 0.9.2-alpha.2 (исторические сведения, не актуальный инвентарь). FrameScale не исправляет чужой запрет Super Resolution + gpu_tape и не меняет чужие JAR. Собственных Mixin и подмен framebuffer нет. При обнаружении известных внешних апскейлеров (`super_resolution`, `superresolution`, `renderscale`, `render_scale`) или `vulkanmod` отключаются только TAAU-контроллер и GPU timestamp queries FrameScale; CPU frame intervals доступны. Это консервативная политика, не универсальная проверка. GPUTape не блокируется; его реальная совместимость ещё не проверена. Iris bridge экспериментальный, работает по явной команде.

Новые команды: `/framescale compat` — основные mod ID и политика; `/framescale compat export` — все загруженные mod ID и версии в новом текстовом файле в каталоге инстанса.

Автономный сканер (Python 3.11+, JDK 21 для javap) только читает JAR и не запускает их. В Modrinth откройте папку инстанса, затем из каталога FrameScale:

```bash
python3 tools/audit_pack.py "/путь/к/HARD-NEW-v3" > framescale-audit.json
```

Отчёт включает SHA256, метаданные, зависимости, Mixin-конфигурации, кандидаты на графические классы, сигнатуры классов Iris при наличии javap, имена шейдерпаков. Нет логов, конфигов и содержимого миров. Перед отправкой просмотрите отчёт. Вложенные JAR перечислены, но не анализируются; кандидаты Mixin не являются проверенными целями; диапазоны зависимостей не разрешаются. Это входные данные для аудита, не подтверждение совместимости.

Проверка на реальном инстансе: запуск оригинальной сборки; запуск с FrameScale без команд; CPU/GPU profiling; Photon 100/85/75/67%; сравнение кадров, p95, 1% low, FPS, VRAM и ошибок. Все рабочие моды, включая GPUTape, оставить включёнными. Если сторонние моды уже конфликтуют друг с другом, FrameScale не маскирует ошибку. Проверены независимые Java-классы и синтетические JAR; полная Gradle-сборка, точные API установленных модов и запуск Minecraft ещё не проверены.
