# DatBoi

Port a 64 bit di **Dat Boi - Dat Game** (`com.rektstudios.daboi`, v1.33, Getrekt Studios, 2016).

L'APK originale era un gioco Unity 5 (Mono) con librerie native solo a 32 bit
(`armeabi-v7a`, `x86`): non si installa sui telefoni che supportano solo app a 64 bit
e non rispetta il requisito 64 bit di Google Play. Questa versione ricrea il gioco come
app Android nativa in Java + OpenGL ES 2.0, **senza alcuna libreria nativa**: ART la
esegue come processo a 64 bit su `arm64-v8a` / `x86_64` (e anche sui dispositivi a 32 bit).

<p align="center">
  <a href="dist/DatBoi-2.0-64bit.apk?raw=true">
    <img alt="Scarica l'APK" src="https://img.shields.io/badge/Scarica%20l'APK-Android%2064%20bit-3DDC84?style=for-the-badge&logo=android&logoColor=white" height="60">
  </a>
</p>

Tocca il pulsante per scaricare [`dist/DatBoi-2.0-64bit.apk`](dist/DatBoi-2.0-64bit.apk) (versione 2.1, minSdk 21 / Android 5.0, targetSdk 35).

## Cosa è stato ricreato

Tutto viene dall'APK originale: il codice C# (`Assembly-CSharp.dll`) decompilato e
reimplementato, la scena Unity (`level0`) letta oggetto per oggetto.

- **Fisica** (`Physics.java`): la ruota (1 kg, raggio 0,5, Y e Z bloccate, materiale
  `MaxFriction`) e il palo di equilibrio (0,5 kg, 0,234 x 0,8, angular drag 1) collegati da
  una HingeJoint. `controlWheel` applica `500 * deltaTime` di coppia per frame, cioè 10 Nm
  con il timestep fisso di 0,02 s. Gravità 9,81. Si muore quando il centro del palo
  scende sotto y = 0,4 (come in `gameControllerScript`).
- **Modello e animazione**: mesh del frog e del monociclo, skinning a 19 ossa e la clip
  `Basis` (4 s, 25 fps) decodificata dal formato "streamed" di Mecanim; la velocità
  dell'animazione segue la rotazione della ruota (`AnimSpeed`, velocità stato -0,65).
  Skinning su GPU.
- **Gioco**: punteggio dalla posizione di `loc`, record e distanza totale salvati,
  "oh shit waddup" a 10, 20, 30, 100, 200, 300, 1000, 2000, 3000 m e così via
  (l'originale lo suonava ogni 10 m), occhiali MLG
  a 10 m, fedora a 20 m, canna con fumo a 30 m (volano in posizione a 4 m/s), righello con
  tacca ogni metro, tacca rossa e numero ogni 10 m, testo "Ur boi cycled ... in total".
- **UI**: stessi testi, ancore, dimensioni e rotazioni della Canvas Unity (pulsante
  `help` e FAQ, "Start da Gaem", schermata "O shit u diededed", "restert?", attiva/disattiva
  i suoni, hitmarker + suono a ogni tocco). Controlli: tocca a sinistra/destra (o frecce
  sinistra/destra, A/D su tastiera).
- **Audio**: musica in loop (parte dopo 1,516 s), hitmarker, "k", "oh shit waddup".

### Extra a -10 m

Andando all'indietro fino a -10 m la musica di sottofondo diventa "I Keep Holding On"
(`assets/minus10.ogg`, +10% di volume su tutto il brano), in loop fino al
"restert?", poi torna la musica originale. Quando il brano arriva a 0:48 parte, dietro
la rana e davanti al terreno, il video "1 MLG" (`assets/mlg1.mp4`) con il verde reso
trasparente (chroma key nello shader); appena finisce parte il video "2 MLG"
(`assets/mlg2.mp4`, a sfondo pieno, senza trasparenza) e poi "3 MLG" (`assets/mlg3.mp4`,
verde trasparente). Dopo i tre video parte "Snoop Dogg" (`assets/snoop.mp4`, verde
trasparente) a ripetizione. Quando il brano arriva a 2:28 "Snoop Dogg" si ferma e
ripartono "1 MLG", "2 MLG" e "3 MLG", poi di nuovo "Snoop Dogg" a ripetizione; la stessa
sequenza riparte a 0:48 e a 2:28 di ogni ripetizione del brano, fino al "restert?".
Tra un video e l'altro non c'è mai uno stacco: ogni video ha il suo lettore, preparato una
volta sola all'avvio e poi solo fatto partire, fermato e riavvolto, così il video successivo
è sempre pronto; l'ultimo fotogramma resta a schermo finché il nuovo non ha il primo. I video MLG e "Snoop Dogg" sono muti e il loro audio è un'unica
traccia (`assets/mlgchain.ogg`, con "1 MLG" e "2 MLG" alzati allo stesso volume di "3 MLG",
circa -4,5 LUFS) che parte un istante prima di "1 MLG": così nessun video
resta fermo sul primo fotogramma ad aspettare l'audio. `snoop.mp4` contiene già "Snoop Dogg" ripetuto 10 volte
(95 s, più di quanto resta mai a schermo tra due serie MLG), perché il lettore di
Android si ferma mezzo secondo ogni volta che riparte da capo. Ogni volta che il brano finisce compare per qualche
secondo una frase a caso in stile Dat Boi ("1 more time 4 da boiz", ...). Questa frase e le scritte "BACKFLIP MODE ACTIVATED!!1!" e "MLG MODE ACTIVATED!!1!" sono nere e senza riquadro, come le altre scritte del gioco.

### Dopo la morte

La canzone di -10 m continua fino al "restert?", ma non succede nient'altro: nessun nuovo
video a 0:48 e 2:28, nessuno specchio, nessuna frase a fine canzone. In una partita MLG
alla morte come sfondo resta solo "Snoop Dogg" (l'audio dei video MLG si ferma); in
Backflip lo schermo resta specchiato com'era; in una partita normale il video dei 1000 m, se stava andando, si ferma insieme al suo audio.

### Modalità Backflip

Andando all'indietro oltre -30 m la partita diventa **Backflip** ("BACKFLIP MODE
ACTIVATED!!1!"): il suo record è la distanza più lontana raggiunta all'indietro. Se
torni in avanti sopra -30 m la partita torna MLG ("MLG MODE ACTIVATED!!1!"), e così via
ogni volta che passi la linea dei -30 m. Conta la modalità in cui muori: oltre -30 m il
record Backflip, sopra -30 m il record MLG. Il record Backflip si sblocca la prima volta, compare come
terza riga sotto "ur record" e "MLG" nella schermata di morte e passa da export/import dei progressi. La musica resta quella della modalità MLG, ma i video MLG
non ci sono: entrando in Backflip il video si ferma, e a 0:48 e 2:28 non parte nessun
video. Tornando in MLG i video ripartono al punto successivo del brano (0:48 o 2:28).

In modalità Backflip, ogni volta che il brano arriva a 0:48 tutto il gioco (scena, video e
scritte) si specchia orizzontalmente, e ogni volta che arriva a 2:28 si specchia
verticalmente. Gli specchi si sommano: al primo giro del brano a 0:48 è specchiato, a
2:28 anche capovolto; al secondo giro a 0:48 torna dritto in orizzontale e a 2:28 torna
dritto del tutto, e così via. I comandi seguono l'immagine: tocchi il lato dello schermo
che vedi. Uscendo dal Backflip o con "restert?" torna tutto normale.

### Record MLG

Un secondo record, **MLG**, funziona come "ur record" (la distanza più lontana raggiunta
in avanti) ma conta solo le partite in cui sei arrivato a -10 m; quelle partite non
contano per "ur record" (una partita è normale, MLG o Backflip, mai più di una). La prima di queste
partite lo sblocca; da allora compare sotto "ur record" nella schermata di morte. Viene
salvato e passa anche da export/import dei progressi.

### Velocità massime

Di fianco a ogni record (ur record, MLG, Backflip) c'è la velocità massima mai raggiunta
in quel tipo di partita, in km/h: in avanti per "ur record" e MLG, all'indietro per
Backflip. Una partita conta per lo
stesso tipo del suo record. Anche le velocità passano da export/import dei progressi.

### Terreno e righello

Il terreno bianco e la barra dei metri arrivano a 30 km (nell'originale finivano a 500 m
e 1000 m).

### Video della modalità normale

In una partita normale (che non è passata da -10 m) parte, dietro la rana, il video
`assets/normal1000.mp4` (verde reso trasparente) a 1000, 2000, 3000, 10000, 20000 e
30000 m, una volta per ciascun traguardo.

### Audio e fluidità

Il video della modalità normale ha lo stesso volume percepito della canzone base
(-10,5 LUFS) e "I Keep Holding On" è un po' più alta (-8,0 LUFS). Musica e video vengono
preparati su un thread separato, così quando partono il gioco e i comandi non si
bloccano; se comunque un fotogramma arriva in ritardo, il gioco rallenta per un istante
invece di fare un salto in avanti con i comandi fermi.

### Salvare i progressi

Nel pannello `help` ci sono due pulsanti in più: **export ur progress** salva record e
distanza totale in un file `datboi-progress.json` (dove vuoi: Download, Drive, ...), e
**import ur progress** li ricarica. L'importazione tiene sempre il valore migliore tra
quello attuale e quello del file, quindi non si perde niente. Utile per cambiare telefono
o reinstallare l'app. Il backup automatico di Android è disattivato: disinstallando e
reinstallando l'app si riparte da zero, e i progressi passano solo dal file esportato.

### Differenze rispetto all'originale

- Niente Unity Ads, Unity IAP e Facebook SDK: rimossi il pannello pubblicità e i pulsanti
  "Remove Ads" / "Restore Purchases". "share on dat facebuk?" apre il menu di condivisione
  di Android.
- Sugli schermi più larghi di 16:9 (quasi tutti i telefoni moderni) la UI mantiene la
  disposizione 16:9 invece di sovrapporre gli elementi.
- I salvataggi della vecchia app (record e distanza) non vengono importati.

## Build admin (privata)

`ADMIN=1 ./tools/build_apk.sh` produce `build/admin/DatBoi-admin.apk`: pacchetto
`com.rektstudios.daboi.admin` (si installa accanto al gioco, con salvataggi suoi), nome
"Dat Boi ADMIN". Serve a revisionare il gioco: dopo la morte tutto va avanti come se fossi
vivo, e in alto ci sono i pulsanti per saltare nella canzone (>0:45, >2:25, >3:30), per
portare dat boi a -11 m (MLG) o -31 m (Backflip), la rana invincibile e una riga con tempo
della canzone, giro, modalità, video in corso e specchi. L'APK admin non va pubblicato in
questo repository; quello normale (`Flavor.ADMIN = false`) non ha nessuna di queste funzioni.

## Struttura

```
app/src/main/
  AndroidManifest.xml
  java/com/rektstudios/daboi/
    MainActivity.java   activity a schermo intero, input da tastiera, condivisione
    Game.java           logica (gameControllerScript, settime, MoveTo, ...)
    Physics.java        ruota + palo di equilibrio
    GameRenderer.java   rendering OpenGL ES 2.0
    HudView.java        UI e controlli touch
    Model.java          caricamento di model.bin
    Sound.java, Save.java
  assets/               generati da tools/bake_assets.py
tools/
  bake_assets.py        estrae e converte gli asset dall'APK originale
  build_apk.sh          compila l'APK (senza Gradle)
```

## Compilare

Serve un JDK, `android.jar` e gli strumenti `aapt`, `d8` (o `dx`), `zipalign`, `apksigner`:
o un Android SDK (`ANDROID_HOME`) oppure, su Ubuntu/Debian,

```sh
sudo apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-17-jdk
./tools/build_apk.sh        # -> build/DatBoi.apk
```

Senza variabili, l'APK è firmato con una chiave di debug generata al momento. Per firmarlo
con la tua chiave: `KEYSTORE=... KEY_ALIAS=... KEY_PASS=... ./tools/build_apk.sh`.

Per rigenerare gli asset dall'APK originale (`pip install UnityPy`, `ffmpeg` per l'OGG):

```sh
unzip game.apk -d /tmp/datboi-apk
python3 tools/bake_assets.py /tmp/datboi-apk app/src/main/assets
```

## Installare

`adb install dist/DatBoi-2.0-64bit.apk`, oppure copia l'APK sul telefono e aprilo
(consentendo l'installazione da origini sconosciute; su Play Protect scegli "Installa
comunque"). L'APK è firmato con la chiave di debug fissa `tools/debug.keystore`, così le
build successive si installano come aggiornamento. Se hai una versione firmata con
un'altra chiave (l'app originale o build precedenti), disinstallala prima.

Per pubblicarlo su Google Play come aggiornamento della vecchia app serve la chiave di
firma originale (il package name `com.rektstudios.daboi` è invariato), e Play richiede
un Android App Bundle (`.aab`) invece dell'APK.
