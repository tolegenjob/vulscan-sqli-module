# VulScan SQLi module

Модуль обнаружения SQL-инъекций. Читает задания из Kafka (`scan.jobs.sqli.v1`), результаты публикует в `scan.job-events.v1`, состояние хранит Core.

Главный метод — сравнение ответов с базовым уровнем (baseline) для пары контрольных значений. Сами по себе HTTP 500, изменение длины, единичная задержка или слово «sql» в теле доказательством не считаются. Пробы не имеют побочных эффектов: нет стековых запросов, `UNION`, `DROP/INSERT/UPDATE/DELETE`, `OR`-тавтологий.

Проверяются только endpoint с `idempotent=true`; остальные пропускаются с причиной `NOT_IDEMPOTENT`.

## Сборка

```
git clone https://github.com/tolegenjob/vulscan-kit && (cd vulscan-kit && ./gradlew publishToMavenLocal -PkitVersion=0.3.0 -x test)
./gradlew build installDist
docker build -t vulscan-sqli-module .
```

## Переменные окружения

| Переменная | По умолчанию |
|---|---|
| `WORKER_CORE_BASE_URL` | обязательна |
| `WORKER_SERVICE_TOKEN` | обязательна |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` |
| `WORKER_CONSUMER_GROUP` | `sqli-module` |
| `WORKER_MAX_CONCURRENT_JOBS` | `2` |
| `WORKER_ALLOWED_PRIVATE_CIDRS` | пусто (через запятую) |
| `SQLI_MAX_PARAMS_PER_ENDPOINT` | `10` |
| `SQLI_MAX_TIME_PROBES_PER_JOB` | `20` |
| `SQLI_TIME_DELAY_SEC` / `SQLI_TIME_DELAY_MAX_SEC` | `3` / `5` |
| `SQLI_BASELINE_SAMPLES` | `3` |
| `SQLI_LATENCY_SAMPLES` | `5` |
| `HEALTH_PORT` | `8081` |
