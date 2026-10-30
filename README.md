# HA Panel

Lekka, darmowa aplikacja na Androida, która zamienia tablet w panel ścienny dla [Home Assistanta](https://www.home-assistant.io/). Alternatywa dla Fully Kiosk Browser bez licencji i bez zależności zewnętrznych. Powstała z myślą o starszych tabletach (testowana założeniowo na Lenovo Tab M10 FHD Plus, Android 10), działa na Androidzie 8 i nowszym.

Do pełnej obsługi z poziomu HA służy integracja: **[hapanel-integration](https://github.com/bobby6robert/hapanel-integration)**.

## Funkcje

- Pełnoekranowy WebView z dashboardem HA, ekran zawsze włączony, ponawianie po utracie połączenia (ekran „Brak połączenia”).
- Autostart po restarcie i blokada wyjścia (przypięcie aplikacji). Ustawienia otwierasz 5 szybkimi dotknięciami w lewym górnym rogu.
- Ekran ustawień z **listą kontrolną**: pokazuje, czego brakuje (mikrofon, autostart, bateria, polski głos TTS, HTTPS), i jednym przyciskiem otwiera właściwe okno systemu.
- Automatyczne wykrywanie przez HA (mDNS) i zdalna konfiguracja z integracji, parowanie 6-cyfrowym kodem z ekranu tabletu.
- **Tryb nocny w aplikacji:** harmonogram (np. 22:00-6:30), ciemny ekran z zegarem i datą, niska jasność, wybudzanie dotykiem lub ruchem i powrót do zegara po bezczynności. Działa bez HA.
- **Okno podglądu (`/popup`)** na dzwonek: pokazuje kamerę lub stronę na wierzchu, wybudza ekran, a po czasie lub po „Zamknij” wraca dokładnie do poprzedniego widoku.
- **Adresy awaryjne:** główny (np. zewnętrzny HTTPS) i dodatkowe (np. lokalny). Gdy główny nie działa, aplikacja przełącza się sama, oznacza „Adres awaryjny · bez mikrofonu” i co 5 minut sprawdza po cichu, czy główny wrócił.
- **Zdarzenia na żywo:** ruch, dotknięcie, niska bateria, start i stan ekranu trafiają od razu do webhooka w HA, bez odpytywania i bez tokena HA na tablecie.
- **Diagnostyka:** temperatura i kondycja baterii, wolna pamięć, czas od uruchomienia, ostatnie załadowanie strony, wersja WebView.
- **Zrzut ekranu** na żądanie (`/screenshot`) jako obraz dla encji kamery w HA, bez strumienia.
- **Aktualizacje z GitHub Releases:** HA wskazuje nowy APK, a tablet pobiera go i pokazuje systemowe potwierdzenie instalacji. Po aktualizacji aplikacja sama wstaje.
- **Dopasowanie wyglądu:** powiększenie tekstu oraz własny CSS i JavaScript wstrzykiwane do stron (np. na małych tabletach).
- **Kopia ustawień** jako JSON (eksport i import, bez PIN-u i tokenów), także z HA.
- **PIN do ustawień** (min. 4 znaki, zapisany jako skrót z solą, blokada na minutę po 5 błędach). Można go ustawić z HA, ale nigdy nie jest stamtąd odczytywany.
- Serwer HTTP w sieci lokalnej do sterowania z HA: wybudzanie i usypianie ekranu, jasność, ładowanie adresu, odtwarzanie dźwięku, głośność, komunikaty głosowe (TTS), restart panelu.
- Mikrofon dla kart WebRTC (rozmowa przez domofon). Wymaga dashboardu po **HTTPS**.
- Wykrywanie ruchu z przedniej kamery i wybudzanie ekranu, automatyczna jasność z czujnika światła.
- Stabilna praca 24/7: blokady Wi-Fi i procesora, powrót do dashboardu po bezczynności, nocne przeładowanie, odporność na awarię WebView.

„Uśpienie” ekranu to czarna nakładka z minimalną jasnością. Bez roota Android nie pozwala naprawdę wyłączyć ekranu.

## Nowości w 2.8 – łatwiejsze aktualizacje
- APK jest wersją końcową podpisaną stałym kluczem wydań (zamiast klucza testowego). Mniej ostrzeżeń Play Protect.
- Podczas aktualizacji panel nie przypina się z powrotem i nie zasłania okna instalatora.
- Gdy aktualizacja się nie uda, tablet pokazuje powód (np. inny podpis, Play Protect, brak miejsca).
- Android 12+: aktualizacja bez dodatkowego pytania.
- **Jednorazowo:** przy przejściu z 2.7 na 2.8 trzeba ręcznie odinstalować starą wersję (zmiana klucza). Kolejne aktualizacje już bez tego.
- Budowanie wydań wymaga sekretu repozytorium `HAPANEL_KEYSTORE` (plik klucza PKCS12 w base64, hasło `hapanel`).

## Nowości w 2.7 – Aurora (rozmowa głosowa)
Wymaga integracji **hapanel 1.6** w Home Assistant.
- **Rozmowa z tabletem:** po komunikacie (`/converse`) tablet sam słucha odpowiedzi, a rozmowa trwa, dopóki ktoś mówi. Koniec po ciszy albo po pożegnaniu („pa”, „dobranoc”).
- **Sposoby rozpoczęcia:** przycisk ✦ „Porozmawiaj z Aurorą”, **słowo wybudzające** (domyślnie „aurora”, rozpoznawane w całości na tablecie – model Vosk ~50 MB pobierany raz), **dwa machnięcia ręką** nad czujnikiem zbliżeniowym, usługa z HA (`/listen`).
- **Wykrywanie mowy na tablecie:** czeka na początek mowy (domyślnie 8 s), nagrywa do ciszy (1,2 s, maks. 30 s) i wysyła WAV do webhooka integracji. Rozpoznanie mowy, odpowiedź AI i głos robi Home Assistant.
- **„Twarz” Aurory:** pełnoekranowa zorza polarna, która reaguje na głos (słucha / myśli / mówi). Dotknięcie kończy rozmowę.
- **Prywatność:** słowo wybudzające działa tylko poza ciszą nocną (domyślnie 22:00–6:30) i opcjonalnie tylko, gdy ktoś jest przy tablecie. Dźwięk nie opuszcza tabletu, dopóki słowo nie padnie.
- Nowa sekcja ustawień „Aurora – rozmowa głosowa” (za PIN-em), nowe pola w `/status`: `voice`, `voice_enabled`, `wake_word`.
- Nowe adresy API: `/converse?url=…&text=…&person=…&listen=1&wait=8`, `/listen?person=…`, `/stoptalk`.

## Nowości w 2.6
- Oszczędniejsze wykrywanie ruchu (jak w Fully): najmniejsza rozdzielczość i najniższa liczba klatek kamery,
  a kamera jest wstrzymana, gdy ktoś używa ekranu (dotyk w ciągu 30 s). Panel działa płynniej.

## Nowości w 2.5

Połączenie wersji 2.4 (v1) i 2.4 (v2):
- **Zegar nocny z temperaturami** (na zewnątrz i w domu) oraz krótką informacją z HA (`/clock?out=8,5&in=21,0&info=...`).
- **Czujnik zbliżeniowy:** ręka przy ekranie wybudza panel (można wyłączyć z HA); stan trafia do HA i jako zdarzenie `proximity`.
- **Tytuł okna podglądu** (`/popup?...&title=...`), np. „Dzwonek – furtka”.
- **Komunikat głosowy z głośnością:** `/tts?text=...&volume=80&wake=1`.
- W `/status` doszły pola `night_enabled`, `fallback` i `proximity`; `/popup_close` działa jak `/closepopup` (zgodność z 2.4).

## Budowanie

Workflow `.github/workflows/build.yml` buduje debugowy APK po każdym commicie. Wejdź w **Actions → ostatnie uruchomienie → Artifacts → hapanel-apk**. Możesz też otworzyć projekt w Android Studio i zbudować APK (Build → Build APK).

APK jest podpisany dołączonym kluczem debug (`app/debug.keystore`), żeby kolejne wersje instalowały się „na wierzch”. To klucz publiczny, tylko do własnych buildów, nie do dystrybucji.

## Wydania i aktualizacje

Workflow buduje APK po każdym commicie (Actions → Artifacts, wygasają po 90 dniach). Aby wydać wersję:
1. Podbij `versionName` i `versionCode` w `app/build.gradle.kts` i zrób commit.
2. Releases → Create a new release, tag `vX.Y` zgodny z `versionName`, Publish.
3. Workflow sam zbuduje APK i dołączy go do wydania (`app-debug.apk`). Wydania zostają na stałe, starsze wersje pozostają do pobrania.

Integracja HA porównuje tag najnowszego wydania z zainstalowaną wersją i oferuje aktualizację. Ten sam podpis (dołączony klucz debug) pozwala aktualizować bez odinstalowywania.

## Instalacja i konfiguracja

1. Zainstaluj APK na tablecie (zezwól na instalację z nieznanych źródeł) i uruchom go. Zatwierdź zgodę na mikrofon.
2. Tablet pokaże ekran „Czekam na konfigurację z Home Assistant” z **kodem parowania** i **ogłosi się w sieci lokalnej (mDNS)**.
3. W HA pojawi się wykryte urządzenie *HA Panel* (Ustawienia → Urządzenia i usługi). Kliknij **Skonfiguruj**, wpisz kod parowania z ekranu tabletu, sprawdź ustawienia i zatwierdź. Integracja [hapanel-integration](https://github.com/bobby6robert/hapanel-integration) wyśle na tablet adres dashboardu i resztę ustawień oraz sparuje go losowym tokenem API (kod chroni przed konfiguracją przez obcego klienta w sieci).
4. Tego, czego Android nie pozwala nadać zdalnie (mikrofon, autostart, bateria, polski głos TTS), pilnuje **lista kontrolna** w ustawieniach tabletu (5 dotknięć w lewy górny róg) z przyciskami „Napraw”. HA pokazuje braki jako czujnik „Brakujące uprawnienia”.

Po ponownej instalacji aplikacji integracja wykryje niesparowany tablet i poprosi w HA o nowy kod z ekranu (ponowne parowanie), a potem wyśle ustawienia jeszcze raz. Ręczna konfiguracja (bez HA) nadal jest możliwa w ustawieniach tabletu.

Zalecane: stały adres IP tabletu (rezerwacja DHCP w routerze). Zmiana adresu jest zresztą wykrywana i aktualizowana automatycznie.

## API HTTP (GET, port 8765)

Jeśli ustawisz token API, dopisz do każdego adresu `token=...`.

| Endpoint | Działanie |
|---|---|
| `/popup?url=...&seconds=30&wake=1&title=...` | okno podglądu na wierzchu (url pełny lub ścieżka w HA), `/closepopup` zamyka |
| `/clock?out=...&in=...&info=...` | temperatury i informacja na zegarze nocnym |
| `/screenshot?scale=0.5&quality=70` | zrzut ekranu (JPEG) |
| `/export` | kopia ustawień w JSON (bez PIN-u i tokenów) |
| `/update?url=...` | pobiera APK z `https://github.com/...` i uruchamia instalację |
| `/info` | identyfikator, nazwa, wersja i informacja, czy wymagany jest token (bez tokena) |
| `/config?...` | zdalna konfiguracja (adresy, uśpienie, ruch, tryb nocny, PIN, parowanie kodem i tokenem), używana przez integrację |
| `/status` | stan w JSON (bateria, ładowanie, ruch, światło, jasność, głośność, Wi-Fi, adres, wersja) |
| `/wake`, `/sleep` | wybudzenie, uśpienie ekranu |
| `/brightness?value=0-255` | jasność (wyłącza auto-jasność) |
| `/auto?on=1\|0` | automatyczna jasność |
| `/load?url=...`, `/reload`, `/restart` | adres, odświeżenie, restart panelu |
| `/tts?text=...&wake=1&volume=0-100` | komunikat głosowy (`wake=1` budzi ekran, `volume` ustawia głośność) |
| `/playsound?url=...`, `/stopsound`, `/volume?value=0-100` | dźwięk i głośność |
| `/toast?text=...&wake=1` | krótki komunikat na ekranie |

## Symulator do testów

`tools/hapanel_mock.py` udaje tablet (to samo API), żeby przetestować integrację w HA bez urządzenia. Tylko biblioteka standardowa Pythona:

```
python3 tools/hapanel_mock.py --port 8765
```

Parametr `--code` ustawia kod parowania (domyślnie 123456). Z opcją `--mdns` (wymaga `pip install zeroconf`) symulator ogłasza się w sieci, więc HA wykryje go jak prawdziwy tablet.

Panel sterowania symulatora: `http://IP:8765/ui`.

## Bezpieczeństwo

API nie jest szyfrowane. Używaj go tylko w sieci lokalnej, ustaw token API i nie wystawiaj portu 8765 do internetu. Zdalny dostęp rób przez VPN.

## Licencja

Dodaj plik `LICENSE` wybranej licencji (np. MIT) przed publiczną dystrybucją.
