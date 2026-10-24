# TVOffZ

Android TV aplikacija (pravljena za SBB EON box) koja u zadato vreme:

- prikaže **odbrojavanje preko ekrana** (podesivo, npr. 60 s), sa dugmetom **Otkaži**,
- zatim **ugasi box** (standby, kao dugme za napajanje na daljinskom) ili **izađe na Home ekran**,
- **zaključa aplikacije** do zadatog vremena (npr. 07:00): svaka pokrenuta aplikacija se odmah vraća na Home,
- **PIN** štiti podešavanja, otkazivanje odbrojavanja i ranije otključavanje.

Radi bez roota, preko Accessibility (Pristupačnost) servisa.

## Instalacija

1. Skini najnoviji APK iz [Releases](../../releases). Na boxu, u aplikaciji *Downloader*, ukucaj:
   `github.com/zzijad/tvoffz/releases/latest/download/TVOffZ.apk`
2. Dozvoli instalaciju iz nepoznatih izvora i instaliraj APK.
3. Otvori TVOffZ i uključi servis:
   **Podešavanja → Podešavanja uređaja → Pristupačnost → TVOffZ → Uključi**.
4. Podesi vreme, akciju, odbrojavanje, dane i "Zaključano do", pa **Postavi PIN**.
5. Pritisni **Testiraj odmah** da proveriš da box zaista odlazi u standby.

Ako na boxu nema menija Pristupačnost, servis se jednom uključuje preko ADB-a:

```
adb shell settings put secure enabled_accessibility_services rs.zzijad.tvoffz/rs.zzijad.tvoffz.TvOffService
adb shell settings put secure accessibility_enabled 1
```

## Kako radi

- Odbrojavanje kreće toliko sekundi **pre** zadatog vremena, da se akcija izvrši tačno u to vreme.
- Zaključavanje traje od zadatog vremena do "Zaključano do" (i preko ponoći). Ako su oba vremena ista, zaključavanja nema.
- **Otkaži** (uz PIN, ako je postavljen) otkazuje i akciju i zaključavanje za tu noć.
- Na ekranu "Aplikacije su zaključane" PIN otključava do kraja tog perioda.
- U periodu zaključavanja blokirana su i Podešavanja boxa, pa servis ne može da se isključi bez PIN-a.

## Ograničenja

- Bez roota nema pravog gašenja; box ide u standby, isto kao sa daljinskim.
- Blokirana aplikacija se otvori na delić sekunde pre nego što je servis vrati na Home.

## Build

GitHub Actions na svaki push na `main` napravi APK i objavi ga kao novi Release (`v1.0.N`).
APK je potpisan ključem `app/debug.keystore` iz repoa, pa se nova verzija uvek instalira preko stare.
