# Стратегия ветвления и релизный процесс

[ English ](../en/branching-and-releases.md) • [ Русский ](branching-and-releases.md)

> Полное руководство по работе с ветками в Git, оформлению Pull Request, локальной проверке кода, автоматическому CI/CD и публикации релизных APK в GitHub Releases.
>
> Verified: 2026-08-28 — Проверено в `.github/workflows/`.

## Краткий обзор

В проекте используется модель **GitHub Flow**: создание короткоживущих веток от `main` (`feat/*`, `fix/*`, `docs/*`), прохождение локальных тестов и линтеров, открытие Pull Request и автоматическая сборка в CI. Релизы автоматически собираются, подписываются и публикуются в **GitHub Releases** при пуше тега версии (`v*.*.*`) или при ручном запуске пайплайна.

---

## 1. Модель ветвления (GitHub Flow)

```
        feat/new-logging ───○───○───┐ (PR)
                                    │
main ───○───────────────────────────●────────────────○ (v1.1.0 Release Tag)
         │                                          │
         └─── fix/subnet-scan ──────○───○───────────┘ (PR)
```

- **`main`**: Основная стабильная ветка репозитория. Прямые коммиты в `main` не рекомендуются. Код в `main` всегда должен быть компилируемым, протестированным и готовым к релизу.
- **Ветки разработки**:
  - `feat/<название>`: Новая функциональность (например, `feat/structured-logging`, `feat/host-key-pinning`).
  - `fix/<название>`: Исправление ошибок (например, `fix/subnet-cidr-range`, `fix/passfifo-race`).
  - `docs/<название>`: Обновление, уточнение и локализация документации.
  - `lab/<название>`: Изменения в Docker/QEMU тестовом стенде.

---

## 2. Пошаговый цикл разработки обновления

### Шаг 1. Актуализация `main` и создание ветки
Перед началом работы обязательно затяните свежие изменения из удалённого репозитория:
```sh
git checkout main
git pull origin main
```
Создайте тематическую ветку задачи:
```sh
git checkout -b feat/my-feature-name
```

### Шаг 2. Внесение изменений и правила оформления коммитов
Придерживайтесь формата **Conventional Commits** (на английском языке):
- `feat(android): implement 4-level structured logging system`
- `fix(android): dynamic CIDR subnet scan without 254-host cap`
- `docs(laptop-setup): update Wi-Fi PMF and firmware initramfs instructions`
- `build(android): upgrade gradle dependencies`

Держите коммиты атомарными: один логический шаг — один коммит.

### Шаг 3. Локальная проверка качества перед пушем (Mandatory)
Перед отправкой изменений на GitHub обязательно выполните локальные проверки:

```sh
# 1. Проверка линтера документации и синхронизации (EN/RU)
python3 scripts/check_docs.py

# 2. Запуск Android Unit-тестов
cd android && ./gradlew test && cd ..

# 3. Пробная локальная сборка отладочного APK
cd android && ./gradlew assembleDebug && cd ..
```

*Если вы изменили логику работы с initramfs, сетью или протоколом разблокировки — обновите соответствующие файлы документации в `docs/ru/` и `docs/en/` в этом же коммите, иначе `check_docs.py` заблокирует PR.*

### Шаг 4. Пуш ветки и открытие Pull Request
Отправьте вашу ветку в удалённый репозиторий:
```sh
git push -u origin feat/my-feature-name
```
Перейдите на GitHub (`https://github.com/DanyaNADAMU/nadamu-unlocker`) и создайте Pull Request в ветку `main`.

---

## 3. Автоматический CI/CD и проверка Pull Request

В репозитории настроены GitHub Actions, которые запускаются на каждый PR:

- **`docs-check.yml`**: Проверяет валидность всех ссылок, наличие меток верификации (`Verified: YYYY-MM-DD`) и обязательное обновление документации при изменении связанных файлов кода.
- **`android-build.yml`**: Срабатывает при изменении файлов в `android/**`. Запускает тесты на JDK 25 и собирает отладочный APK (`debug.apk`).

### Как посмотреть результаты сборки PR:
1. Внизу страницы Pull Request отображаются статусы проверок (Checks).
2. Нажмите **Details** напротив упавшей или выполняющейся проверки, чтобы увидеть полный консольный вывод Gradle или Python-линтера.

---

## 4. Публикация релизов и сборка Release APK

Релизный процесс полностью автоматизирован в воркфлоу `.github/workflows/release.yml`.

### Что входит в релиз:
1. Подписанный и оптимизированный релизный APK (`nadamu-unlocker-vX.Y.Z.apk`).
2. Контрольная сумма SHA-256 (`nadamu-unlocker-vX.Y.Z.apk.sha256`).
3. Автоматически сгенерированный список изменений (Release Notes / Changelog).

### Способ 1. Автоматический выпуск через Git Tag (Основной)
1. Убедитесь, что ветка `main` обновлена и содержит все необходимые изменения:
   ```sh
   git checkout main
   git pull origin main
   ```
2. Создайте аннотированный тег версии (Semantic Versioning):
   ```sh
   git tag -a v1.1.0 -m "Release v1.1.0: structured logging, host key pinning, fast CIDR discovery"
   ```
3. Отправьте тег на GitHub:
   ```sh
   git push origin v1.1.0
   ```
4. GitHub Actions автоматически запустит воркфлоу `Release APK`, соберет релизный бинарник и создаст релиз в разделе **Releases**.

### Способ 2. Ручной запуск через Web UI (`workflow_dispatch`)
1. Перейдите во вкладку **Actions** на GitHub (`https://github.com/DanyaNADAMU/nadamu-unlocker/actions`).
2. В левой колонке выберите воркфлоу **Release Android APK**.
3. Нажмите кнопку **Run workflow**.
4. Введите имя тега (например, `v1.1.0`), при необходимости отметьте галочку *Draft* или *Prerelease*, и нажмите зеленую кнопку **Run workflow**.

---

## 5. Как скачать и проверить готовый APK

После завершения релизного воркфлоу:
1. Откройте страницу релизов: `https://github.com/DanyaNADAMU/nadamu-unlocker/releases`.
2. В самом верхнем (последнем) релизе в секции **Assets** скачайте файл `nadamu-unlocker-vX.Y.Z.apk` на телефон.
3. При необходимости проверьте целостность файла по SHA-256:
   ```sh
   sha256sum nadamu-unlocker-vX.Y.Z.apk
   ```
   Хеш должен совпадать со значением в прикрепленном файле `.sha256`.
