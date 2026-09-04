# Руководство по настройке ноутбука (Целевой машины)

[ English ](../en/laptop-setup.md) • [ Русский ](laptop-setup.md)

> Полное практическое руководство по настройке ноутбуков на базе Kali Linux / Debian / Ubuntu.
> Настройка `dropbear-initramfs`, необходимых модулей ядра, Wi-Fi хуков и параметров для удалённой разблокировки LUKS.
>
> Verified: 2026-08-28 (проверено тестовым стендом lab test_unlock.py и QEMU multi-transport harness)

---

## 1. Архитектура: Жизненный цикл загрузки ОС

При включении зашифрованного компьютера или выходе из гибернации происходит следующая последовательность:

```
[ Включение питания / Пробуждение из Swap ]
                     │
                     ▼
         [ UEFI / BIOS / GRUB ]
                     │
                     ▼
          [ Загрузка ядра Linux ]
                     │
                     ▼
            [ Стадия Initramfs ] ──► Загружаются сетевые драйверы (USB, Ethernet, Wi-Fi)
                     │          ──► Поднимается сеть по DHCP (IP=dhcp / udhcpc)
                     │          ──► Стартует SSH-сервер Dropbear на порту 22 (только по ключам)
                     │          ──► Создаётся именованный канал (FIFO): /lib/cryptsetup/passfifo
                     │          ──► cryptsetup ждёт пароль из passfifo или с клавиатуры
                     │
                     ▼  ◄── Телефон подключается по SSH, пишет пароль в passfifo
      [ LUKS-том расшифрован ]  ──► Появляется блочное устройство /dev/mapper/<target>
                     │
                     ▼
        [ Pivot_root в ОС ]    ──► Сеть initramfs и Dropbear штатно гасятся
                     │          ──► Монтируется зашифрованный корень, systemd грузит систему
                     ▼
         [ Экран входа / Рабочий стол ]
```

### Зачем нужен Dropbear в initramfs?
- На этапе предзагрузки реальный корень зашифрован. Стандартные системные службы (`sshd`, `systemd`, `NetworkManager`) ещё физически недоступны.
- `dropbear-initramfs` — это компактный автономный SSH-сервер, который встраивается прямо во временный образ `initramfs` в оперативной памяти.
- Ввод пароля реализован через FIFO (пайп) `/lib/cryptsetup/passfifo`. Когда `cryptsetup` запрашивает пароль диска, любая корректная строка, записанная в этот канал, разблокирует LUKS-том без физической клавиатуры.

---

## 2. Необходимые пакеты и их назначение

| Пакет | Назначение в Initramfs |
|---|---|
| `dropbear-initramfs` | Встраиваемый SSH-сервер для ранней стадии загрузки. |
| `cryptsetup-initramfs` | Хуки и скрипты для разблокировки LUKS-дисков и создания канала `passfifo`. |
| `busybox` | Набор базовых UNIX-утилит (`sh`, `udhcpc`, `ip`, `printf`, `test`) с минимальным размером. |
| `wpasupplicant` | WPA/WPA2-клиент для подключения к Wi-Fi на этапе initramfs (Режимы A3/A4). |
| `rfkill` | Утилита для снятия программной и аппаратной блокировки беспроводных модулей. |
| `iw` | Утилита для настройки и проверки беспроводных интерфейсов. |

Установка всех зависимостей:
```sh
sudo apt-get update
sudo apt-get install -y dropbear-initramfs cryptsetup-initramfs busybox wpasupplicant rfkill iw
```

---

## 3. Настройка локальных режимов (A1, A2, A3, A4)

### Режим A1: USB-модем (Телефон ⇄ USB-кабель ⇄ Ноутбук)

**Суть режима:** Ноутбук подключается к телефону обычным кабелем Type-C. На телефоне включается тумблер **«USB-модем» (USB Tethering)**. Телефон выступает в роли роутера и DHCP-сервера для ноутбука.

#### Шаг 1. Добавление сетевых модулей ядра
Откройте `/etc/initramfs-tools/modules` и добавьте драйверы USB-сети:
```conf
rndis_host
cdc_ether
cdc_ncm
usbnet
```
*Пояснение:* `rndis_host` отвечает за RNDIS-модемы Android; `cdc_ether` и `cdc_ncm` поддерживают стандартные CDC USB-Ethernet адаптеры.

#### Шаг 2. Включение сети и Dropbear в initramfs
В файле `/etc/initramfs-tools/initramfs.conf` укажите:
```conf
DROPBEAR=y
IP=dhcp
```

#### Шаг 3. Настройка безопасности Dropbear (Только вход по ключам)
В файле `/etc/dropbear-initramfs/config` (или `/etc/dropbear/initramfs/dropbear.conf`):
```conf
DROPBEAR_OPTIONS="-p 22 -s -j -k -E"
```
*Разбор флагов:*
- `-p 22`: Слушать порт 22.
- `-s`: Запретить вход по паролю (разрешить только SSH-ключи).
- `-j`: Запретить локальный проброс портов (local port forwarding).
- `-k`: Запретить удалённый проброс портов (remote port forwarding).
- `-E`: Направлять логи ошибок в стандартный поток вывода / syslog.

#### Шаг 4. Добавление публичного ключа клиента
Скопируйте строку публичного ключа из Android-приложения Nadamu и добавьте в `/etc/dropbear/initramfs/authorized_keys` (или `/etc/dropbear-initramfs/authorized_keys`):
```sh
sudo chmod 600 /etc/dropbear/initramfs/authorized_keys
```

#### Шаг 5. Пересборка initramfs
```sh
sudo update-initramfs -u -k all
```
*Разбор флагов:*
- `-u`: Обновить существующий образ initramfs.
- `-k all`: Применить изменения для всех установленных версий ядра Linux.

---

### Режим A2: Ethernet LAN (Ноутбук подключен по сетевому кабелю)

**Суть режима:** Ноутбук подключен патч-кордом к локальной сети (роутеру/свитчу), а телефон находится в той же сети (например, по Wi-Fi).

#### Шаг 1. Модули сетевой карты
Определите драйвер вашей Ethernet-карты (через `lspci -k` или `lsmod | grep -E "r8169|e1000e|tg3|igb"`).
Добавьте имя драйвера (например, `r8169`, `e1000e`, `virtio_net`) в `/etc/initramfs-tools/modules`.

#### Шаг 2. Конфигурация и сборка
Параметры `IP=dhcp`, опции Dropbear (`-p 22 -s -j -k -E`) и файл `authorized_keys` настраиваются так же, как в A1.
Примените изменения:
```sh
sudo update-initramfs -u -k all
```

---

### Режим A3: Wi-Fi Hotspot (Ноутбук подключается к точке доступа телефона)

**Суть режима:** На телефоне включается раздача Wi-Fi (Точка доступа). При включении ноутбука initramfs активирует Wi-Fi карту, подключается к точке доступа телефона, получает IP и запускает Dropbear.

#### Шаг 1. Модули ядра для беспроводной связи
Добавьте в `/etc/initramfs-tools/modules`:
```conf
cfg80211
mac80211
rfkill
iwlwifi
iwlmvm
```
*(Убедитесь, что драйвер вашей Wi-Fi карты, например `iwlwifi`, `iwlmvm`, `rtw88_8822ce`, `ath10k_pci`, `mt7921e`, также указан в списке).*

#### Шаг 2. Сохранение учётных данных точки доступа
Создайте файл конфигурации `/etc/nadamu/wifi/wpa_supplicant.conf`:
```sh
sudo mkdir -p /etc/nadamu/wifi
sudo tee /etc/nadamu/wifi/wpa_supplicant.conf > /dev/null << 'EOF'
ctrl_interface=/run/wpa_supplicant
update_config=1

network={
    ssid="ИМЯ_ТОЧКИ_ДОСТУПА_ТЕЛЕФОНА"
    psk="ПАРОЛЬ_ТОЧКИ_ДОСТУПА"
    key_mgmt=WPA-PSK WPA-PSK-SHA256 SAE
    proto=RSN WPA
    pairwise=CCMP TKIP
    group=CCMP TKIP
    ieee80211w=1
    scan_ssid=1
}
EOF
sudo chmod 600 /etc/nadamu/wifi/wpa_supplicant.conf
```
*Важные замечания по Wi-Fi параметрам:*
- `ieee80211w=1` (Protected Management Frames / PMF): **критично для Android-хотспотов**, без него точка доступа отклоняет подключение с ошибкой `status_code=31` (`ASSOC_REJECT`).
- `key_mgmt=WPA-PSK WPA-PSK-SHA256 SAE`: поддержка как WPA2-Personal, так и WPA3-Personal.
- `scan_ssid=1`: принудительное сканирование скрытых и мобильных точек доступа.
- Если в пароле содержатся спецсимволы (`$`, `&`, `#`), задавайте их в одинарных кавычках `'...'` в шелле во избежание обрезания.
- Длина текстового пароля WPA-PSK по стандарту: **от 8 до 63 символов**.

#### Шаг 3. Создание хука сборки initramfs (`nadamu_wifi`)
Этот хук копирует бинарники `wpa_supplicant`, `wpa_cli`, `iw`, `rfkill`, `ip`, регуляторную базу (`regulatory.db`), прошивки адаптеров (`firmware`) и сохранённый конфиг сети внутрь временного образа initramfs:
```sh
sudo tee /etc/initramfs-tools/hooks/nadamu_wifi > /dev/null << 'EOF'
#!/bin/sh
set -e

PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

# Копирование беспроводных утилит и сетевых бинарников
for bin in wpa_supplicant wpa_cli rfkill iw ip; do
    bin_path=$(command -v "$bin" 2>/dev/null || true)
    if [ -n "$bin_path" ]; then
        copy_exec "$bin_path" "$bin_path"
    fi
done

# Копирование конфига точки доступа
if [ -f /etc/nadamu/wifi/wpa_supplicant.conf ]; then
    mkdir -p "${DESTDIR}/etc/wpa_supplicant"
    cp -f /etc/nadamu/wifi/wpa_supplicant.conf "${DESTDIR}/etc/wpa_supplicant/wpa_supplicant.conf"
    chmod 600 "${DESTDIR}/etc/wpa_supplicant/wpa_supplicant.conf"
fi

# Копирование регуляторной базы беспроводных частот (CRDA)
for reg in /lib/firmware/regulatory.db* /lib/crda/regulatory.bin*; do
    if [ -f "$reg" ]; then
        mkdir -p "${DESTDIR}$(dirname "$reg")"
        cp -f "$reg" "${DESTDIR}$reg"
    fi
done

# Копирование прошивок беспроводных карт (Intel iwlwifi, Realtek, Atheros, MediaTek и др.)
for fw in /lib/firmware/iwlwifi-* /lib/firmware/intel/iwlwifi/* /lib/firmware/rtw* /lib/firmware/ath10k/* /lib/firmware/ath11k/* /lib/firmware/mediatek/*; do
    if [ -f "$fw" ]; then
        mkdir -p "${DESTDIR}$(dirname "$fw")"
        cp -f "$fw" "${DESTDIR}$fw"
    fi
done

exit 0
EOF
sudo chmod +x /etc/initramfs-tools/hooks/nadamu_wifi
```

#### Шаг 4. Создание загрузочного скрипта автоподключения (`nadamu_wifi_up`)
Этот скрипт запускается ядром на стадии `init-premount`, находит активный Wi-Fi интерфейс, снимает блокировку `rfkill`, подключается к хотспоту с логированием состояния и запрашивает IP по DHCP:
```sh
sudo tee /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up > /dev/null << 'EOF'
#!/bin/sh
PREREQ="udev"
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

CONF="/etc/wpa_supplicant/wpa_supplicant.conf"
[ -f "$CONF" ] || exit 0

echo "[nadamu-wifi] Unblocking wireless devices..."
rfkill unblock wifi 2>/dev/null || rfkill unblock all 2>/dev/null || true
iw reg set RU 2>/dev/null || true

WLAN_IF="wlan0"
for ifpath in /sys/class/net/*; do
    if [ -d "$ifpath/wireless" ] || [ -d "$ifpath/phy80211" ]; then
        WLAN_IF=$(basename "$ifpath")
        break
    fi
done

echo "[nadamu-wifi] Bringing up interface $WLAN_IF..."
ip link set "$WLAN_IF" up 2>/dev/null || true

mkdir -p /run /var/run /run/wpa_supplicant
wpa_supplicant -B -i "$WLAN_IF" -Dnl80211,wext -c "$CONF" -f /tmp/wpa.log -P /run/wpa_supplicant.pid

echo "[nadamu-wifi] Connecting to Wi-Fi network..."
i=0
connected=0
while [ $i -lt 15 ]; do
    status=$(wpa_cli -i "$WLAN_IF" status 2>/dev/null | grep "wpa_state=" | cut -d= -f2)
    echo "[nadamu-wifi] State ($i): ${status:-SCANNING}..."
    if [ "$status" = "COMPLETED" ]; then
        echo "[nadamu-wifi] Wi-Fi connected successfully!"
        connected=1
        break
    fi
    sleep 1
    i=$((i + 1))
done

if [ $connected -eq 0 ]; then
    echo "[nadamu-wifi] Failed to associate. Last log lines:"
    tail -n 10 /tmp/wpa.log 2>/dev/null || true
fi

# Получение IP-адреса через DHCP
echo "[nadamu-wifi] Requesting DHCP lease on $WLAN_IF..."
udhcpc -i "$WLAN_IF" -n -q -t 5 2>/dev/null || true
exit 0
EOF
sudo chmod +x /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up
```

*Разбор ключевых команд:*
- `wpa_supplicant -B -i "$WLAN_IF" -c "$CONF" -P /run/wpa_supplicant.pid`:
  - `-B`: Фоновый режим демона (background).
  - `-i <iface>`: Имя беспроводного интерфейса (например, `wlan0` или `wlp2s0`).
  - `-c <path>`: Путь к файлу конфигурации внутри initramfs.
  - `-P <pidfile>`: Файл для сохранения PID процесса.
- `udhcpc -i "$WLAN_IF" -n -q -t 5`:
  - `-i <iface>`: Целевой сетевой интерфейс.
  - `-n`: Не зависать, если адрес не получен (неблокирующий режим).
  - `-q`: Завершить работу сразу после успешного получения аренды адреса.
  - `-t 5`: Отправить до 5 запросов Discover перед выходом.

#### Шаг 5. Пересборка initramfs
```sh
sudo update-initramfs -u -k all
```

---

### Режим A4: Домашний / Офисный Wi-Fi

**Суть режима:** И телефон, и ноутбук подключаются к существующему домашнему Wi-Fi роутеру.

- Настройка полностью совпадает с режимом A3, но в `/etc/nadamu/wifi/wpa_supplicant.conf` указываются имя и пароль вашей домашней Wi-Fi сети.
- **Важное требование к роутеру:** Функция изоляции беспроводных клиентов (**AP Isolation / Client Isolation**) должна быть **выключена** в настройках роутера, иначе роутер не пропустит трафик между телефоном и ноутбуком по 22 порту.

---

## 4. Автоматическая установка через скрипт

Чтобы выполнить все вышеперечисленные шаги одной командой, используйте готовый скрипт из репозитория:

```sh
sudo ./install.sh
```

Либо сразу передайте параметры Wi-Fi для режима A3/A4:
```sh
sudo ./install.sh --ssid "MyHotspot" --psk "MyPassword123"
```

После выполнения скрипта:
1. Откройте приложение **Nadamu Unlocker** на телефоне.
2. Скопируйте **Client SSH Public Key**.
3. Вставьте его в `/etc/dropbear/initramfs/authorized_keys` на ноутбуке.
4. Выполните `sudo update-initramfs -u -k all`.

---

## 5. Интерактивная проверка работы

Вы можете проверить работу пайпа `passfifo` прямо по SSH без перезагрузки:
```sh
ssh -i /path/to/private_key -p 22 root@<IP_НОУТБУКА>
```
В открывшейся сессии initramfs запустите помощник:
```sh
# unlock
Enter LUKS Password:
[NADAMU] Unlock payload delivered to /lib/cryptsetup/passfifo.
```
При вводе верного пароля том `/dev/mapper/<target>` откроется, initramfs передаст управление основной ОС, и система продолжит загрузку.
