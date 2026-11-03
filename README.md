# Jarvis

Osobisty asystent głosowy po polsku na Androida, w stylu Jarvisa Tony'ego Starka.

- Budzi się na „Hey Jarvis”, mówi i słucha po polsku; „stop” przerywa go w pół zdania.
- Mózg: Kimi (domyślnie `kimi-k3.1-pro-highspeed`) albo Claude; proste komendy działają lokalnie.
- Głos: Azure TTS, a bez internetu lokalny Piper.
- Steruje telefonem: kontakty, połączenia, SMS, kalendarz, powiadomienia, nawigacja, wypełnianie pól na ekranie.
- Nagrywa i przepisuje rozmowy w telefonie (sherpa-onnx), wyłapuje z nich obietnice i przypomina o nich.
  Dźwięk znika po 30 dniach (rozmowy) albo 7 dniach (nasłuch otoczenia), tekst zostaje.

Wszystko zostaje w telefonie. Klucze API wpisuje się w Opcjach i trzymane są tylko w SharedPreferences.

## Uruchomienie

Wymagane: JDK 21, Android SDK, telefon z Androidem 8.0+ (minSdk 26) podłączony przez adb.

```powershell
cd jarvis
.\gradlew.bat installDebug
```

Przy pierwszym uruchomieniu nadaj uprawnienia (mikrofon, telefon, powiadomienia, sterowanie ekranem)
i wpisz klucze w Opcjach. Model mowy pobiera się sam.

ONNX Runtime (1.24.3) musi pasować do AAR-a sherpa-onnx (1.13.0), więc nie zmieniaj go bez sprawdzenia.

## Wersja

0.44 (versionCode 44)
