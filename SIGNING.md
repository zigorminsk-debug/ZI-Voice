# Подпись ZI-Voice — постоянный ключ (ОБЯЗАТЕЛЬНО К ПРОЧТЕНИЮ)

Приложение ZI-Voice подписывается **одним постоянным ключом**. Android считает
два APK одним приложением (и разрешает обновление поверх установленной версии)
только при совпадении подписи. Поэтому **этот ключ никогда не меняется и не
теряется** — иначе обновления поверх станут невозможны.

## Где лежит ключ

| Файл | Назначение |
|---|---|
| `signing/zi-voice.keystore` | keystore (PKCS12), alias `zi-voice` |
| `signing/keystore-password.txt` | пароль keystore и ключа (один и тот же) |
| `signing/zi-voice.keystore.b64` | копия keystore в base64 (для секретов CI / бэкапа) |

Параметры сертификата:
- DN: `CN=ZI-Voice, O=ZI-Voice, C=BY`
- RSA 2048, действует до 05.09.2076
- SHA-256 отпечаток: `96:0C:FF:48:9F:12:0C:6E:5E:55:1A:81:D7:50:8B:5F:1D:D7:75:43:3E:C4:48:5F:C7:10:CA:E4:3F:77:2F:0D`

## Правила

1. **Не генерировать новый ключ** для ZI-Voice. Никогда.
2. **Не пересоздавать** `zi-voice.keystore` и не менять пароль.
3. **Не коммитить изменения** в `signing/` без крайней необходимости.
4. Для каждого релиза **увеличивать `versionCode`** в `app/build.gradle.kts`
   (иначе установка поверх может не сработать у части установщиков).
5. Сменить ключ можно только при компрометации — и тогда придётся менять
   `applicationId` либо просить пользователей удалить старое приложение.

## Как подписывается CI

Каждый запуск workflow «Сборка APK» (`.github/workflows/android.yml`) подписывает
и debug, и release APK постоянным ключом. Источник ключа — по приоритету:

1. секреты репозитория: `APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`,
   `APK_KEY_ALIAS`, `APK_KEY_PASSWORD`;
2. если секретов нет — файлы `signing/zi-voice.keystore` +
   `signing/keystore-password.txt` из репозитория.

В логе шага «Подписать APK постоянным ключом» печатается отпечаток сертификата
(`apksigner verify --print-certs`) — сверьте его с SHA-256 выше, чтобы убедиться,
что использован именно постоянный ключ.

## Как подписать локально (например, другому агенту)

Нужен Android SDK (build-tools) или любой JDK с `apksigner`/`zipalign`:

```bash
PASS="$(cat signing/keystore-password.txt)"
zipalign -f -p 4 input.apk aligned.apk
apksigner sign \
  --ks signing/zi-voice.keystore \
  --ks-key-alias zi-voice \
  --ks-pass "pass:$PASS" \
  --key-pass "pass:$PASS" \
  --out output-signed.apk aligned.apk

# проверка: отпечаток должен совпасть с указанным выше
apksigner verify --print-certs output-signed.apk
```

Если Android SDK недоступен — просто запустите workflow «Сборка APK»
(вкладка Actions → Run workflow): он отдаёт уже подписанные артефакты
`ZI-Voice-debug` и `ZI-Voice-release`.

## Выпуск обновления (кратко)

1. Внести изменения, увеличить `versionCode` (и `versionName`) в `app/build.gradle.kts`.
2. Смержить PR в `main`.
3. Поставить тег: `git tag v0.1.1 && git push origin v0.1.1` —
   GitHub Release с подписанными APK создастся автоматически.

## Восстановление из base64

```bash
base64 -d signing/zi-voice.keystore.b64 > zi-voice.keystore
```
