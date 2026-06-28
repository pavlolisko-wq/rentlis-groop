Rentlis groop — Android (WebView) проект
========================================

Это готовый к сборке Android-проект. Внутри уже вшит интерфейс приложения
(app/src/main/assets/rentmaster.html). Работает офлайн, интернет не нужен.

КАК ПОЛУЧИТЬ APK (Android Studio — проще всего):
1. Установить Android Studio (если ещё нет): developer.android.com/studio
2. File → Open → выбрать папку RentlisGroop. Дождаться Gradle Sync
   (Studio сам докачает Gradle 8.7 и нужный Android SDK).
3. Меню Build → Build App Bundle(s) / APK(s) → Build APK(s).
4. Появится ссылка "locate" → файл app/build/outputs/apk/debug/app-debug.apk
5. Перекинуть app-debug.apk на телефон и установить
   (разрешить "установку из неизвестных источников").

КАК СОБРАТЬ ИЗ КОНСОЛИ (если установлен Android SDK + JDK 17):
1. В папке проекта один раз создать wrapper:  gradle wrapper
2. Debug-сборка:    ./gradlew assembleDebug
   APK:             app/build/outputs/apk/debug/app-debug.apk
3. Release (без подписи): ./gradlew assembleRelease
   Для публикации release нужно подписать ключом (keystore).

ПОМЕНЯТЬ ИНТЕРФЕЙС:
Просто замени файл app/src/main/assets/rentmaster.html на новую версию
и пересобери — больше ничего не нужно.

Имя пакета: md.rentlis.groop  ·  minSdk 26  ·  targetSdk 34

========================================
СОБРАТЬ APK В ОБЛАКЕ (без Android Studio, бесплатно)
========================================
Подходит, если не хочешь ставить SDK на компьютер.

1. Завести бесплатный аккаунт на github.com и создать новый репозиторий
   (кнопка New → имя любое, например rentlis-groop → Create).
2. Загрузить туда ВСЁ содержимое этой папки RentlisGroop
   (Add file → Upload files → перетащить файлы и папки → Commit).
   Важно: должна попасть и скрытая папка .github (в ней скрипт сборки).
3. Открыть вкладку "Actions" в репозитории. Сборка "Build APK" запустится
   сама после загрузки (или нажми Run workflow вручную).
4. Когда галочка позеленеет (~2-4 мин) — зайти в этот запуск, внизу раздел
   "Artifacts" → скачать RentlisGroop-app-debug-apk (это zip с app-debug.apk).
5. Распаковать, перекинуть app-debug.apk на телефон, установить
   (разрешив установку из неизвестных источников).

Файл сборки: .github/workflows/build-apk.yml
