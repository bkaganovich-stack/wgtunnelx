# Релизы WG Tunnel X: настройка и обслуживание

## Разовая настройка (≈15 минут)

### 1. Репозиторий

Создайте **обычный** публичный репозиторий `wgtunnelx` (кнопка *New repository*, без README и лицензии). Кнопкой *Fork* пользоваться не стоит: в форках публичных репозиториев расписания GitHub Actions по умолчанию выключены ([документация](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)), а вся автоматика держится на расписании.

```sh
git remote add origin https://github.com/<owner>/wgtunnelx.git
git push -u origin main
```

Если владелец не `bkaganovich-stack`, поменяйте `updateRepository` в `wgtunnelx.properties` — по нему приложение ищет обновления.

### 2. Права Actions

*Settings → Actions → General → Workflow permissions* → **Read and write permissions**. Без этого workflow не сможет пушить слияния, теги и создавать релизы.

### 3. Ключ подписи

Все релизы должны подписываться одним ключом, иначе Android не установит обновление поверх. Храните копию ключа и паролей вне GitHub.

```sh
keytool -genkeypair -v -keystore wgtunnelx.jks -alias wgtunnelx \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 wgtunnelx.jks > wgtunnelx.jks.b64     # macOS: base64 -i wgtunnelx.jks
```

*Settings → Secrets and variables → Actions → New repository secret*:

| Секрет | Значение |
| --- | --- |
| `KEYSTORE` | содержимое `wgtunnelx.jks.b64` |
| `SIGNING_STORE_PASSWORD` | пароль хранилища |
| `SIGNING_KEY_ALIAS` | `wgtunnelx` |
| `SIGNING_KEY_PASSWORD` | пароль ключа |

### 4. Токен для слияний (рекомендуется)

Когда апстрим меняет свои файлы в `.github/workflows`, GitHub не даёт `GITHUB_TOKEN` запушить такие коммиты (нужно право `workflows`). Создайте fine-grained токен: *Settings (профиля) → Developer settings → Fine-grained tokens*, доступ только к `wgtunnelx`, права **Contents: Read and write** и **Workflows: Read and write**. Сохраните его как секрет `SYNC_TOKEN`. Без токена слияния работают, пока апстрим не трогает свои воркфлоу.

### 5. Первый релиз

*Actions → x-release → Run workflow*, галочку *bump* снимите: получится `5.8.2-x.1`. Дальше всё идёт само.

## Как работает автоматика

1. `x-upstream-release` каждые 15 минут спрашивает `GET /repos/wgtunnel/android/releases/latest`. Черновики и пре-релизы этот эндпоинт не возвращает; дополнительно проверяется, что тег имеет вид `X.Y.Z`.
2. Если релиза форка для этой версии ещё нет, тег вливается в `main` (`scripts/upstream-sync.sh`):
   - удалённые в форке воркфлоу апстрима остаются удалёнными, новые — вычищаются;
   - в файлах с врезками форка берётся версия апстрима, врезки накладываются заново (`scripts/apply-hooks.py`);
   - `xRevision` сбрасывается в 1.
3. Тесты → проверка сгенерированных конфигов настоящим Xray-core с базами runetfreedom → подписанная сборка `standaloneRelease`.
4. Только после успешной сборки: push в `main`, тег `X.Y.Z-x.1`, релиз с APK (`arm64`, `armv7`, universal), `SHA256SUMS`, отпечатком сертификата и заметками апстрима. Релиз помечается как *latest*, и установленные копии предлагают обновиться.

Задержка от релиза апстрима — обычно 15–30 минут: GitHub может сдвигать запуски по расписанию под нагрузкой.

## Если что-то пошло не так

Workflow создаёт issue — его увидит владелец репозитория (уведомление на почту).

**«Upstream X.Y.Z needs a manual merge».** Апстрим изменил код, пересекающийся с форком, вне известных врезок.

```sh
git fetch https://github.com/wgtunnel/android.git tag X.Y.Z
git merge X.Y.Z                 # разрешить конфликты
python3 scripts/apply-hooks.py  # врезки на месте?
./gradlew :xray:testDebugUnitTest :app:assembleStandaloneDebug
git push
```

Затем *Actions → x-upstream-release → Run workflow* (поле тега можно оставить пустым).

**«WG Tunnel X build for upstream X.Y.Z failed».** Слияние прошло, сборка или тесты — нет. Чаще всего апстрим поменял API, которым пользуется `RoutingTunnelProvider`. Исправьте код в `main`, запустите workflow вручную. Если упала проверка конфигов Xray, смотрите лог шага *Validate generated configs*.

**Расписание перестало запускаться.** *Actions → x-upstream-release → Enable workflow*. Обычно до этого не доходит: workflow сам продлевает себе жизнь раз в сутки.

## Обновление Xray-core

`x-update-xray` раз в неделю проверяет релизы [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite). Новая версия коммитится в `main` (`xray/libxray.version` и `xray/libxray.sha256`) только если тесты и `xray run -test` прошли, и попадает в ближайший релиз. Выпустить её сразу — `x-release`.

## Релиз изменений форка

*Actions → x-release* с галочкой *bump*: `5.8.3-x.1` → `5.8.3-x.2`. Код версии растёт как `upstreamVersionCode × 100 + xRevision`, поэтому любые релизы форка обновляют друг друга корректно.
