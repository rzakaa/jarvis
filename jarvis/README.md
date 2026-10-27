# Jarvis – osobisty asystent (Android)

Etap 1: **rejestrator rozmów**. Aplikacja nasłuchuje w tle i zapisuje tylko fragmenty, w których ktoś mówi.
Wszystko zostaje w telefonie (prywatny katalog aplikacji, bez kopii w chmurze).

## Uruchomienie w Android Studio

1. Android Studio → **File → Open** → wybierz folder `jarvis` (ten, w którym leży ten plik).
2. Poczekaj, aż Gradle pobierze zależności (pierwszy raz kilka minut).
   Jeśli Android Studio zaproponuje aktualizację wtyczki Android Gradle Plugin – możesz się zgodzić.
3. Utwórz emulator (Device Manager → **+**) z systemem **Android 14 lub 15** (obraz „Google APIs”).
4. Kliknij **Run ▶**.

## Mikrofon w emulatorze

Emulator domyślnie **nie słyszy** mikrofonu komputera:

1. Przy uruchomionym emulatorze kliknij **⋯** (Extended controls) → **Microphone**.
2. Włącz **„Virtual microphone uses host audio input”**.
3. Ustawienie wyłącza się po każdym restarcie emulatora – trzeba je włączyć ponownie.

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
