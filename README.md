# s3-build-cache

Settings-плагин Gradle: удалённый build cache поверх любого S3-совместимого хранилища (AWS S3, Cloudflare R2, MinIO). Клиент — AWS SDK v2.
Записи хранятся как объекты `<prefix><hash ключа кэша>`. Если хранилище недоступно, Gradle отключает удалённый кэш до конца сборки, сборка не падает.

## Подключение
1. В `gradle.properties`: `org.gradle.caching=true`.
2. В `settings.gradle.kts` примените плагин `dev.pyrlyn.s3-build-cache` и настройте `remote<S3BuildCache>` (см. `example/settings.gradle.kts`).
3. Чтение — `isPull` (по умолчанию `true`), запись — `isPush`. Обычно пушит только CI: `isPush = System.getenv("CI") != null`.
4. Ключи передавайте через переменные окружения, не коммитьте их.

## Параметры
| Параметр | По умолчанию | Описание |
|---|---|---|
| `endpoint` | `null` (AWS) | URL S3-совместимого сервера |
| `bucket` | — | обязателен |
| `region` | `us-east-1` | `auto` для R2 |
| `accessKeyId` / `secretAccessKey` | `null` | без них — стандартная цепочка AWS |
| `pathStyle` | `false` | `true` для MinIO и R2 |
| `prefix` | `gradle-cache/` | общий неймспейс записей |
| `isPull` / `isPush` | `true` / `false` | чтение / запись |
| `maxConnections` | `50` | пул HTTP-соединений (Apache client) |
| `connectionTimeoutMs` / `socketTimeoutMs` | `10000` / `60000` | таймауты соединения |
| `connectionTimeToLiveMs` | `60000` | время жизни соединения в пуле |
| `apiCallTimeoutMs` / `apiCallAttemptTimeoutMs` | `300000` / `120000` | общий таймаут вызова / одной попытки; ретраи — режим STANDARD |
| `requestChecksumCalculation` | `WHEN_SUPPORTED` | `WHEN_REQUIRED` для старых S3-совместимых серверов |
| `multipartThresholdBytes` | 16 MiB | с этого размера — multipart upload |
| `multipartPartSizeBytes` | 8 MiB | размер части, минимум 5 MiB |
| `maxSizeBytes` / `maxSizeMb` | `null` | лимит размера префикса, `null` — без вытеснения |
| `lruRefreshAfterMs` | 1 ч | см. «Вытеснение» |

## Как работает
- Ключ объекта — только `<prefix><хеш ключа Gradle>`, без имени проекта и путей (чистая content-addressed схема).
- Загрузка — один `GET` без `HEAD`, 404 = промах. Запись — `PUT` без предварительного `HEAD`.
- Записи от 16 MiB грузятся multipart-ом: сначала во временный файл (в `java.io.tmpdir` демона Gradle), затем по частям; при ошибке upload отменяется (`AbortMultipartUpload`).
- Ответы проверяются по контрольной сумме, если сервер её отдаёт (`WHEN_SUPPORTED`). Для запросов CRC32 считается по умолчанию; если старый сервер его не понимает, поставьте `requestChecksumCalculation = "WHEN_REQUIRED"`. На MinIO проверены оба режима.

## Общий кэш для нескольких проектов
Укажите одинаковые `bucket` и `prefix` в обоих проектах:

```kotlin
// project-a/settings.gradle.kts и project-b/settings.gradle.kts
buildCache {
    remote<S3BuildCache> {
        endpoint = "https://<ACCOUNT_ID>.r2.cloudflarestorage.com"
        bucket = "gradle-cache"
        region = "auto"
        pathStyle = true
        prefix = "shared/"   // один и тот же префикс
        isPush = System.getenv("CI") != null
    }
}
```

Совпадающие задачи с одинаковыми входами получат один и тот же ключ, и кэш будет общим. Разные версии Gradle, плагинов или JDK меняют входы задач, поэтому хеши разойдутся сами — коллизий не будет, отдельный префикс для этого не нужен. Отдельный префикс нужен только если вы хотите раздельные квоты или чистку.

## AWS S3
- `endpoint` не задавать, `region` = регион бакета (например `eu-central-1`), `pathStyle = false`.
- Без ключей используется стандартная цепочка AWS (env `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, профиль, роль).
- Права IAM: `s3:GetObject`, `s3:PutObject` на `arn:aws:s3:::<bucket>/<prefix>*`.

## Cloudflare R2
- `endpoint = "https://<ACCOUNT_ID>.r2.cloudflarestorage.com"`, `region = "auto"`, `pathStyle = true`.
- Ключи: R2 → Manage API Tokens → токен с правом Object Read & Write на бакет.

## MinIO
- `endpoint = "http://host:9000"`, `region = "us-east-1"`, `pathStyle = true`.

## Вытеснение (опционально)
`maxSizeMb = 5000` (или `maxSizeBytes`) включает вытеснение: после каждой успешной записи плагин перечисляет объекты под префиксом (`ListObjectsV2` с пагинацией), суммирует размеры и удаляет самые старые по `LastModified` (`DeleteObjects` пачками до 1000), пока не уложится в лимит.

В S3 нет времени доступа, поэтому LRU приблизительный: при попадании, если запись старше `lruRefreshAfterMs` (по умолчанию 1 ч), плагин обновляет её `LastModified` копированием объекта на себя (`CopyObject`, `REPLACE`). Порог ограничивает число лишних запросов.

Ограничения:
- Листинг на каждую запись стоит запросов; на больших префиксах (сотни тысяч объектов) лучше lifecycle-правило.
- Параллельные сборки могут гоняться: кратковременно превысить лимит или удалить запись, которую кто-то только что прочитал. Для кэша это безопасно, будет просто промах.
- Ошибки вытеснения и обновления не роняют сборку, пишется предупреждение.

Нужны права `s3:ListBucket` и `s3:DeleteObject` (на R2 — Object Read & Write).

## Очистка без плагина
Без `maxSizeBytes` плагин ничего не удаляет. Настройте lifecycle-правило бакета (S3 и R2 его поддерживают), например удалять объекты с префиксом старше 30 дней.

## Сборка
```
./gradlew build                 # компиляция и unit-тесты (MockK + фейковый S3 in-process)
./gradlew e2eTest               # e2e против настоящего MinIO (Testcontainers); без Docker тесты пропускаются
./gradlew publishToMavenLocal   # локальная публикация
cd example && ../gradlew help   # проверка подключения плагина
```

E2E по умолчанию использует образ `cgr.dev/chainguard/minio:latest` (официальные образы `minio/minio` больше не скачиваются с Docker Hub). Другой образ: `-De2e.minioImage=...` или `MINIO_IMAGE`. Для Colima сокет Docker подхватывается автоматически.
