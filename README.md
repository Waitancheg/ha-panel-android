# ha-panel-android

HA Panel (`net.waitancheg.hakiosk`, версия 1.5, versionCode 6) — небольшой лаунчер и киоск для настенной панели Home Assistant. Целевое устройство: ASUS Fonepad 7 (K019), Android 5.0.1, x86. minSdk 21, targetSdk 22.

Приложение написано на чистом Java и API платформы Android, без сторонних библиотек и без Gradle. Сборка: `./build.sh` → `out/hakiosk.apk`.

## Что делает приложение

- Полноэкранный WebView с Home Assistant (адрес по умолчанию — заглушка `http://homeassistant.example:8123`; свой адрес задаётся в настройках). Вход и сессия — штатные, через сам HA.
- Надпись «Нет связи, повтор…» и повтор каждые 15 с, плюс перезагрузка страницы, когда сеть возвращается. Кнопка «Назад» ходит по истории WebView, из приложения она не выходит.
- Заставка по простою (по умолчанию 5 минут): фото из локальных медиа HA, папка `/media/<folder>` (по умолчанию `slideshow`), перекрёстное затухание каждые 15 с.
  Фото берутся через уже выполненную в WebView сессию HA
  (`document.querySelector('home-assistant').hass.callWS`: browse_media / resolve_media → подписанный URL → скачивание и декодирование на стороне приложения).
  Необязательный долгосрочный токен в настройках включает запасной путь: встроенный WebSocket-клиент (WsClient / HaMedia).
  Если фото нет или HA недоступен, показываются тусклые часы, которые немного сдвигаются каждую минуту.
- Скрытые настройки: удерживать левый верхний угол (80 dp) 3 с или нажать его 5 раз за 3 с, затем ввести PIN (по умолчанию 1234).
- Pull-to-refresh: потянуть вниз от верхнего края (или явно вертикальный жест), когда страница прокручена в самый верх; после протяжки на 120 dp WebView перезагружается. Круговой индикатор следует за пальцем. Положение прокрутки читается из shadow DOM HA, а не из `WebView.getScrollY()`.
- Ночной режим (v1.4): датчик освещённости; освещённость ниже порога темноты (по умолчанию 3 лк) в течение 30 с → яркость окна 0 и чёрная панель на весь экран (экран не гаснет, `FLAG_KEEP_SCREEN_ON`). Касание только убирает панель (жест в HA не передаётся) на 60 с после последнего касания; освещённость выше max(порог+7, порог×3) (10 лк) в течение 3 с → обычный режим. В настройках: вкл/выкл, порог темноты, текущие люксы. Поминутный журнал: `/sdcard/Android/data/net.waitancheg.hakiosk/files/night_lux.log`. Проверочный запуск: `adb shell am start -n net.waitancheg.hakiosk/.MainActivity --es night_sim 0` (или `off`).
- Заставка и ночной режим (v1.5): таймер простоя (в настройках, минуты; по умолчанию 5) взводится заново от каждого касания и клавиши, от возврата на экран и от конца ночного режима. Пока в комнате темно (чёрная панель или 60 с после касания), заставка не запускается и останавливается, если темнеет. В logcat, тег `HAPanel`: `resumed; slideshow after N s idle`, `idle ... -> slideshow`, `slideshow start/stop`. Проверочный запуск (только в памяти, не сохраняется): `adb shell am start -n net.waitancheg.hakiosk/.MainActivity --ei idle_test_sec 20` (`0` — снова значение из настроек). Экран настроек не гаснет.
- Стартует после загрузки (`BOOT_COMPLETED`) и является домашним приложением (HOME).

## Требования для сборки

- Bash и Python 3 (скрипт дописывает `classes.dex` в APK через `zipfile`).
- JDK 17. Скрипт подставляет `JAVA_HOME=$HOME/tools/jdk-17.0.21+9`, если переменная не задана. Исходники компилируются как Java 8 (`-source 8 -target 8`). `d8` из build-tools 34.0.0 падает на class-файлах от JDK 21, даже с `-target 8`, поэтому для сборки нужен именно JDK 17 (`JAVA_HOME`).
- Android SDK с `build-tools/34.0.0` (aapt2, d8, zipalign, apksigner, `core-lambda-stubs.jar`) и `platforms/android-34/android.jar`. Каталог SDK — `ANDROID_SDK`, по умолчанию `$HOME/tools/android-sdk`.
- Для установки на планшет: `adb` и включённая отладка по USB. Планшет — Android 5.0+ (проверено на K019, Android 5.0.1).

## Сборка

Keystore и пароль в репозиторий не кладутся. Перед сборкой укажите уже существующий ключ владельца (см. ниже) и при необходимости SDK:

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_SDK=$HOME/tools/android-sdk
export HAKIOSK_KEYSTORE=$HOME/.config/hakiosk/keystore.jks
export HAKIOSK_KEYSTORE_PASS='пароль-от-keystore'
./build.sh
```

Готовый пакет: `out/hakiosk.apk`. Каталоги `build/` и `out/` в git не входят.

Если keystore или пароль не найдены, скрипт останавливается с ошибкой и APK не подписывает. Новый ключ он не создаёт: свежий ключ не подойдёт для обновления уже установленного приложения.

## Подпись существующим keystore

Обновление ставится поверх установленной HA Panel только с тем же ключом, которым подписана версия на планшете. Другой ключ `adb` отвергнет (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); удаление пакета сотрёт настройки и сессию HA на устройстве.

Ключ, которым подписывал прежний `build.sh`, имеет alias `hakiosk`. Если alias другой, задайте `HAKIOSK_KEY_ALIAS`.

Скрипт ищет подпись так:

| Что | Откуда, по порядку |
| --- | --- |
| Файл keystore | `HAKIOSK_KEYSTORE`, иначе `$XDG_CONFIG_HOME/hakiosk/keystore.jks` или `~/.config/hakiosk/keystore.jks`, иначе `~/.hakiosk/keystore.jks` |
| Пароль | `HAKIOSK_KEYSTORE_PASS`, иначе файл `HAKIOSK_KEYSTORE_PASS_FILE`, иначе `~/.config/hakiosk/keystore.pass` или `~/.hakiosk/keystore.pass` |
| Alias | `HAKIOSK_KEY_ALIAS`, иначе `hakiosk` |

Путь внутри этого репозитория скрипт не принимает. Пароль в `ps` не попадает: в `apksigner` он передаётся как `env:` или `file:`. В файле пароля берётся первая строка.

Положить уже имеющийся keystore рядом с домашним каталогом, не в git:

```bash
mkdir -p ~/.config/hakiosk
install -m 600 /путь/к/keystore.jks ~/.config/hakiosk/keystore.jks
printf '%s\n' 'пароль-от-keystore' > ~/.config/hakiosk/keystore.pass
chmod 600 ~/.config/hakiosk/keystore.pass
./build.sh
```

## Установка через adb

```bash
adb devices
adb install -r out/hakiosk.apk
```

`-r` заменяет пакет и сохраняет данные (адрес, PIN, сессию WebView), если подпись совпала. Запуск вручную:

```bash
adb shell am start -n net.waitancheg.hakiosk/.MainActivity
```

После установки выберите HA Panel домашним экраном. Приложение само регистрируется как HOME и дополнительно стартует по `BOOT_COMPLETED`.

## Настройки

Левый верхний угол экрана (квадрат 80 dp): удержать 3 секунды или нажать 5 раз в течение 3 секунд. Появится запрос PIN. PIN по умолчанию — `1234`; его стоит сменить в настройках (минимум 4 цифры).

На экране настроек: адрес Home Assistant, необязательный долгосрочный токен (только если без него фото заставки не грузятся), время до заставки, интервал смены фото, папка в «Локальных медиа» HA (`/media/…`), PIN, ночной режим. Кнопки открывают Wi-Fi и системные настройки Android, позволяют выбрать другой домашний экран и сбросить вход в HA.

Фото для заставки: файлы JPG/PNG в папке `/media/slideshow` (или в той, что указана в настройках) на сервере Home Assistant, раздел «Медиа → Локальные медиа».

На планшете, где адрес уже сохранён, замена значения по умолчанию в исходниках ничего не меняет: приложение читает сохранённые настройки.

## Тесты

`test/mock_ha.py` — имитация WebSocket и HTTP Home Assistant и поддельная страница фронтенда. `test/HaMediaTest.java` — JVM-тест с `org.json`.
