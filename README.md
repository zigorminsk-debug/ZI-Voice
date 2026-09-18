# ZI-Voice

Тренировка попадания в ноты (Android).

Приложение слушает голос через микрофон, определяет высоту тона (алгоритм YIN)
и показывает, насколько точно вы попали в выбранную ноту: индикатор в центах,
зелёная зона ±10 центов, подсказки «ниже/выше цели».

## Сборка APK через GitHub Actions

Сборка полностью автоматизирована — workflow **«Сборка APK»** (`.github/workflows/android.yml`).

### Как получить APK

1. Откройте вкладку **Actions** в репозитории.
2. Выберите workflow **«Сборка APK»** → **Run workflow** (ручной запуск) —
   или просто сделайте push/PR в `main`, сборка запустится сама.
3. Дождитесь завершения задания **Сборка APK**.
4. Внизу страницы запуска, в разделе **Artifacts**, скачайте:
   - **ZI-Voice-debug** — debug-сборка, устанавливается на любое устройство;
   - **ZI-Voice-release** — релизная сборка (без подписи, если не настроены секреты).

### Автоматический Release

Достаточно поставить тег — Release с APK создастся сам:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

На странице **Releases** появятся оба APK.

### Локальная сборка

Нужен JDK 17 (Android SDK Gradle скачает/найдёт сам через `ANDROID_HOME`):

```bash
./gradlew assembleDebug
# результат: app/build/outputs/apk/debug/app-debug.apk
```

### Подпись релизных APK (опционально)

Чтобы релизный APK был сразу подписан, добавьте 4 секрета репозитория
(**Settings → Secrets and variables → Actions**):

| Секрет | Значение |
|---|---|
| `APK_KEYSTORE_BASE64` | keystore в base64 |
| `APK_KEYSTORE_PASSWORD` | пароль keystore |
| `APK_KEY_ALIAS` | алиас ключа |
| `APK_KEY_PASSWORD` | пароль ключа |

Создать keystore и получить base64:

```bash
keytool -genkeypair -v -keystore zi-voice.keystore -alias zi-voice \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 zi-voice.keystore > zi-voice.keystore.b64
```

Если секреты не заданы — релизный APK просто собирается неподписанным, сборка не падает.

## Технологии

- Kotlin 2.0, AGP 8.6.1, Gradle 8.9 (wrapper), JDK 17
- minSdk 24, targetSdk/compileSdk 35
- Без внешних зависимостей: `AudioRecord` + детектор тона YIN на чистом Kotlin

## Структура

```
.github/workflows/android.yml   — CI: сборка APK, артефакты, Release по тегу
app/src/main/.../MainActivity.kt — экран тренажёра и детектор высоты тона
gradlew + gradle/wrapper        — Gradle wrapper 8.9
```
