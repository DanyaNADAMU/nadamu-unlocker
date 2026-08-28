# Стратегия ветвления и релизный процесс

[ English ](../en/branching-and-releases.md) • [ Русский ](branching-and-releases.md)

> Правила работы с ветками в Git, оформление Pull Request, автоматические проверки CI и публикация подписанных APK в GitHub Releases.
>
> Verified: 2026-08-28 — Проверено в `.github/workflows/`.

## Краткий обзор

Используется модель **GitHub Flow**: создание веток от `main` (`feat/*`, `fix/*`, `docs/*`), открытие Pull Request и слияние после успешного прохождения CI. Релизы автоматически публикуются в GitHub Releases при создании тегов версий (`v*.*.*`) или вручную через `workflow_dispatch`.

---

## 1. Модель ветвления (GitHub Flow)

```
        feat/new-ui  ───○───○───┐ (PR)
                                 │
main ───○───────────────────────●────────────────○ (v1.0.0 Release Tag)
         │                                       │
         └─── fix/ssh-timeout ──○───○────────────┘ (PR)
```

- **`main`**: Основная стабильная ветка. Прямые коммиты в `main` не рекомендуются. Код в `main` всегда должен быть компилируемым и проходить все тесты.
- **Ветки разработки**:
  - `feat/<описание>`: Новая функциональность (например, `feat/biometric-prompt`).
  - `fix/<описание>`: Исправление ошибок (например, `fix/subnet-cidr-scan`).
  - `docs/<описание>`: Обновление и локализация документации.
  - `lab/<описание>`: Изменения в тестовом стенде.

---

## 2. Рабочий процесс (Pull Request)

### Пошаговый процесс

1. Обновите локальную ветку `main`:
   ```sh
   git checkout main
   git pull origin main
   ```
2. Создайте ветку задачи:
   ```sh
   git checkout -b feat/my-feature
   ```
3. Внесите изменения и выполните локальные проверки:
   ```sh
   # 1. Проверка документации и ссылок
   python3 scripts/check_docs.py

   # 2. Юнит-тесты Android (если затронута папка android/)
   cd android && ./gradlew test && cd ..
   ```
4. Закоммитьте изменения:
   ```sh
   git add <файлы>
   git commit -m "feat(android): add qr code scanner for identity keys"
   ```
5. Отправьте ветку на GitHub и откройте Pull Request:
   ```sh
   git push -u origin feat/my-feature
   ```

### Фильтрация путей в CI

- **`docs-check.yml`**: Запускает проверку документации на всех PR.
- **`android-build.yml`**: Собирает Android-проект **только** если затронуты файлы в `android/**` или `.github/workflows/android-build.yml`.

---

## 3. Публикация релизов (APK)

Релизы публикуются в **GitHub Releases** со следующими артефактами:
1. Подписанный релизный APK (`nadamu-unlocker-vX.Y.Z.apk`).
2. Файл контрольной суммы SHA-256 (`nadamu-unlocker-vX.Y.Z.apk.sha256`).
3. Автоматический список изменений (Changelog).

### Выпуск релиза через Git Tag

1. Убедитесь, что ветка `main` обновлена:
   ```sh
   git checkout main
   git pull origin main
   ```
2. Создайте и отправьте тег версии:
   ```sh
   git tag -a v1.0.0 -m "Release v1.0.0"
   git push origin v1.0.0
   ```
3. GitHub Actions workflow `.github/workflows/release.yml` автоматически соберёт, подпишет и опубликует релиз.
