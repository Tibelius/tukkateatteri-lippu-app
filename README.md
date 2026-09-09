# Tukkateatteri

Lipunmyyntisovellus varausten, ovimyynnin ja Google Sheets -taulukoiden käsittelyyn. Toteutus käyttää Kotlinia, Jetpack Composea, ViewModel/Repository-rakennetta ja Roomia.

## Kehitys

Gradle käyttää Java 25:tä.

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest # emulaattori tai laite käynnissä
./gradlew installDebug
```

Tiedot tallennetaan paikalliseen Room-tietokantaan. Tietokantaskeeman muutos vaatii migraation, tietokantaversion noston ja `app/schemas`-tiedostojen päivittämisen. Sekä debug- että julkaisuversio käynnistyvät tyhjällä tietokannalla.

## Google Sheets

Sovellus käyttää laitteessa valittua Google-tiliä, ei Cloud-projektin omistajan tiliä. Tuonti onnistuu taulukosta, johon kyseisellä tilillä on katseluoikeus. Vienti vaatii muokkausoikeuden. Sovellus käsittelee vain käyttäjän antamaa Google Sheets -osoitetta.

Tuonnissa sovellus tulkitsee taulukon välilehdet esityspäiviksi. Esityksen taulukko-osoite ja esityspäivän välilehti tallennetaan paikallisesti. Yksittäisen esityspäivän voi päivittää valikosta ilman osoitteen antamista uudelleen.

Cloud-projektissa Sheets API:n on oltava käytössä. Android OAuth -asiakkaalle rekisteröidään paketti `fi.tukkateatteri` sekä debug- ja release-allekirjoitusten SHA-1-sormenjäljet. Testing-tilassa jokainen käyttäjä lisätään Audience-testikäyttäjäksi. Tuotantotilassa tätä ei tarvita.

## Julkaisu

`keystore.properties` on paikallinen tiedosto, jossa ovat `storeFile`, `storePassword`, `keyAlias` ja `keyPassword`. JKS-avainta tai salasanoja ei koskaan versionhallintaan. Julkaisuavaimen pitää pysyä samana, jotta uuden version voi asentaa nykyisen sovellusversion päälle.

```bash
./scripts/release.sh 0.4.1
```

Skripti ajetaan puhtaalla `main`-haaralla. Se tarkistaa allekirjoitusasetukset, kasvattaa `versionCode`-arvoa yhdellä ja asettaa annetun `versionName`-arvon. Sen jälkeen se ajaa lintin ja yksikkötestit, rakentaa allekirjoitetun APK:n sekä tarkistaa APK:n allekirjoituksen. Valmis tiedosto on `app/build/outputs/apk/release/Tukkateatteri-release.apk`.

Skripti ei tee committia, tagia eikä pushaa. Tarkista muutokset ja APK ensin, sitten commitoi `app/build.gradle.kts`, luo versiotagi ja julkaise APK GitHub Releasesissa.
