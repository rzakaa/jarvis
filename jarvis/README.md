# Jarvis – osobisty asystent (Android)

Etap 1: **rejestrator rozmów**. Aplikacja nasłuchuje w tle i zapisuje tylko fragmenty, w których ktoś mówi.
Wszystko zostaje w telefonie (prywatny katalog aplikacji, bez kopii w chmurze).

## Uruchomienie w Android Studio

1. Android Studio → **File → Open** → wybierz folder `jarvis` (ten, w którym leży ten plik).
2. Poczekaj, aż Gradle pobierze zależności (pierwszy raz kilka minut).
   Jeśli Android Studio zaproponuje aktualizację wtyczki Android Gradle Plugin – możesz się zgodzić.
3. Utwórz emulator (Device Manager → **+**) z systemem **Android 14 lub 15** (obraz „Google APIs”).
4. Kliknij **Run ▶**.

## Mikrofon w emulatorze (Windows)

Przełącznik „Enable Host Microphone Access” w oknie emulatora potrafi nie działać –
emulator podaje wtedy sztuczny ton 220 Hz zamiast mikrofonu (pasek głośności stale pełny, także w ciszy).
Pewny sposób: uruchomić emulator z Terminala z flagą `-allow-host-audio`, a aplikację instalować przez Gradle.

```
# 1. zamknij działające emulatory
Get-Process qemu*, emulator* -ErrorAction SilentlyContinue | Stop-Process -Force

# 2. uruchom emulator z mikrofonem komputera (nie zamykaj tej karty Terminala)
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_8 -allow-host-audio

# 3. w nowej karcie Terminala: zainstaluj aplikację na działającym emulatorze
.\gradlew installDebug
```

Potem uruchom Jarvisa z listy aplikacji w emulatorze. Kontrola: w ciszy pasek głośności spada prawie do zera.

Pobranie nagrań na komputer:

```
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" root
mkdir C:\nagrania-jarvis
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pull /data/data/pl.jarvis/files/recordings C:\nagrania-jarvis
```

## Jak testować

1. W aplikacji kliknij **Włącz nasłuchiwanie** i zezwól na mikrofon i powiadomienia.
2. Status „Nasłuchuję…” – pasek pokazuje głośność.
3. Powiedz coś przez kilka sekund → status zmieni się na **„Nagrywam rozmowę”**.
4. Zamilknij na 8 s → nagranie pojawi się na liście (pod „Dzisiaj”). Można je odtworzyć albo usunąć.
5. Jeśli nagrywa za dużo (np. szum) albo za mało – zmień **Czułość**.

Nagrania krótsze niż 1 s mowy (stuknięcia, trzaśnięcia drzwiami) są automatycznie odrzucane.
Pliki leżą w `/data/data/pl.jarvis/files/recordings/RRRR-MM-DD/GG-MM-SS.wav`
(podgląd: Android Studio → View → Tool Windows → Device Explorer).

## Plan

- [x] Etap 1a: nasłuchiwanie w tle, wykrywanie mowy, zapis nagrań, lista i odtwarzanie
- [ ] Etap 1b: transkrypcja na telefonie (Whisper), nocne podsumowania i lista zadań
- [ ] Etap 2: komendy głosowe – zarobki, płatności (ZUS, alimenty), przypomnienia, raporty
- [ ] Etap 3: rozmowa z Jarvisem (słowo „Jarvis”, głos Piper `pl_PL-bass-high`, Claude przez API)
- [ ] Etap 4: połączenia, SMS, Gmail, kalendarz
- [ ] Etap 5: muzyka i głośniki, inicjatywa (poranny raport), słuchawki, dom
