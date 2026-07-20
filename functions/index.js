const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

/**
 * Cloud Function que se dispara cuando cambia el estado de una llamada en /calls/{calleeNumber}
 * Envía una notificación Push de alta prioridad vía FCM al dispositivo receptor.
 */
exports.onCallStateChange = functions.firestore
  .document("calls/{calleeNumber}")
  .onWrite(async (change, context) => {
    const calleeNumber = context.params.calleeNumber;
    const afterData = change.after.exists ? change.after.data() : null;

    if (!afterData) return null;

    const status = afterData.status;
    const callerNumber = afterData.callerNumber || "";
    const isVideo = afterData.isVideo || false;

    // Buscar el usuario destino por su número de teléfono en Firestore
    const usersQuery = await admin.firestore()
      .collection("users")
      .where("number", "==", calleeNumber)
      .limit(1)
      .get();

    if (usersQuery.empty) {
      console.log(`No se encontró usuario con el número: ${calleeNumber}`);
      return null;
    }

    const targetUserDoc = usersQuery.docs[0].data();
    const fcmToken = targetUserDoc.fcmToken;

    if (!fcmToken) {
      console.log(`El usuario ${calleeNumber} no tiene un FCM token registrado.`);
      return null;
    }

    if (status === "ringing") {
      console.log(`Enviando PUSH de llamada entrante de ${callerNumber} a ${calleeNumber}...`);

      const message = {
        token: fcmToken,
        data: {
          type: "incoming_call",
          callerNumber: callerNumber,
          isVideo: String(isVideo),
          timestamp: String(Date.now())
        },
        android: {
          priority: "high",
          ttl: 30000 // Expira en 30 segundos si el teléfono no tiene conexión
        }
      };

      try {
        const response = await admin.messaging().send(message);
        console.log("Push de llamada enviado exitosamente:", response);
      } catch (error) {
        console.error("Error al enviar push FCM:", error);
      }
    } else if (status === "ended") {
      console.log(`Llamada finalizada. Enviando PUSH de cancelacion a ${calleeNumber}...`);

      const message = {
        token: fcmToken,
        data: {
          type: "call_ended",
          callerNumber: callerNumber
        },
        android: {
          priority: "high"
        }
      };

      try {
        await admin.messaging().send(message);
      } catch (error) {
        console.error("Error al enviar push cancelacion FCM:", error);
      }
    }

    return null;
  });
