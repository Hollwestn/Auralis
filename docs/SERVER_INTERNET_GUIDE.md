# Сервер Auralis для устройств в разных сетях

Практический вариант — постоянно включённый Linux VPS с публичным IP, собственный домен и HTTPS. Компьютер и телефон обращаются к одному адресу, например `https://music.your-domain.ru`, через домашний Wi-Fi, мобильную сеть или другую доступную интернет-сеть. Расстояние влияет на задержку, но не требует общей локальной сети. Музыка хранится на VPS; домашний ПК можно выключить после загрузки файлов.

Ниже используются условные `VPS_IP` и `music.your-domain.ru`: замените их на адрес своего сервера и поддомен своего зарегистрированного домена. На реальном VPS в этом окружении развёртывание не выполнялось; Docker/PostgreSQL/Caddy подготовлены для запуска пользователем. Проверки приложения и локального API перечислены в TEST_REPORT.md.

## 1. Что подготовить

- VPS: Ubuntu24.04 LTS x64, публичный IPv4; ориентир для личной библиотеки — 2 vCPU, 4GB RAM, от40GB SSD. Объём диска выбирайте с учётом музыки и копий.
- SSH-доступ: IP, имя пользователя и ключ/пароль от провайдера.
- Собственный домен и доступ к его DNS.
- Готовый `release/Auralis-Server-Internet.zip`: JAR, Dockerfile, compose.yaml, Caddyfile, .env.example, start-internet.sh и эта инструкция. Архив содержит только файлы поставки, без локальной базы, музыки и вашего .env. На VPS Java и Maven отдельно не нужны: JRE находится в контейнере.

Не размещайте публичный сервер на `localhost` телефона или на частном IP домашнего роутера. Этот сценарий использует VPS и не требует проброса портов домашнего роутера либо обхода CGNAT.

## 2. Привязать домен

В DNS-панели создайте запись типа A: имя `music`, значение — публичный IPv4 VPS. Получится `music.your-domain.ru`. AAAA добавляйте только при настроенном и доступном IPv6 этого VPS.

На Windows проверьте:

```powershell
Resolve-DnsName music.your-domain.ru -Type A
```

В ответе должен быть публичный IPv4 VPS. Если показан другой IP, исправьте DNS и дождитесь обновления записи.

## 3. Открыть доступ к VPS

В панели провайдера разрешите входящие TCP22 (либо ваш SSH-порт), TCP80 и TCP443. UDP443 необязателен для HTTP/3. База PostgreSQL5432 и внутренний API8080 не публикуются для клиентов.

Подключитесь из PowerShell:

```powershell
ssh root@VPS_IP
```

В примерах используется root свежего VPS. Если провайдер выдал пользователя `ubuntu`, подключайтесь как `ubuntu@VPS_IP` и выполняйте административные команды через sudo.

При использовании UFW сначала разрешите свой фактический SSH-порт, затем веб-порты. Для стандартного SSH22:

```sh
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

Не включайте UFW с правилом22, если SSH у вас работает на другом порту: используйте этот порт вместо22.

## 4. Установить Docker на свежую Ubuntu24.04

Команды сверены с [официальной установкой Docker Engine для Ubuntu](https://docs.docker.com/engine/install/ubuntu/). Если Docker уже установлен, сначала проверьте `docker version` и `docker compose version`; повторная установка не нужна.

```sh
sudo apt update
sudo apt install -y ca-certificates curl unzip openssl nano
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker compose version
sudo docker run --rm hello-world
```

Ожидается версия Compose и сообщение успешного запуска hello-world. Инструкция рассчитана на свежую Ubuntu24.04; для другой ОС используйте её официальный способ установки.

## 5. Передать серверные файлы с Windows

Откройте отдельное окно PowerShell в папке проекта. Выполните, заменив VPS_IP и SSH-логин:

```powershell
scp .\release\Auralis-Server-Internet.zip root@VPS_IP:/root/
```

В SSH-сессии:

```sh
sudo mkdir -p /opt/auralis
sudo unzip /root/Auralis-Server-Internet.zip -d /opt/auralis
cd /opt/auralis
ls -l auralis-server.jar compose.yaml Caddyfile Dockerfile start-internet.sh
```

Для непривилегированного SSH-пользователя отправьте архив в его домашний каталог и распакуйте оттуда через sudo. Команда `ls` должна показать все пять файлов. Используйте отдельный каталог для нового развёртывания. Если unzip спрашивает о замене файлов, проверьте, не выбрали ли вы каталог существующего сервера.

## 6. Настроить домен и базу

На VPS в `/opt/auralis`:

```sh
sudo cp -n .env.example .env
sudo chmod 600 .env
openssl rand -hex 32
sudo nano .env
```

Последняя команда генерации выдаёт случайный пароль. Впишите его в .env вместо примера. Итоговые основные строки:

```dotenv
AURALIS_DB_PASSWORD=СЮДА_СГЕНЕРИРОВАННЫЙ_ПАРОЛЬ
AURALIS_HTTP_BIND=127.0.0.1
AURALIS_DOMAIN=music.your-domain.ru
AURALIS_MAX_USER_BYTES=21474836480
AURALIS_IMPORT_ROOT=
AURALIS_IMPORT_ADMIN_USER_ID=
```

У домена не должно быть `https://`, порта или пути. Для кириллического домена используйте ASCII/Punycode-имя из DNS-панели. Сохраните nano: Ctrl+O, Enter, Ctrl+X. Пароль базы относится к PostgreSQL, а пароль музыкального аккаунта создаётся отдельно в приложении. Лимит по умолчанию — 20GiB на аккаунт; при необходимости измените число байт до загрузки большой коллекции.

Для существующего развёртывания не заменяйте пароль PostgreSQL в .env произвольно: уже созданная база сохраняет прежний пароль. Сначала выполните согласованную смену учётных данных самой БД.

## 7. Запустить PostgreSQL, API и HTTPS

```sh
cd /opt/auralis
sudo sh ./start-internet.sh
```

Скрипт проверяет заполненный домен, пароль и локальную привязку API, проверяет Compose и запускает три сервиса. Нужные папки создаются автоматически. Первый запуск скачивает образы и собирает Java-контейнер.

Ожидаемые сервисы: db (healthy), api (running), https (running). Проверка:

```sh
sudo docker compose --profile https ps
curl -fsS http://127.0.0.1:8080/api/v1/health
curl -fsS https://music.your-domain.ru/api/v1/health
```

Ожидается JSON со `status: ok`. На старте Java и получение сертификата могут занять некоторое время; повторите health после запуска контейнеров. [Caddy автоматически получает HTTPS-сертификат](https://caddyserver.com/docs/automatic-https), когда DNS указывает на сервер и нужные порты доступны. Сертификаты сохраняются в volume caddy_data.

Если что-то не запустилось:

```sh
sudo docker compose logs --tail 100 db api https
```

Не удаляйте volumes для исправления ошибки.

## 8. Проверить с внешней сети

С Windows:

```powershell
Invoke-RestMethod https://music.your-domain.ru/api/v1/health
```

На телефоне отключите Wi-Fi, оставьте мобильный интернет и откройте тот же HTTPS-адрес в браузере. Если health открывается в обеих сетях, сервер доступен извне. При закрытии SSH и выключении домашнего ПК контейнеры на VPS продолжают работать. Политика `restart: unless-stopped` восстанавливает их после перезагрузки VPS.

## 9. Подключить Desktop и телефон

В обоих клиентах укажите один базовый URL:

```text
https://music.your-domain.ru
```

Не добавляйте `/api/v1`, :8080 или :5432. Desktop: Настройки → серверная коллекция; создайте аккаунт или войдите. На телефоне войдите тем же email и паролем. Музыкальный аккаунт не является SSH-пользователем или пользователем PostgreSQL.

Другой аккаунт получает свою отдельную коллекцию. Для общей вашей библиотеки все ваши устройства используют один аккаунт.

## 10. Загрузить музыку и проверить синхронизацию

На Desktop в серверной коллекции нажмите «Загрузить файлы» и выберите собственные треки. Дождитесь завершения. На телефоне потяните библиотеку вниз, запустите трек. Простое сканирование локальной папки ПК не отправляет файлы на VPS.

Проверьте по порядку: потоковое воспроизведение на телефоне через мобильную сеть; плейлист и лайк; обновление серверной коллекции на ПК; сохранение позиции/очереди на одном устройстве и «Продолжить» на другом. Для Desktop используются явные кнопки синхронизации, push-синхронизация пока отсутствует. Подробнее — SERVER_SETUP.md.

«Моя волна» выбирает музыку вашего серверного каталога. Добавьте несколько треков, лайки и предпочтения, затем запустите волну на телефоне. Внешняя подписка не нужна. Для офлайна заранее скачайте музыку через меню трека на телефоне.

## 11. Данные, копии и обновление

PostgreSQL хранится в volume postgres_data, музыка — music_data, сертификаты — caddy_data. Они сохраняются при обычном `docker compose up --build -d` и перезапуске контейнеров. Команда `docker compose down -v` удаляет volumes: не используйте её для обычного обновления.

Согласованная резервная копия на VPS:

```sh
cd /opt/auralis
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
sudo docker compose stop api
sudo docker compose exec -T --user 0 db pg_dump -U auralis -d auralis -Fc -f "/backups/database-$STAMP.dump"
sudo docker compose run --rm --no-deps --user 0 --entrypoint tar -v "$PWD/backups:/backups" api -czf "/backups/music-$STAMP.tar.gz" -C /app/data .
sudo docker compose start api
```

Запускайте команды по одной и проверяйте завершение. Если pg_dump/tar не прошёл, сохраните сообщение и верните API командой `sudo docker compose start api`. Скопируйте оба архива на другой диск/хост, сохраните .env отдельно. Восстановление описано в SERVER_SETUP.md; оно выполняется в выбранную целевую базу и требует отдельного внимания к данным.

Для обновления сначала сделайте копию, передайте новый JAR в каталог сервера и выполните `sudo docker compose --profile https up --build -d api`, затем проверьте HTTPS health и трек.

## Диагностика

| Симптом | Проверка |
|---|---|
| Домен не открывается | DNS A/AAAA, IP VPS, правила провайдера и UFW, порты80/443 |
| HTTPS не появился | `docker compose logs --tail 100 https`; DNS и доступность портов; для действительного публичного домена не нужен самоподписанный сертификат |
| 502 Bad Gateway | `docker compose logs --tail 100 api db`; дождитесь старта API, проверьте пароль базы и свободное место |
| Health работает, музыка пустая | Тот ли аккаунт; файлы действительно загружены серверу, а не только импортированы локально |
| 401 | Войти заново тем же аккаунтом; токен мог быть отозван |
| Загрузка413 | Серверный лимит300MB на файл; проверьте размер |
| Работает по Wi-Fi, не работает по мобильной сети | В приложении должен быть публичный HTTPS-домен, а не частный IP; проверьте этот URL в браузере телефона |

Работа из любой точки предполагает доступный интернет и доступность домена из выбранной сети. Сетевые блокировки, отсутствие связи и остановленный VPS приложение устранить не может.
