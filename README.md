# Energy Intelligence Bulgaria (Енерго BG)

Android приложение и сървърна задача за анализ на енергийния пазар в България и Европа.

## Архитектура
```
GitHub Actions (на всеки 15 мин.)  ──>  клон `data`: feed/feed.json, news.json, status.json  ──>  Android приложение
  pipeline/fetch.py                       (без ключове, с източник, час, тип и резолюция)        (резервно: Energy-Charts директно)
```
- **Ключове** – само в GitHub Secrets: `ENTSOE_TOKEN` (задължителен за ENTSO-E), `ANTHROPIC_API_KEY` (по желание – BG резюмета и дневен AI анализ).
- **Източници:** ENTSO-E Transparency Platform → резервно Energy-Charts (Fraunhofer ISE); Open-Meteo (прогноза + ансамбъл); RSS: European Commission, US DOE, ScienceDaily, Phys.org, Energy-Storage.news, pv magazine.
- Всеки набор данни носи: източник, URL, тип (ден напред / измерено / прогноза / история), единица, резолюция, час на изтегляне.

## Раздели
Табло · Енергиен пазар · Европа и Балкани · Производство · Пренос и търговия · BESS анализатор (NPV/IRR, LFP/NMC/Na-ion/проточни) · Новини · Energy Vision · Знаете ли, че...? · AI анализатор · Отчети (CSV/PDF) · Настройки (тема, състояние на източниците).

## Тестове
- `python -m unittest discover -s pipeline/tests` – парсване на ENTSO-E XML, Energy-Charts, RSS, класификация, часови зони.
- `./gradlew testDebugUnitTest` – цени, батерия, NPV/IRR, справочник, четене на feed.json.

## Инсталиране
Всяко качване в `main` се билдва и публикува като Release → `EnergyIntelligence.apk`.
