# 🚀 Guía de Despliegue de Cloud Functions para Simple Call App

Esta Cloud Function permite recibir llamadas con la app cerrada y sin notificaciones permanentes molestas, utilizando **Firebase Cloud Messaging (FCM)**.

---

## 📋 Requisitos Previos

1. Tener Node.js instalado (v18 o superior).
2. Tener Firebase CLI instalado:
   ```bash
   npm install -g firebase-tools
   ```
3. Estar en el **Plan Blaze (Pay as you go)** en la consola de Firebase (las Cloud Functions lo requieren, pero los primeros 2 millones de ejecuciones/mes son gratis).

---

## 🛠️ Instrucciones de Despliegue (Pasos simples)

1. Abre una terminal dentro de esta carpeta (`functions`):
   ```bash
   cd "E:\Descargas\Simple Call app\functions"
   ```

2. Inicia sesión en Firebase CLI (si no lo has hecho antes):
   ```bash
   firebase login
   ```

3. Vincula el proyecto con tu consola Firebase (selecciona tu proyecto de Simple Call App):
   ```bash
   firebase use --add
   ```

4. Instala las dependencias necesarias:
   ```bash
   npm install
   ```

5. Despliega la función a Firebase:
   ```bash
   firebase deploy --only functions
   ```

---

## ✅ ¡Listo!
Una vez desplegada, Firebase ejecutará automáticamente `onCallStateChange` cada vez que alguien haga una llamada. La función enviará un push notification de alta prioridad que despertará a la app de destino al instante, **incluso si la app está totalmente cerrada o el teléfono fue reiniciado**.
