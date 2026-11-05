<h1 align="center">WaveDonate</h1>

<p align="center">
  Плагин выдачи доната для магазинов <a href="https://wdonate.wtf">WaveDonate</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.8.8%2B-62b47a?style=flat-square" alt="Minecraft 1.8.8+">
  <img src="https://img.shields.io/badge/Java-8%2B-e76f00?style=flat-square" alt="Java 8+">
  <img src="https://img.shields.io/badge/Folia-supported-4fbcd6?style=flat-square" alt="Folia">
  <img src="https://img.shields.io/github/v/release/Alexsxq/wavedonate-plugin?style=flat-square&color=4fbcd6" alt="Release">
</p>

Игрок оплачивает покупку на сайте магазина, а плагин забирает заказ и выполняет команды товара у вас на сервере.

Поддерживаются Spigot, Paper, Purpur и Folia, от 1.8.8 до последних версий. Плагин ставится на игровой сервер, на BungeeCord и Velocity его класть не нужно.

## Установка

1. Скачайте jar из [Releases](https://github.com/Alexsxq/wavedonate-plugin/releases/latest) или из кабинета WaveDonate.
2. Положите его в папку `plugins` и перезапустите сервер.
3. Выполните в консоли сервера:

```
wd setup <ключ магазина>
```

Ключ находится в кабинете на вкладке «Плагин». Если на одном магазине несколько режимов, поставьте плагин на каждый сервер и укажите ID режима:

```
wd setup <ключ магазина> <server-id>
```

## Команды товара

Команды задаются в кабинете у каждого товара. Обычная строка выполняется от консоли, а в начале строки можно указать действие:

```
lp user %player% parent add vip
[player] kit vip
[message] &aСпасибо за покупку!
[broadcast] &e%player% купил %product%
[title] &6Спасибо!|&fЗа покупку %product%
[actionbar] &aПокупка выдана
[sound] entity.player.levelup|1|1
[delay:5] give %player% diamond 1
```

| Плейсхолдер | Значение |
|---|---|
| `%player%` | ник игрока |
| `%product%` | название товара |
| `%amount%` | сумма заказа |
| `%order%` | номер заказа |
| `%from%` | кто оплатил, если это подарок |
| `%quantity%` | количество валюты |

Цвета пишутся как `&a` или `&#ff8800`. На версиях ниже 1.16 HEX заменяется ближайшим обычным цветом.

## Возможности

- Заказ хранится в `queue.yml`, пока не выполнятся все его команды. Если сервер выключится посреди выдачи, после запуска она продолжится с того же места.
- С `delivery.require-online: true` покупка ждёт, пока игрок зайдёт на сервер.
- После выдачи игрок видит сообщение, тайтл и слышит звук. Всё это меняется в `config.yml`.
- Один ключ можно использовать на нескольких серверах, заказ выдаётся только один раз.

## Команды

Нужно право `wavedonate.admin`, по умолчанию оно есть у операторов.

| Команда | Описание |
|---|---|
| `/wd status` | состояние подключения и статистика |
| `/wd ping` | проверить ключ и связь с сайтом |
| `/wd check` | проверить новые оплаты сразу |
| `/wd queue` | заказы, которые ещё ждут выдачи |
| `/wd test [ник]` | показать уведомление о покупке |
| `/wd reload` | перечитать конфиг |

## Сборка

```
mvn package
```

Jar появится в папке `target`.

## Лицензия

[MIT](LICENSE) © Domshyy Industries
