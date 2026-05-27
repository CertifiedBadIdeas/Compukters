# План реализации Terminal Back Panel

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить плоский sprite terminal на простую кастомную item model с отдельными front, back и side текстурами.

**Architecture:** Ассет должен остаться небольшим и предсказуемым: одна тонкая прямоугольная 3D-модель с явными текстурами по сторонам. Сохраняем текущий вид лицевой панели, добавляем минималистичную спинку и трогаем transforms только если первый рендер покажет явную проблему ориентации или читаемости.

**Tech Stack:** JSON-модели Minecraft, PNG пиксельные текстуры, Gradle runClient для ручной визуальной проверки.

---

### Task 1: Подготовить текстуры terminal

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_front.png`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_back.png`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_side.png`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal.png`

- [ ] **Step 1: Сохранить текущий фронт как основу новой front texture**

Скопировать текущее лицо terminal в `terminal_front.png`, не меняя силуэт предмета.

- [ ] **Step 2: Нарисовать новую back texture**

Создать 16x16 `terminal_back.png` с такими признаками:

```text
- тёмный внешний корпус
- более светлая центральная крышка
- два маленьких винта или пиксельных акцента
- неброская нижняя маркировка или логотип
```

- [ ] **Step 3: Нарисовать side texture**

Создать `terminal_side.png`, которая читается как более тёмный материал корпуса с лёгким бликом, чтобы устройство выглядело тонким, но цельным.

- [ ] **Step 4: Осознанно сохранить или переиспользовать `terminal.png`**

Либо оставить `terminal.png` как legacy placeholder, если на него ещё есть ссылки, либо сделать его копией front texture, чтобы случайные обращения по старому пути всё ещё показывали корректную лицевую сторону.

- [ ] **Step 5: Проверить размеры текстур**

Run: `file modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_front.png modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_back.png modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_side.png`

Expected: все файлы определяются как PNG 16 x 16.

### Task 2: Заменить generated item model

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json`

- [ ] **Step 1: Заменить generated model на тонкий параллелепипед**

Использовать hand-authored item model с одним element и отдельными текстурами:

```json
{
  "textures": {
    "front": "compukterkraft:item/terminal_front",
    "back": "compukterkraft:item/terminal_back",
    "side": "compukterkraft:item/terminal_side"
  },
  "elements": [
    {
      "from": [2, 1, 7.5],
      "to": [14, 15, 8.5],
      "faces": {
        "north": { "texture": "#front" },
        "south": { "texture": "#back" },
        "east": { "texture": "#side" },
        "west": { "texture": "#side" },
        "up": { "texture": "#side" },
        "down": { "texture": "#side" }
      }
    }
  ]
}
```

- [ ] **Step 2: Добавить display transforms только если первый рендер покажет необходимость**

Если предмет окажется повернут неверно или потеряет читаемость front/back, добавить явные `display` transforms уже после визуальной проверки, а не угадывать заранее.

- [ ] **Step 3: Проверить структуру JSON**

Run: `jq . modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json >/dev/null`

Expected: команда завершается успешно без вывода.

### Task 3: Визуально проверить ассет в игре

**Files:**
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_front.png`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_back.png`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_side.png`

- [ ] **Step 1: Запустить клиент для визуальной проверки**

Run: `./gradlew :modules:v1_21_1:v1_21_1-neoforge:runClient`

Expected: клиент стартует без ошибок загрузки ассетов.

- [ ] **Step 2: Проверить GUI, вид в руке и выброшенный предмет**

Проверить вручную:

```text
- читаемость в инвентаре и хотбаре
- видимость лицевой стороны в руке
- выброшенный предмет показывает отличающуюся заднюю сторону при повороте
```

- [ ] **Step 3: Исправить transforms или тон боковины только при конкретной проблеме**

Если предмет выглядит слишком толстым, слишком тёмным или front/back плохо различаются, внести минимальную правку и повторить визуальную проверку.

- [ ] **Step 4: Чисто зафиксировать набор изменённых файлов**

Run: `git status --short`

Expected: в статусе перечислены только плановые изменения terminal model, textures и связанные plan/spec файлы.