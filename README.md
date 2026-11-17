# Gestor de Stickers para WhatsApp

App Android (Kotlin + Jetpack Compose) para crear, editar y gestionar paquetes de stickers de WhatsApp.

## Funciones

- **Leer los stickers de WhatsApp**: muestra los stickers que WhatsApp guarda en
  `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` (también WhatsApp Business).
  Android exige conceder acceso a la carpeta una vez desde el selector del sistema; el permiso queda guardado.
  Puedes seleccionar varios y añadirlos a un paquete, o mantener pulsado uno para editarlo.
- **Paquetes**: crear, renombrar, cambiar autor, eliminar; añadir, reordenar, borrar stickers,
  cambiar emojis y elegir el icono del paquete (se genera solo con el primer sticker).
- **Enviar a WhatsApp / WhatsApp Business** con el diálogo oficial. La app detecta si el paquete ya
  está añadido y, al editarlo, incrementa `image_data_version` para que WhatsApp se actualice.
- **Editor de imagen**:
  - **Recortar**: recuadro con esquinas arrastrables, opción cuadrada.
  - **Lazo**: recorte a mano alzada trazando con el dedo.
  - **Inteligente**: quita el fondo automáticamente (ML Kit Subject Segmentation, en el dispositivo).
  - **Borrador**: pincel de tamaño ajustable para retocar.
  - **Borde**: sin borde o de cualquier color de la paleta, con grosor ajustable (contorno redondeado
    calculado con una transformada de distancia euclídea).
  - Girar, voltear y deshacer.
  - Al guardar, se genera un WebP de 512×512 con margen y < 100 KB, y se pide 1–3 emojis.
  - Se guarda el recorte original sin borde, así que puedes volver a editar un sticker y cambiar su borde.

## Requisitos de WhatsApp que aplica la app

3–30 stickers por paquete · 512×512 WebP < 100 KB · icono 96×96 < 50 KB · 1–3 emojis por sticker.
Los paquetes son de stickers estáticos (los animados de WhatsApp se importan con su primer fotograma).

## Compilar

Requiere JDK 17+ y el Android SDK (API 35).

```bash
./gradlew assembleDebug          # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # pruebas del procesado de imagen (Robolectric)
```

Android 8.0 (API 26) o superior. El recorte inteligente necesita los servicios de Google Play
(el modelo se descarga automáticamente la primera vez).

## Estructura

```
app/src/main/java/com/littledumbduck/stickers/
├── data/        Modelos, repositorio en disco (filesDir/packs) e importador
├── image/       Carga de imágenes, procesado (recorte, lazo, borde, WebP) y recorte inteligente
├── whatsapp/    ContentProvider para WhatsApp, intent de añadir paquete y lector de la carpeta de stickers
└── ui/          Pantallas Compose: inicio, paquete, stickers de WhatsApp y editor
```
