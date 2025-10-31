#!/bin/bash

# CGI скрипт для запуска Java приложения
# Этот файл нужно разместить на сервере как ~/cgi-bin/server

# Читаем POST данные
if [ "$REQUEST_METHOD" = "POST" ]; then
    read -n $CONTENT_LENGTH POST_DATA
else
    POST_DATA="$QUERY_STRING"
fi

# Запускаем Java приложение (без FastCGI, напрямую)
echo "Content-Type: application/json"
echo ""

# Здесь нужен обычный Java класс, не FastCGI
# Временное решение - вернём тестовый JSON
echo "{\"result\": true, \"now\": \"$(date -Iseconds)\", \"time\": 1000}"
