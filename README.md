# Gestor de Stickers para WhatsApp

App Android (Kotlin + Jetpack Compose) para crear, editar y gestionar paquetes de stickers de WhatsApp.

## Funciones

- **Leer los stickers de WhatsApp**: muestra los stickers que WhatsApp guarda en
  `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` (también WhatsApp Business).
  Android exige conceder acceso a la carpeta una vez desde el selector del sistema; el permiso queda guardado.
  Puedes seleccionar varios y añadirlos a un paquete, o mantener pulsado uno para editarlo.
- **Paquetes estáticos o animados**: crear, renombrar, cambiar autor, eliminar; añadir, reordenar,
  borrar stickers, cambiar emojis y elegir el icono del paquete (se genera solo con el primer sticker).
- **Stickers animados**: se crean desde GIF, vídeo (primeros 8 s) o WebP animado, y los animados
  de WhatsApp se importan sin perder la animación (si ya cumplen los requisitos, se copian sin
  recomprimir). Todas las herramientas del editor se aplican a cada fotograma. Android no sabe leer
  ni escribir WebP animado fotograma a fotograma, así que la app incluye su propio
  códec de contenedor (`image/WebpCodec.kt`).
- **Enviar a WhatsApp / WhatsApp Business** con el diálogo oficial. La app detecta si el paquete ya
  está añadido y, al editarlo, incrementa `image_data_version` para que WhatsApp se actualice.
- **Vídeos de TikTok sin marca de agua**: pega el enlace (o usa *Compartir* en TikTok y elige
  esta app) y el vídeo se descarga sin marca de agua y se abre en el editor. Usa la dirección del
  reproductor web de TikTok (`playAddr`), que no lleva marca de agua, igual que yt-dlp; si TikTok
  solo ofrece la versión marcada, se descarga esa y se avisa. Se conecta solo a TikTok, sin
  servicios de terceros. Prueba real: `TIKTOK_LIVE_URL=<enlace> ./gradlew testDebugUnitTest`.
- **Stickers con sonido** (pestaña *Sonido* del editor). WhatsApp empezó a probar en noviembre de
  2026 los stickers con sonido (beta de Android 2.26.47.1): el sonido va con stickers animados,
  suena al tocar el icono del altavoz y se repite tres veces. Fuentes del sonido:
  - **Audio del propio vídeo**, que por defecto sigue al tramo elegido en *Duración* (o empieza en
    el fotograma elegido si el sticker es una imagen fija).
  - **Archivo de audio** (MP3, M4A, OGG, WAV, FLAC… lo que sepa leer el móvil): una canción, un efecto…
  - **Audio de otro vídeo**: la imagen de un vídeo con el sonido de otro.
  - **Nota de voz** grabada con el micrófono (máx. 10 s).
  - **Sonido guardado** al reeditar un sticker (se puede recortar, cambiar o recuperar).

  Para cada fuente: forma de onda con el tramo elegido (máx. 10 s), mover el tramo de 0,5 en 0,5 s,
  *Igualar al sticker* (misma duración que la animación), volumen 0–200 %, entrada y salida suaves,
  normalizar, y **mezclar por debajo el audio original del vídeo** con su propio volumen (p. ej. una
  música con las voces del vídeo de fondo). *Escuchar* reproduce el resultado final y arranca la
  animación a la vez. El sonido se guarda como AAC mono 44,1 kHz (`sound_*.m4a`) junto al sticker.
- **Fotograma de un vídeo**: en paquetes estáticos, al elegir un vídeo, GIF o WebP animado se elige
  el instante exacto (reproducción, barra, paso fotograma a fotograma y *Usar este fotograma*). En
  paquetes animados, *Fotograma fijo* hace lo mismo (imagen fija + sonido del vídeo, por ejemplo).
- **Llevar el sonido a WhatsApp**: WhatsApp todavía **no** permite que otras apps incluyan el sonido
  en un paquete (lo dicen en [WhatsApp/stickers#1163](https://github.com/WhatsApp/stickers/issues/1163):
  «está en la hoja de ruta»), así que el paquete se sigue enviando sin sonido y, para cada sticker con
  sonido, *Exportar para WhatsApp como vídeo* genera un MP4 512×512 (H.264 + AAC) con el fondo que
  elijas (blanco, negro o croma). Se guarda en la galería (*Stickers con sonido*) o se comparte, y en
  WhatsApp se crea con Stickers → **Crear** → el vídeo → botón de sonido → audio original. También se
  puede guardar solo el audio (Música/*Stickers con sonido*) para la opción *Importar archivo* de
  WhatsApp. Desde el menú del paquete se exportan todos de una vez. Cuando WhatsApp publique la API,
  el sonido ya está guardado con cada sticker.
- **Editor de imagen**:
  - **Duración** (vídeos, GIF y animaciones): línea de tiempo con miniaturas para elegir de qué
    segundo a qué segundo va el sticker (máx. 10 s). Arrastra los extremos o el tramo entero; si
    mientras arrastras subes el dedo, la línea de tiempo se amplía alrededor del tirador (cada
    ~40 dp duplica el zoom) para ajustar fotograma a fotograma, y el fotograma exacto se ve en grande.
    Las ediciones ya hechas (recorte, lazo, fondo, borrador…) se vuelven a aplicar al tramo nuevo.
    La vista previa reproduce el vídeo/GIF **entero** (con sonido, barra de posición y cabezal en la
    línea de tiempo) y tiene botones *Inicio aquí* / *Fin aquí* para marcar el instante que se está
    viendo; también se puede repetir solo el tramo elegido.
  - **Recortar**: recuadro con esquinas arrastrables, opción cuadrada.
  - **Lazo**: recorte a mano alzada trazando con el dedo.
  - **Inteligente**: quita el fondo automáticamente en el dispositivo. Usa ML Kit Subject
    Segmentation (personas, animales y objetos) y, si no hay servicios de Google Play o no se
    puede descargar el modelo, un modelo de personas incluido en la app que funciona sin conexión.
  - **Zoom**: pellizca con dos dedos para ampliar (hasta 10×) y moverte; un dedo sigue dibujando.
  - **Borrador**: pincel de tamaño ajustable para retocar.
  - **Borde**: sin borde o de cualquier color de la paleta, con grosor ajustable (contorno redondeado
    calculado con una transformada de distancia euclídea).
  - Girar, voltear y deshacer.
  - Al guardar, se genera un WebP de 512×512 con margen y < 100 KB, y se pide 1–3 emojis.
  - Se guarda el recorte original sin borde, así que puedes volver a editar un sticker y cambiar su borde.

## Requisitos de WhatsApp que aplica la app

3–30 stickers por paquete · 512×512 WebP < 100 KB (animados < 500 KB, fotogramas ≥ 8 ms,
máximo 10 s) · icono 96×96 < 50 KB · 1–3 emojis por sticker · sin mezclar animados y estáticos.

## Lo que no se puede hacer

Leer los **paquetes instalados** dentro de WhatsApp: WhatsApp los guarda en su almacenamiento
privado y Android no permite que otras apps accedan a él. La app lee los stickers de la carpeta
*WhatsApp Stickers*, que es lo único accesible.

## Compilar

Requiere JDK 17+ y el Android SDK (API 35).

```bash
./gradlew assembleDebug          # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # pruebas de imagen, códec WebP, clips, línea de tiempo, audio y fotograma (Robolectric)
```

Android 8.0 (API 26) o superior, móviles ARM. Los APK se firman con `app/debug.keystore` (clave de
depuración, no apta para Play Store) para que cada versión nueva se instale como actualización.

## Estructura

```
app/src/main/java/com/littledumbduck/stickers/
├── audio/       Sonido: decodificar/mezclar/codificar (MediaCodec), grabar, y exportar a MP4 para WhatsApp
├── data/        Modelos, repositorio en disco (filesDir/packs) e importador
├── image/       Carga de imágenes, procesado (recorte, lazo, borde, WebP) y recorte inteligente
├── whatsapp/    ContentProvider para WhatsApp, intent de añadir paquete y lector de la carpeta de stickers
└── ui/          Pantallas Compose: inicio, paquete, stickers de WhatsApp y editor
```
