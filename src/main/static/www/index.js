(function(){
    const form  = document.getElementById('point-form');
    const errEl = document.getElementById('error');

    const svg   = document.getElementById('plot');
    const GAREA = document.getElementById('area');
    const GPTS  = document.getElementById('points');
    const GRID  = document.getElementById('grid');

    const CX = 200, CY = 200;
    const SCALE = 100;

    // Переменные для зума
    let zoomLevel = 1;
    const ZOOM_MIN = 0.5;
    const ZOOM_MAX = 3;
    const ZOOM_STEP = 0.2;

    // Пагинация
    let currentPage = 1;
    const ROWS_PER_PAGE = 10;
    let allHistory = [];

    // ==============================================
    // ФУНКЦИИ ПРЕОБРАЗОВАНИЯ КООРДИНАТ
    // ==============================================
    function toPx(x, y, r) {
        const k = SCALE / r;
        return {
            X: CX + x * k,
            Y: CY - y * k,
            k
        };
    }

    function fromPx(px, py, r) {
        const k = SCALE / r;
        const x = (px - CX) / k;
        const y = (CY - py) / k;
        return {x, y};
    }

    // ==============================================
    // ОТРИСОВКА СЕТКИ
    // ==============================================
    function renderGrid() {
        GRID.innerHTML = "";
        const step = 50;

        // Вертикальные линии
        for (let x = step; x < 400; x += step) {
            if (x === CX) continue;
            const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
            line.setAttribute("x1", x);
            line.setAttribute("y1", 0);
            line.setAttribute("x2", x);
            line.setAttribute("y2", 400);
            GRID.appendChild(line);
        }

        // Горизонтальные линии
        for (let y = step; y < 400; y += step) {
            if (y === CY) continue;
            const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
            line.setAttribute("x1", 0);
            line.setAttribute("y1", y);
            line.setAttribute("x2", 400);
            line.setAttribute("y2", y);
            GRID.appendChild(line);
        }
    }

    // ==============================================
    // ОТРИСОВКА ФИГУР (НОВЫЙ ВАРИАНТ ИЗ ИЗОБРАЖЕНИЯ)
    // ==============================================
    function renderArea(r) {
        if (!(r > 0)) return;
        GAREA.innerHTML = "";
        const k = SCALE / r;

        // 1. ПОЛУКРУГ СПРАВА (1-я четверть): x≥0, y≥0, x²+y²≤(R/2)²
        const radius = (r / 2) * k;
        const arc = document.createElementNS("http://www.w3.org/2000/svg", "path");
        // Рисуем дугу от (R/2, 0) до (0, R/2) через (R/2·cos45°, R/2·sin45°)
        arc.setAttribute("d",
            `M ${CX} ${CY} ` +
            `L ${CX + radius} ${CY} ` +
            `A ${radius} ${radius} 0 0 0 ${CX} ${CY - radius} ` +
            `Z`
        );
        arc.setAttribute("class", "shape");
        GAREA.appendChild(arc);

        // 2. ТРЕУГОЛЬНИК СЛЕВА (2-я четверть): x≤0, y≤0
        // Вершины: (0,0), (-R,0), (0,-R/2)
        const A = toPx(0, 0, r);
        const B = toPx(-r, 0, r);
        const C = toPx(0, -r/2, r);
        const tri = document.createElementNS("http://www.w3.org/2000/svg", "polygon");
        tri.setAttribute("points", `${A.X},${A.Y} ${B.X},${B.Y} ${C.X},${C.Y}`);
        tri.setAttribute("class", "shape");
        GAREA.appendChild(tri);

        // 3. ПРЯМОУГОЛЬНИК СНИЗУ (4-я четверть): x≥0, -R≤y≤0
        // От (0,0) до (R/2,0) до (R/2,-R) до (0,-R)
        const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        rect.setAttribute("x", CX);
        rect.setAttribute("y", CY);
        rect.setAttribute("width", (r/2) * k);
        rect.setAttribute("height", r * k);
        rect.setAttribute("class", "shape");
        GAREA.appendChild(rect);
    }

    // ==============================================
    // УПРАВЛЕНИЕ ЗУМОМ
    // ==============================================
    function applyZoom() {
        svg.style.transform = `scale(${zoomLevel})`;
    }

    document.getElementById('zoom-in').addEventListener('click', () => {
        if (zoomLevel < ZOOM_MAX) {
            zoomLevel = Math.min(ZOOM_MAX, zoomLevel + ZOOM_STEP);
            applyZoom();
        }
    });

    document.getElementById('zoom-out').addEventListener('click', () => {
        if (zoomLevel > ZOOM_MIN) {
            zoomLevel = Math.max(ZOOM_MIN, zoomLevel - ZOOM_STEP);
            applyZoom();
        }
    });

    document.getElementById('zoom-reset').addEventListener('click', () => {
        zoomLevel = 1;
        applyZoom();
    });

    // ==============================================
    // ПРЕВЬЮ ТОЧКИ
    // ==============================================
    let previewDot = null;

    function removePreview() {
        if (previewDot && previewDot.parentNode) {
            previewDot.parentNode.removeChild(previewDot);
        }
        previewDot = null;
    }

    function plotPreview(x, y, r) {
        if (!(r > 0) || !Number.isFinite(x) || !Number.isFinite(y)) {
            removePreview();
            return;
        }

        const {X, Y} = toPx(x, y, r);

        if (!previewDot) {
            previewDot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
            previewDot.setAttribute("r", 6);
            previewDot.setAttribute("class", "pt preview");
            GPTS.appendChild(previewDot);
        }

        previewDot.setAttribute("cx", X);
        previewDot.setAttribute("cy", Y);
    }

    // ==============================================
    // КЛИК ПО ГРАФИКУ
    // ==============================================
    svg.addEventListener('click', (ev) => {
        const r = getSelectedR();
        if (!(r > 0)) return;

        const rect = svg.getBoundingClientRect();
        const scaleX = 400 / rect.width;
        const scaleY = 400 / rect.height;

        const svgX = (ev.clientX - rect.left) * scaleX / zoomLevel;
        const svgY = (ev.clientY - rect.top) * scaleY / zoomLevel;

        const {x, y} = fromPx(svgX, svgY, r);

        setXFromNumber(x);
        document.getElementById('y').value = y.toFixed(3);
        plotPreview(x, y, r);
    });

    // ==============================================
    // РАБОТА С ФОРМОЙ
    // ==============================================
    document.querySelectorAll('input[name="x"]').forEach(cb => {
        cb.addEventListener('change', function() {
            if (this.checked) {
                document.querySelectorAll('input[name="x"]').forEach(o => {
                    if (o !== this) o.checked = false;
                });
            }
            onPreview();
        });
    });

    document.querySelectorAll('input[name="r"]').forEach(radio => {
        radio.addEventListener('change', () => {
            const r = getSelectedR();
            if (r) renderArea(r);
            onPreview();
        });
    });

    document.getElementById('y').addEventListener('input', onPreview);

    function getSelectedX() {
        const checked = Array.from(document.querySelectorAll('input[name="x"]:checked'));
        return checked.length === 1 ? Number(checked[0].value) : null;
    }

    function setXFromNumber(x) {
        const xi = Math.max(-4, Math.min(4, Math.round(x)));
        document.querySelectorAll('input[name="x"]').forEach(o => o.checked = false);
        const target = document.querySelector(`input[name="x"][value="${xi}"]`);
        if (target) target.checked = true;
    }

    function getY() {
        const raw = document.getElementById('y').value.trim().replace(',', '.');
        if (raw === "" || !/^[-+]?(?:\d+|\d*\.\d+)$/.test(raw)) return null;
        return Number(raw);
    }

    function getSelectedR() {
        const r = document.querySelector('input[name="r"]:checked');
        return r ? Number(r.value) : null;
    }

    function validateInputs() {
        const x = getSelectedX(), y = getY(), r = getSelectedR();
        if (x === null) return "Нужно выбрать ровно один X в диапазоне [-4..4]";
        if (y === null) return "Y должен быть числом";
        if (y < -5 || y > 5) return "Y должен быть в диапазоне [-5..5]";
        if (r === null) return "Нужно выбрать R (1..5)";
        return null;
    }

    function onPreview() {
        const x = getSelectedX(), y = getY(), r = getSelectedR();
        if (x !== null && y !== null && r) {
            plotPreview(x, y, r);
        } else {
            removePreview();
        }
    }

    // ==============================================
    // ПАГИНАЦИЯ
    // ==============================================
    function updatePagination() {
        const totalPages = Math.max(1, Math.ceil(allHistory.length / ROWS_PER_PAGE));

        document.getElementById('page-info').textContent = `Страница ${currentPage} из ${totalPages}`;
        document.getElementById('results-count').textContent = `Всего: ${allHistory.length}`;

        document.getElementById('prev-page').disabled = currentPage <= 1;
        document.getElementById('next-page').disabled = currentPage >= totalPages;
    }

    function renderPage() {
        const start = (currentPage - 1) * ROWS_PER_PAGE;
        const end = start + ROWS_PER_PAGE;
        const pageData = allHistory.slice(start, end);

        const tbody = document.querySelector('#results tbody');
        tbody.innerHTML = "";

        pageData.forEach((row, i) => {
            const tr = document.createElement('tr');
            if (start === 0 && i === 0) tr.classList.add('latest');

            const ms = (Number(row.scriptTimeMs) || 0).toFixed(3);
            const cells = [row.x, row.y, row.r, (row.hit ? "✓ Да" : "✗ Нет"), row.time, ms];

            cells.forEach(val => {
                const td = document.createElement('td');
                td.textContent = val;
                tr.appendChild(td);
            });

            tbody.appendChild(tr);
        });

        updatePagination();
    }

    document.getElementById('prev-page').addEventListener('click', () => {
        if (currentPage > 1) {
            currentPage--;
            renderPage();
        }
    });

    document.getElementById('next-page').addEventListener('click', () => {
        const totalPages = Math.ceil(allHistory.length / ROWS_PER_PAGE);
        if (currentPage < totalPages) {
            currentPage++;
            renderPage();
        }
    });

    // ==============================================
    // ОТРИСОВКА ИСТОРИИ И ТОЧЕК
    // ==============================================
    function draw(history) {
        allHistory = (history || [])
            .slice()
            .sort((a, b) => new Date(b.time) - new Date(a.time));

        currentPage = 1;
        renderPage();

        // Отрисовка точек на графике
        GPTS.innerHTML = "";

        const r = getSelectedR();
        if (!r) return;

        allHistory.forEach(row => {
            if (row.r !== r) return;

            const {X, Y} = toPx(row.x, row.y, r);
            const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
            circle.setAttribute("cx", X);
            circle.setAttribute("cy", Y);
            circle.setAttribute("r", 5);
            circle.setAttribute("class", `pt ${row.hit ? 'hit' : 'miss'}`);

            const title = document.createElementNS("http://www.w3.org/2000/svg", "title");
            title.textContent = `(${row.x}, ${row.y}) - ${row.hit ? 'Попадание' : 'Промах'}`;
            circle.appendChild(title);

            GPTS.appendChild(circle);
        });
    }

    // ==============================================
    // ОТПРАВКА ФОРМЫ
    // ==============================================
    form.addEventListener('submit', function(e) {
        e.preventDefault();
        errEl.textContent = "";

        const msg = validateInputs();
        if (msg) {
            errEl.textContent = msg;
            return;
        }

        const x = getSelectedX(), y = getY(), r = getSelectedR();
        const body = new URLSearchParams({
            x: String(x),
            y: String(y),
            r: String(r)
        });

        fetch(form.action, {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
            body: body.toString(),
        })
        .then(resp => resp.json())
        .then(data => {
            if (!data.ok) {
                errEl.textContent = data.error || "Ошибка сервера";
                return;
            }
            removePreview();
            renderArea(r);
            draw(data.history);
        })
        .catch(() => errEl.textContent = "Сеть/сервер недоступен");
    });

    // ==============================================
    // ИНИЦИАЛИЗАЦИЯ
    // ==============================================
    renderGrid();
    const r0 = getSelectedR();
    if (r0) renderArea(r0);
    onPreview();
    updatePagination();
})();
