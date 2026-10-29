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
4. Zamilknij na ponad minutę (albo kliknij **Wyłącz**) → nagranie pojawi się na liście (pod „Dzisiaj”). Przerwy krótsze niż minuta zostają w tym samym nagraniu, skrócone do 2 s.
5. Jeśli nagrywa za dużo (np. szum) albo za mało – zmień **Czułość**.

Nagrania krótsze niż 1 s mowy (stuknięcia, trzaśnięcia drzwiami) są automatycznie odrzucane.
Pliki leżą w `/data/data/pl.jarvis/files/recordings/RRRR-MM-DD/GG-MM-SS.wav`
(podgląd: Android Studio → View → Tool Windows → Device Explorer).

## Jarvis z AI i słowem „Jarvis” (od wersji 0.6)

W zakładce **⚙ Opcje** wpisz:

- **Klucz API Claude** – platform.claude.com → doładuj konto (np. 5 $) → API Keys → Create Key.
  Bez klucza Jarvis rozumie tylko proste komendy lokalne.
- albo **Kimi** (od wersji 0.7): wybierz „Kimi”, wpisz klucz z platform.moonshot.ai, kliknij „Pobierz modele” i wybierz model.
- **AccessKey Picovoice** – console.picovoice.ai → darmowe konto → AccessKey. Potrzebny do wykrywania słowa „Jarvis”.

Słowo „Jarvis” działa, gdy w zakładce **Nagrania** jest włączone nasłuchiwanie. Po odpowiedzi Jarvis słucha dalej;
cisza albo „dzięki” kończy rozmowę.

## Plan

- [x] Etap 1a: nasłuchiwanie w tle, wykrywanie mowy, zapis nagrań, lista i odtwarzanie
- [ ] Etap 1b: transkrypcja na telefonie (Whisper), nocne podsumowania i lista zadań
- [x] Etap 2: zarobki, płatności (ZUS, alimenty), raporty, zadania
- [ ] Przypomnienia o terminach (ZUS, alimenty)
- [x] Etap 3: rozmowa z Jarvisem – Claude przez API, słowo „Jarvis”, tryb rozmowy
- [ ] Głos Piper `pl_PL-bass-high`
- [ ] Etap 4: połączenia, SMS, Gmail, kalendarz
- [ ] Etap 5: muzyka i głośniki, inicjatywa (poranny raport), słuchawki, dom
