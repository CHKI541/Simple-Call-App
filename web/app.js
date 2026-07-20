// Simple Call App - Web Application logic

// Configuración de Firebase (Extraída de google-services.json del proyecto Android)
const firebaseConfig = {
    apiKey: "AIzaSyAb3dF0OBwknGFxEezknHbMAJZ6WbG5njo",
    authDomain: "app-de-llamada.firebaseapp.com",
    projectId: "app-de-llamada",
    storageBucket: "app-de-llamada.firebasestorage.app",
    messagingSenderId: "717516447001",
    appId: "1:717516447001:web:db3864f772cd015822384a" // ID ficticio web compatible
};

// Inicializar Firebase
firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();
const googleProvider = new firebase.auth.GoogleAuthProvider();

// Variables globales de estado
let currentUser = null;
let myNumber = "";
let myName = "";
let activeChatNumber = "";
let activeChatName = "";

// WebRTC & Llamadas
let localStream = null;
let peerConnection = null;
let callTimer = null;
let callDurationSeconds = 0;
let isMuted = false;
let currentCallNumber = "";
let isIncomingCall = false;

// --- TELECONFERENCIA & MULTI-PEER ---
let activePeers = {}; // phoneNumber -> { peerConnection, isCaller, status, hold: boolean, remoteStream: MediaStream, candidatesListener: function }
let conferenceMode = false;
let organizerMode = false;
let participantToParticipantCallId = null;
let secondaryCallListener = null;

// Listeners activos en Firestore (para desmontar cuando corresponda)
let incomingCallListener = null;
let chatMessagesListener = null;
let chatsListListener = null;
let contactsListener = null;
let historyListener = null;
let callSessionListener = null; // listener activo de sesión de llamada en curso
const individualChatListeners = {}; // roomId -> listener

const peerConfig = {
    iceServers: [
        { urls: "stun:openrelay.metered.ca:80" },
        { 
            urls: "turn:openrelay.metered.ca:80?transport=udp", 
            username: "openrelayproject", 
            credential: "openrelayproject" 
        },
        { 
            urls: "turn:openrelay.metered.ca:443?transport=tcp", 
            username: "openrelayproject", 
            credential: "openrelayproject" 
        },
        { urls: "stun:stun.l.google.com:19302" },
        { urls: "stun:stun1.l.google.com:19302" }
    ],
    sdpSemantics: "unified-plan"
};

// Función helper para prevenir vulnerabilidades de XSS (Cross-Site Scripting) al inyectar HTML
function escapeHTML(str) {
    if (!str) return "";
    return str.toString()
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

// Elementos de Audio en el DOM
const ringtoneAudio = document.getElementById("audio-ringtone");
const notificationAudio = document.getElementById("audio-notification");
const remoteAudio = document.getElementById("remote-audio");

// --- INICIALIZACIÓN ---
document.addEventListener("DOMContentLoaded", () => {
    // Suscribirse a cambios en el estado de autenticación
    auth.onAuthStateChanged(user => {
        if (user) {
            currentUser = user;
            reloadUserAndVerify();
        } else {
            currentUser = null;
            myNumber = "";
            myName = "";
            showView("login-container");
            cleanupListeners();
        }
    });

    setupUIListeners();
    setupDialer();
    checkNotificationPermission();

    // Desbloquear audio al primer toque/click del usuario (política autoplay)
    const unlockAudio = () => {
        [ringtoneAudio, notificationAudio, remoteAudio].forEach(el => {
            if (el) {
                el.play().catch(() => {});
                setTimeout(() => { el.pause(); el.currentTime = 0; }, 100);
            }
        });
        document.removeEventListener("click", unlockAudio);
        document.removeEventListener("keydown", unlockAudio);
    };
    document.addEventListener("click", unlockAudio);
    document.addEventListener("keydown", unlockAudio);

    // Cortar llamada activa al cerrar/recargar la pestaña
    window.addEventListener("beforeunload", () => {
        const peerKeys = Object.keys(activePeers);
        peerKeys.forEach(num => {
            const peer = activePeers[num];
            const docId = peer.isCaller ? num : myNumber;
            db.collection("calls").doc(docId).update({ status: "ended" }).catch(() => {});
        });
        if (participantToParticipantCallId) {
            db.collection("calls").doc(participantToParticipantCallId).update({ status: "ended" }).catch(() => {});
        }
    });
});

// --- AUTENTICACIÓN Y VALIDACIONES ---

function reloadUserAndVerify() {
    showLoader(true, "Verificando sesión...");
    
    // Forzar recarga de usuario para validar estado
    currentUser.reload().then(() => {
        if (true || currentUser.emailVerified) {
            // Verificar si tiene perfil creado en Firestore
            db.collection("users").document ? "" : ""; // Compat API: db.collection().doc()
            db.collection("users").doc(currentUser.uid).get()
                .then(doc => {
                    if (doc.exists) {
                        const userData = doc.data();
                        if (userData.number) {
                            myNumber = userData.number;
                            myName = userData.name || "Usuario";
                            
                            // Guardar en almacenamiento local
                            localStorage.setItem("user_number", myNumber);
                            
                            // Entrar al Dashboard
                            setupDashboardUI();
                            startGlobalListeners();
                            showView("dashboard-container");
                        } else {
                            // Tiene perfil pero no asignó número
                            showView("number-container");
                        }
                    } else {
                        // Perfil nuevo
                        showView("number-container");
                    }
                })
                .catch(err => {
                    alert("Error al cargar perfil: " + err.message);
                    auth.signOut();
                });
        } else {
            // No verificado
            showView("verification-container");
        }
    }).catch(err => {
        // Error de conexión o sesión expirada
        showView("login-container");
    });
}

function setupDashboardUI() {
    document.getElementById("user-display-name").innerText = myName;
    document.getElementById("user-display-number").innerText = "Número: " + myNumber;
    document.getElementById("user-avatar-char").innerText = myName.charAt(0).toUpperCase();
    document.getElementById("settings-name").value = myName;
    showLoader(false);
}

// Iniciar escuchas globales (llamadas entrantes, mensajes)
function startGlobalListeners() {
    cleanupListeners();

    // 1. Escuchar llamadas VoIP dirigidas a mí
    incomingCallListener = db.collection("calls").doc(myNumber)
        .onSnapshot(doc => {
            if (doc.exists) {
                const callSession = doc.data();
                if (callSession.status === "ringing" && callSession.callerNumber !== myNumber) {
                    // Si ya estoy en una llamada, ignorar/rechazar
                    if (peerConnection) {
                        db.collection("calls").doc(myNumber).update({ status: "ended" });
                        return;
                    }
                    showIncomingCallPopup(callSession.callerNumber);
                } else if (callSession.status === "ended" || callSession.status === "rejected") {
                    // El caller terminó la llamada — el callee debe terminarla también
                    hideIncomingCallPopup();
                    if (peerConnection) {
                        endCallLocally();
                    }
                }
            } else {
                // El documento fue eliminado (caller limpió la sesión)
                hideIncomingCallPopup();
                if (isIncomingCall && peerConnection) {
                    endCallLocally();
                }
            }
        });

    // 2. Escuchar mensajes nuevos para notificaciones
    listenMessagesForNotifications();
}

// Escuchar los mensajes no leídos de los chats activos del usuario
function listenMessagesForNotifications() {
    // Escuchar salas de chat del usuario
    chatsListListener = db.collection("chats")
        .where("participants", "array-contains", myNumber)
        .onSnapshot(snapshot => {
            if (snapshot) {
                const currentRoomIds = snapshot.docs.map(doc => doc.id);
                
                // Desmontar salas inactivas
                for (const roomId in individualChatListeners) {
                    if (!currentRoomIds.includes(roomId)) {
                        individualChatListeners[roomId]();
                        delete individualChatListeners[roomId];
                    }
                }

                // Agregar listeners individuales
                snapshot.docs.forEach(doc => {
                    const roomId = doc.id;
                    if (!individualChatListeners[roomId]) {
                        individualChatListeners[roomId] = db.collection("chats").doc(roomId)
                            .collection("messages")
                            .where("toNumber", "==", myNumber)
                            .where("delivered", "==", false)
                            .onSnapshot(msgSnapshot => {
                                if (msgSnapshot) {
                                    const isMsgNotifEnabled = document.getElementById("settings-toggle-msg-notif").checked;
                                    
                                    msgSnapshot.docChanges().forEach(change => {
                                        if (change.type === "added") {
                                            const msg = change.doc.data();
                                            if (msg.id && msg.fromNumber !== myNumber) {
                                                // Marcar como entregado en Firestore
                                                db.collection("chats").doc(roomId)
                                                    .collection("messages").doc(msg.id)
                                                    .update({ delivered: true });

                                                // Si el chat no está abierto, mostrar notificación
                                                if (activeChatNumber !== msg.fromNumber) {
                                                    if (isMsgNotifEnabled) {
                                                        playNotificationSound();
                                                        showDesktopNotification("Mensaje de " + msg.fromNumber, msg.text, msg.fromNumber);
                                                    }
                                                    // Refrescar chats list
                                                    loadChatsList();
                                                }
                                            }
                                        }
                                    });
                                }
                            });
                    }
                });
            }
        });
}

function cleanupListeners() {
    if (incomingCallListener) incomingCallListener();
    if (chatsListListener) chatsListListener();
    if (chatMessagesListener) chatMessagesListener();
    if (contactsListener) contactsListener();
    if (historyListener) historyListener();
    
    for (const roomId in individualChatListeners) {
        individualChatListeners[roomId]();
    }
    
    incomingCallListener = null;
    chatsListListener = null;
    chatMessagesListener = null;
    contactsListener = null;
    historyListener = null;
}

// --- ACCIONES UI DE BOTONES ---

function setupUIListeners() {
    // Botones Login & Registro
    document.getElementById("btn-login").addEventListener("click", loginWithEmail);
    document.getElementById("btn-register").addEventListener("click", registerWithEmail);
    document.getElementById("btn-google-login").addEventListener("click", loginWithGoogle);
    document.getElementById("btn-forgot-password").addEventListener("click", sendPasswordReset);
    document.getElementById("btn-save-number").addEventListener("click", saveNumberProfile);
    
    // Verificación
    document.getElementById("btn-check-verified").addEventListener("click", reloadUserAndVerify);
    document.getElementById("btn-resend-verification").addEventListener("click", resendEmailVerification);
    document.getElementById("btn-cancel-verification").addEventListener("click", () => {
        auth.signOut();
        document.getElementById("verification-container").style.display = "none";
    });

    // Pestañas del sidebar
    const tabs = document.querySelectorAll(".nav-tab");
    tabs.forEach(tab => {
        tab.addEventListener("click", () => {
            tabs.forEach(t => t.classList.remove("active"));
            tab.classList.add("active");
            
            const tabId = tab.getAttribute("data-tab");
            const panels = document.querySelectorAll(".tab-panel");
            panels.forEach(p => p.classList.remove("active"));
            document.getElementById(tabId).classList.add("active");
            
            // Cargar datos correspondientes al panel activo
            if (tabId === "tab-chats") loadChatsList();
            if (tabId === "tab-contacts") loadContactsList();
            if (tabId === "tab-history") loadCallHistoryList();
        });
    });

    // Perfil
    document.getElementById("btn-update-name").addEventListener("click", updateProfileName);
    document.getElementById("btn-change-password-dialog").addEventListener("click", () => {
        document.getElementById("modal-change-password").style.display = "flex";
    });
    document.getElementById("btn-close-pass-dialog").addEventListener("click", () => {
        document.getElementById("modal-change-password").style.display = "none";
    });
    document.getElementById("btn-save-new-password").addEventListener("click", changePassword);
    document.getElementById("btn-sign-out").addEventListener("click", () => auth.signOut());

    // Crear contacto
    document.getElementById("btn-new-contact-dialog").addEventListener("click", () => {
        document.getElementById("modal-new-contact").style.display = "flex";
    });
    document.getElementById("btn-close-contact-dialog").addEventListener("click", () => {
        document.getElementById("modal-new-contact").style.display = "none";
    });
    document.getElementById("btn-save-new-contact").addEventListener("click", addContact);

    // Iniciar Chat
    document.getElementById("btn-new-chat-dialog").addEventListener("click", () => {
        document.getElementById("modal-new-chat").style.display = "flex";
    });
    document.getElementById("btn-close-chat-dialog").addEventListener("click", () => {
        document.getElementById("modal-new-chat").style.display = "none";
    });
    document.getElementById("btn-start-new-chat").addEventListener("click", startNewChat);

    // Borrar historial
    document.getElementById("btn-clear-history-confirm").addEventListener("click", clearCallHistory);

    // Chat activo e inputs
    document.getElementById("btn-chat-send").addEventListener("click", sendChatMessage);
    document.getElementById("chat-message-input").addEventListener("keypress", (e) => {
        if (e.key === "Enter") sendChatMessage();
    });

    // Llamadas desde cabecera de chat
    document.getElementById("btn-chat-call-voip").addEventListener("click", () => {
        initiateVoipCall(activeChatNumber);
    });

    // Controles de llamada activa
    document.getElementById("btn-call-hangup").addEventListener("click", rejectOrHangupCall);
    document.getElementById("btn-call-mute").addEventListener("click", toggleCallMute);
    document.getElementById("btn-call-speaker").addEventListener("click", toggleCallSpeaker);
    
    // Controles de Conferencia y Minimización
    document.getElementById("btn-call-minimize").addEventListener("click", toggleCallMinimize);
    document.getElementById("btn-show-add-participant").addEventListener("click", () => {
        const container = document.getElementById("add-participant-container");
        container.style.display = (container.style.display === "none") ? "block" : "none";
    });
    document.getElementById("btn-add-participant-dial").addEventListener("click", () => {
        const target = document.getElementById("add-participant-input").value.trim();
        if (target) {
            initiateVoipCall(target, true); // True indicating second participant
            document.getElementById("add-participant-input").value = "";
            document.getElementById("add-participant-container").style.display = "none";
        }
    });
    document.getElementById("btn-call-merge").addEventListener("click", mergeCallsInConference);
    document.getElementById("btn-call-swap").addEventListener("click", swapCallsOnHold);

    // Botón para desbloquear el audio (autoplay del navegador)
    document.getElementById("btn-unlock-audio").addEventListener("click", () => {
        remoteAudio.play().then(() => {
            document.getElementById("btn-unlock-audio").style.display = "none";
            console.log("Audio desbloqueado por el usuario");
        }).catch(err => console.warn("Error al desbloquear audio:", err));
    });

    // Alertas de llamada entrante popup
    document.getElementById("btn-incoming-accept").addEventListener("click", acceptIncomingCall);
    document.getElementById("btn-incoming-reject").addEventListener("click", rejectIncomingCall);

    // Banner de notificaciones
    document.getElementById("btn-enable-notif").addEventListener("click", requestNotificationPermission);
    document.getElementById("btn-close-banner").addEventListener("click", () => {
        document.getElementById("notif-banner").style.display = "none";
    });
}

// --- FUNCIONES DE AUTENTICACIÓN ---

function loginWithEmail() {
    const email = document.getElementById("auth-email").value.trim();
    const password = document.getElementById("auth-password").value.trim();
    if (!email || !password) return alert("Completa todos los campos");
    
    showLoader(true, "Iniciando sesión...");
    auth.signInWithEmailAndPassword(email, password)
        .catch(err => {
            showLoader(false);
            alert("Error: " + err.message);
        });
}

function registerWithEmail() {
    const email = document.getElementById("auth-email").value.trim();
    const password = document.getElementById("auth-password").value.trim();
    if (!email || !password) return alert("Completa todos los campos");
    if (password.length < 6) return alert("La contraseña debe tener al menos 6 caracteres");

    showLoader(true, "Registrando usuario...");
    auth.createUserWithEmailAndPassword(email, password)
        .then(result => {
            // Crear perfil inicial en Firestore
            return db.collection("users").doc(result.user.uid).set({
                uid: result.user.uid,
                email: email,
                name: "",
                number: "",
                status: "offline"
            }).then(() => {
                // Enviar verificación DESPUÉS de guardar el perfil
                return result.user.sendEmailVerification();
            }).then(() => {
                showLoader(false);
                showView("verification-container");
            });
        })
        .catch(err => {
            showLoader(false);
            alert("Error al registrarse: " + err.message);
        });
}

function loginWithGoogle() {
    showLoader(true, "Iniciando sesión con Google...");
    auth.signInWithPopup(googleProvider)
        .then(result => {
            currentUser = result.user;
            // Verificar si ya tiene perfil en Firestore
            return db.collection("users").doc(result.user.uid).get();
        })
        .then(doc => {
            if (!doc.exists || !doc.data().number) {
                // Crear/actualizar perfil base si no existe
                return db.collection("users").doc(currentUser.uid).set({
                    uid: currentUser.uid,
                    email: currentUser.email,
                    name: currentUser.displayName || "",
                    number: "",
                    status: "online"
                }, { merge: true }).then(() => {
                    showLoader(false);
                    showView("number-container");
                    // Pre-llenar el nombre si viene de Google
                    if (currentUser.displayName) {
                        document.getElementById("profile-name").value = currentUser.displayName;
                    }
                });
            } else {
                // Ya tiene perfil completo
                reloadUserAndVerify();
            }
        })
        .catch(err => {
            showLoader(false);
            if (err.code !== "auth/popup-closed-by-user") {
                alert("Error con Google: " + err.message);
            }
        });
}

function sendPasswordReset() {
    const email = document.getElementById("auth-email").value.trim();
    if (!email) return alert("Ingresa tu correo primero en el campo de texto");
    
    auth.sendPasswordResetEmail(email)
        .then(() => alert("✅ Correo de reseteo enviado exitosamente a " + email))
        .catch(err => alert("Error: " + err.message));
}

function saveNumberProfile() {
    const name = document.getElementById("profile-name").value.trim();
    const number = document.getElementById("profile-number").value.trim();
    if (!name || !number) return alert("Ingresa tu nombre y el número deseado");

    showLoader(true, "Guardando número...");

    // Verificar si el número ya está tomado
    db.collection("users").where("number", "==", number).get()
        .then(snapshot => {
            if (!snapshot.empty) {
                showLoader(false);
                return alert("El número ya está registrado por otro usuario");
            }

            // Guardar perfil
            db.collection("users").doc(currentUser.uid).set({
                uid: currentUser.uid,
                email: currentUser.email,
                name: name,
                number: number,
                status: "online"
            }, { merge: true }).then(() => {
                myNumber = number;
                myName = name;
                localStorage.setItem("user_number", myNumber);
                setupDashboardUI();
                startGlobalListeners();
                showView("dashboard-container");
            });
        })
        .catch(err => {
            showLoader(false);
            alert("Error: " + err.message);
        });
}

function resendEmailVerification() {
    if (currentUser) {
        currentUser.sendEmailVerification()
            .then(() => alert("Correo de verificación reenviado"))
            .catch(err => alert("Error: " + err.message));
    }
}

function updateProfileName() {
    const newName = document.getElementById("settings-name").value.trim();
    if (!newName) return alert("El nombre no puede estar vacío");

    db.collection("users").doc(currentUser.uid).update({ name: newName })
        .then(() => {
            myName = newName;
            document.getElementById("user-display-name").innerText = myName;
            document.getElementById("user-avatar-char").innerText = myName.charAt(0).toUpperCase();
            alert("Perfil actualizado correctamente");
        })
        .catch(err => alert("Error: " + err.message));
}

function changePassword() {
    const newPass = document.getElementById("new-password-input").value.trim();
    if (newPass.length < 6) return alert("La contraseña debe tener al menos 6 caracteres");

    currentUser.updatePassword(newPass)
        .then(() => {
            document.getElementById("modal-change-password").style.display = "none";
            document.getElementById("new-password-input").value = "";
            alert("Contraseña actualizada exitosamente");
        })
        .catch(err => alert("Error al actualizar contraseña: " + err.message));
}

// --- GESTIÓN DE CONTACTOS ---

function loadContactsList() {
    const listContainer = document.getElementById("contacts-list");
    listContainer.innerHTML = '<div class="text-center p-4">Cargando contactos...</div>';

    contactsListener = db.collection("users").doc(currentUser.uid)
        .collection("contacts")
        .onSnapshot(snapshot => {
            listContainer.innerHTML = "";
            if (!snapshot || snapshot.empty) {
                listContainer.innerHTML = '<div class="empty-state"><h4>No tienes contactos guardados</h4></div>';
                return;
            }

            snapshot.docs.forEach(doc => {
                const contact = doc.data();
                const item = document.createElement("div");
                item.className = "list-item";
                
                const nameEscaped = escapeHTML(contact.name);
                const numberEscaped = escapeHTML(contact.number);
                const avatarChar = nameEscaped.length > 0 ? nameEscaped.charAt(0).toUpperCase() : "?";

                item.innerHTML = `
                    <div class="profile-avatar">${avatarChar}</div>
                    <div class="list-item-info">
                        <div class="list-item-title">${nameEscaped}</div>
                        <div class="list-item-subtitle">Tel: ${numberEscaped}</div>
                    </div>
                    <div class="list-item-actions">
                        <button class="material-icons btn-item-action chat">forum</button>
                        <button class="material-icons btn-item-action call">phone</button>
                        <button class="material-icons btn-item-action delete" style="color: var(--hangup-red);">delete</button>
                    </div>
                `;

                // Asignar listeners programáticamente para evitar inyecciones XSS en atributos onclick
                item.querySelector(".chat").addEventListener("click", () => openDirectChat(contact.number, contact.name));
                item.querySelector(".call").addEventListener("click", () => initiateVoipCall(contact.number));
                item.querySelector(".delete").addEventListener("click", () => deleteContact(contact.id));

                listContainer.appendChild(item);
            });
        });
}

function addContact() {
    const name = document.getElementById("new-contact-name").value.trim();
    const number = document.getElementById("new-contact-number").value.trim();
    if (!name || !number) return alert("Completa todos los campos");

    const contactId = db.collection("users").doc(currentUser.uid).collection("contacts").doc().id;
    db.collection("users").doc(currentUser.uid).collection("contacts").doc(contactId).set({
        id: contactId,
        name: name,
        number: number
    }).then(() => {
        document.getElementById("modal-new-contact").style.display = "none";
        document.getElementById("new-contact-name").value = "";
        document.getElementById("new-contact-number").value = "";
        alert("Contacto agregado");
    }).catch(err => alert("Error: " + err.message));
}

window.deleteContact = function(contactId) {
    if (confirm("¿Estás seguro de eliminar este contacto?")) {
        db.collection("users").doc(currentUser.uid).collection("contacts").doc(contactId).delete()
            .then(() => alert("Contacto eliminado"));
    }
};

// --- GESTIÓN DE CHATS Y MENSAJES ---

function loadChatsList() {
    const listContainer = document.getElementById("chats-list");
    listContainer.innerHTML = '<div class="text-center p-4">Cargando conversaciones...</div>';

    // Cargar contactos locales en un mapa para resolver nombres
    db.collection("users").doc(currentUser.uid).collection("contacts").get()
        .then(contactsSnapshot => {
            const contactMap = {};
            contactsSnapshot.forEach(doc => {
                const c = doc.data();
                contactMap[c.number] = c.name;
            });

            db.collection("chats")
                .where("participants", "array-contains", myNumber)
                .get() // Usamos get para ordenarlo en memoria y evitar requerir índices compuestos
                .then(snapshot => {
                    listContainer.innerHTML = "";
                    if (snapshot.empty) {
                        listContainer.innerHTML = '<div class="empty-state"><h4>No tienes chats abiertos</h4></div>';
                        return;
                    }

                    // Ordenar localmente por lastMessageTimestamp descendente
                    const chatRooms = snapshot.docs.map(doc => doc.data())
                        .sort((a, b) => b.lastMessageTimestamp - a.lastMessageTimestamp);

                    chatRooms.forEach(room => {
                        const otherNumber = room.participants.find(p => p !== myNumber) || myNumber;
                        const otherName = contactMap[otherNumber] || otherNumber;
                        const dateStr = formatTimestamp(room.lastMessageTimestamp);
                        
                        const otherNameEscaped = escapeHTML(otherName);
                        const lastMsgEscaped = room.lastMessageText ? escapeHTML(room.lastMessageText) : "Sin mensajes";
                        const avatarChar = otherNameEscaped.length > 0 ? otherNameEscaped.charAt(0).toUpperCase() : "?";

                        const item = document.createElement("div");
                        item.className = "list-item" + (activeChatNumber === otherNumber ? " active-bg" : "");
                        item.onclick = () => openDirectChat(otherNumber, otherName);
                        
                        item.innerHTML = `
                            <div class="profile-avatar">${avatarChar}</div>
                            <div class="list-item-info">
                                <div class="list-item-header">
                                    <div class="list-item-title">${otherNameEscaped}</div>
                                    <div class="list-item-time">${dateStr}</div>
                                </div>
                                <div class="list-item-subtitle">${lastMsgEscaped}</div>
                            </div>
                        `;
                        listContainer.appendChild(item);
                    });
                });
        });
}

function startNewChat() {
    const target = document.getElementById("new-chat-number").value.trim();
    if (!target) return alert("Ingresa un número");
    if (target === myNumber) return alert("No puedes chatear contigo mismo");

    document.getElementById("modal-new-chat").style.display = "none";
    document.getElementById("new-chat-number").value = "";
    
    openDirectChat(target, target);
}

window.openDirectChat = function(otherNumber, otherName) {
    activeChatNumber = otherNumber;
    activeChatName = otherName;

    // Mostrar el contenedor de chat y ocultar el empty state
    document.getElementById("chat-empty-state").style.display = "none";
    document.getElementById("chat-active-container").style.display = "flex";

    document.getElementById("active-chat-name").innerText = otherName;
    document.getElementById("active-chat-number").innerText = otherNumber;

    // Para responsividad en móviles
    document.body.classList.add("mobile-chat-active");

    // Desmontar listener del chat anterior
    if (chatMessagesListener) chatMessagesListener();

    const roomId = getChatRoomId(myNumber, otherNumber);
    
    // Escuchar mensajes en tiempo real ordenados por timestamp
    chatMessagesListener = db.collection("chats").doc(roomId)
        .collection("messages")
        .orderBy("timestamp", "asc")
        .onSnapshot(snapshot => {
            const container = document.getElementById("chat-messages-scroll");
            container.innerHTML = "";

            if (!snapshot || snapshot.empty) {
                container.innerHTML = '<div class="text-center p-4" style="color: var(--text-light-gray)">No hay mensajes en esta conversación</div>';
                return;
            }

            const readReceiptsEnabled = document.getElementById("settings-toggle-read-receipts").checked;

            snapshot.docs.forEach(doc => {
                const msg = doc.data();
                
                // Marcar como entregados y leídos si es entrante
                if (msg.fromNumber === otherNumber) {
                    const updateData = {};
                    if (!msg.delivered) updateData.delivered = true;
                    if (readReceiptsEnabled && !msg.read) updateData.read = true;
                    
                    if (Object.keys(updateData).length > 0) {
                        db.collection("chats").doc(roomId).collection("messages").doc(msg.id).update(updateData);
                    }
                }

                // Resolver marca de tiempo de forma segura (retrocompatible)
                let msgTime = Date.now();
                if (msg.timestamp) {
                    if (typeof msg.timestamp === "number") msgTime = msg.timestamp;
                    else if (msg.timestamp.seconds) msgTime = msg.timestamp.seconds * 1000;
                    else if (msg.timestamp.toDate) msgTime = msg.timestamp.toDate().getTime();
                }
                const formattedTime = new Date(msgTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

                const row = document.createElement("div");
                row.className = "message-row " + (msg.fromNumber === myNumber ? "sent" : "received");
                
                let ticks = "";
                if (msg.fromNumber === myNumber) {
                    if (msg.read) ticks = '<span class="material-icons message-status read" style="font-size: 14px; margin-left: 2px;">done_all</span>';
                    else if (msg.delivered) ticks = '<span class="material-icons message-status" style="font-size: 14px; margin-left: 2px; color: var(--text-light-gray)">done_all</span>';
                    else ticks = '<span class="material-icons message-status" style="font-size: 14px; margin-left: 2px; color: var(--text-light-gray)">done</span>';
                }

                const textEscaped = escapeHTML(msg.text);

                row.innerHTML = `
                    <div class="message-bubble">
                        <div class="message-text">${textEscaped}</div>
                        <div class="message-info">
                            <span>${formattedTime}</span>
                            ${ticks}
                        </div>
                    </div>
                `;
                container.appendChild(row);
            });

            // Auto Scroll a la última burbuja
            container.scrollTop = container.scrollHeight;
        });

    // Cargar de nuevo la lista para marcar la selección
    loadChatsList();
};

function sendChatMessage() {
    const text = document.getElementById("chat-message-input").value.trim();
    if (!text || !activeChatNumber) return;

    document.getElementById("chat-message-input").value = "";

    const roomId = getChatRoomId(myNumber, activeChatNumber);
    const messagesRef = db.collection("chats").doc(roomId).collection("messages");
    const docId = messagesRef.doc().id;

    const message = {
        id: docId,
        fromNumber: myNumber,
        toNumber: activeChatNumber,
        text: text,
        timestamp: firebase.firestore.FieldValue.serverTimestamp(),
        delivered: false,
        read: false
    };

    messagesRef.doc(docId).set(message).then(() => {
        // Actualizar último mensaje de la sala de chat
        db.collection("chats").doc(roomId).set({
            roomId: roomId,
participants: [myNumber, activeChatNumber],
            lastMessageText: text,
            lastMessageTimestamp: Date.now(),
            lastSender: myNumber
        }, { merge: true });
    }).catch(err => alert("Error al enviar: " + err.message));
}

// --- LLAMADAS VOIP (WEBRTC MESH & TELECONFERENCIA) ---

// Agregar track/stream a elemento de audio separado por participante
function addRemoteAudio(phoneNumber, streamOrTrack) {
    let audioEl = document.getElementById(`remote-audio-${phoneNumber}`);
    if (!audioEl) {
        audioEl = document.createElement("audio");
        audioEl.id = `remote-audio-${phoneNumber}`;
        audioEl.autoplay = true;
        audioEl.playsinline = true;
        document.body.appendChild(audioEl);
    }
    
    if (streamOrTrack instanceof MediaStream) {
        audioEl.srcObject = streamOrTrack;
    } else {
        if (!audioEl.srcObject) {
            audioEl.srcObject = new MediaStream();
        }
        audioEl.srcObject.addTrack(streamOrTrack);
    }
    
    audioEl.play().then(() => {
        console.log(`Audio remoto reproduciendo para: ${phoneNumber}`);
    }).catch(err => {
        console.warn(`Autoplay bloqueado para ${phoneNumber}, mostrando botón de desbloqueo:`, err);
        document.getElementById("btn-unlock-audio").style.display = "flex";
    });
}

function removeRemoteAudio(phoneNumber) {
    const audioEl = document.getElementById(`remote-audio-${phoneNumber}`);
    if (audioEl) {
        audioEl.pause();
        audioEl.srcObject = null;
        audioEl.remove();
    }
}

function updateConferenceUI() {
    const confContainer = document.getElementById("conference-container");
    const listEl = document.getElementById("conference-participants-list");
    const showAddBtn = document.getElementById("btn-show-add-participant");
    const mergeBtn = document.getElementById("btn-call-merge");
    const swapBtn = document.getElementById("btn-call-swap");
    
    const peerKeys = Object.keys(activePeers);
    
    if (peerKeys.length <= 1) {
        confContainer.style.display = "none";
        // Si hay una llamada activa y no hay conferencia, mostrar botón para añadir
        if (peerKeys.length === 1 && activePeers[peerKeys[0]].status === "connected") {
            showAddBtn.style.display = "block";
        } else {
            showAddBtn.style.display = "none";
        }
        mergeBtn.style.display = "none";
        swapBtn.style.display = "none";
        return;
    }
    
    // Si hay más de un participante, mostrar sección de conferencia
    confContainer.style.display = "block";
    showAddBtn.style.display = "none"; // Ya no se pueden añadir más
    listEl.innerHTML = "";
    
    let hasHold = false;
    let hasActive = false;
    
    peerKeys.forEach(num => {
        const peer = activePeers[num];
        const statusText = peer.hold ? "En espera" : (peer.status === "connected" ? "Conectado" : "Marcando...");
        const statusClass = peer.hold ? "hold" : (peer.status === "connected" ? "connected" : "ringing");
        
        if (peer.hold) hasHold = true;
        else if (peer.status === "connected") hasActive = true;
        
        const item = document.createElement("div");
        item.className = "conference-item";
        item.innerHTML = `
            <span class="name">${num}</span>
            <div style="display: flex; align-items: center; gap: 8px;">
                <span class="status ${statusClass}">${statusText}</span>
                ${organizerMode ? `
                    <button class="btn-action-small" onclick="toggleParticipantHold('${num}')" title="${peer.hold ? 'Reanudar' : 'Retener'}">
                        <span class="material-icons" style="font-size:16px;">${peer.hold ? 'play_arrow' : 'pause'}</span>
                    </button>
                ` : ''}
            </div>
        `;
        listEl.appendChild(item);
    });
    
    // Si somos el organizador, habilitamos fusión o alternación
    if (organizerMode) {
        if (hasHold && hasActive) {
            mergeBtn.style.display = "block";
            swapBtn.style.display = "block";
        } else if (hasHold && !hasActive) {
            // Todos en espera
            mergeBtn.style.display = "none";
            swapBtn.style.display = "block";
        } else {
            // Todos activos
            mergeBtn.style.display = "none";
            swapBtn.style.display = "none";
        }
    } else {
        mergeBtn.style.display = "none";
        swapBtn.style.display = "none";
    }
}

// Iniciar Llamada VoIP
function initiateVoipCall(targetNumber, isSecond = false) {
    if (!targetNumber) return alert("Ingresa un número");
    if (targetNumber === myNumber) return alert("No puedes llamarte a ti mismo");
    
    // Verificar soporte de micrófono
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        alert("Error de seguridad: Tu navegador bloquea el acceso al micrófono.\n\n" +
              "Utiliza HTTPS o localhost para poder realizar llamadas.");
        return;
    }
    
    if (!isSecond) {
        // Reiniciar estados de llamadas
        activePeers = {};
        conferenceMode = false;
        organizerMode = false;
        isIncomingCall = false;
        currentCallNumber = targetNumber;
        
        document.getElementById("call-user-name").innerText = targetNumber;
        document.getElementById("call-user-number").innerText = targetNumber;
        document.getElementById("call-status-label").innerText = "Marcando...";
        document.getElementById("call-timer-label").style.display = "none";
        document.getElementById("btn-show-add-participant").style.display = "none";
        document.getElementById("conference-container").style.display = "none";
        document.getElementById("add-participant-container").style.display = "none";
        document.getElementById("call-overlay").style.display = "flex";
        document.getElementById("call-overlay").classList.remove("minimized");
    } else {
        // Llamada a segundo participante (organizador de conferencia)
        organizerMode = true;
        document.getElementById("call-status-label").innerText = "Llamando a participante...";
        // Retener al primer participante
        const peerKeys = Object.keys(activePeers);
        if (peerKeys.length > 0) {
            toggleParticipantHold(peerKeys[0], true);
        }
    }
    
    const startCall = (stream) => {
        localStream = stream;
        startMicDiagnostics(localStream);
        
        const pc = new RTCPeerConnection(peerConfig);
        peerConnection = pc; // Referencia principal compatible
        activePeers[targetNumber] = {
            peerConnection: pc,
            isCaller: true,
            status: "ringing",
            hold: false
        };
        
        // Adjuntar tracks locales
        localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
        
        pc.onconnectionstatechange = () => {
            console.log(`Connection state with ${targetNumber}:`, pc.connectionState);
            if (pc.connectionState === "connected") {
                activePeers[targetNumber].status = "connected";
                if (!isSecond) {
                    document.getElementById("call-status-label").innerText = "Llamada Conectada";
                    startCallTimer();
                }
                updateConferenceUI();
            } else if (pc.connectionState === "failed" || pc.connectionState === "disconnected") {
                console.warn(`Connection failed with ${targetNumber}`);
                hangupPeer(targetNumber);
            }
        };
        
        pc.ontrack = event => {
            console.log(`Track recibido de ${targetNumber}`);
            if (event.streams && event.streams[0]) {
                addRemoteAudio(targetNumber, event.streams[0]);
            } else {
                addRemoteAudio(targetNumber, event.track);
            }
        };
        
        pc.onicecandidate = event => {
            if (event.candidate) {
                sendIceCandidateToFirebase(targetNumber, true, event.candidate);
            }
        };
        
        // Crear Oferta SDP
        pc.createOffer().then(offer => {
            pc.setLocalDescription(offer).then(() => {
                db.collection("calls").doc(targetNumber).set({
                    callerNumber: myNumber,
                    calleeNumber: targetNumber,
                    status: "ringing",
                    offerSdp: offer.sdp,
                    answerSdp: null,
                    hold: false
                });
                
                listenRemoteIceCandidates(targetNumber, false, pc);
                listenToCallSessionUpdates(targetNumber, targetNumber);
                updateConferenceUI();
            });
        }).catch(err => {
            console.error("Error al crear oferta:", err);
            hangupPeer(targetNumber);
        });
    };
    
    if (localStream) {
        startCall(localStream);
    } else {
        navigator.mediaDevices.getUserMedia({ audio: true }).then(startCall).catch(err => {
            alert("Error al acceder al micrófono: " + err.message);
            endCallLocally();
        });
    }
}

// Escuchar cambios de sesión
function listenToCallSessionUpdates(docId, participantNumber) {
    let remoteDescriptionSet = false;
    const listener = db.collection("calls").doc(docId)
        .onSnapshot(doc => {
            if (!doc.exists) {
                console.log(`La sesión con ${participantNumber} fue eliminada de Firebase`);
                hangupPeer(participantNumber);
                return;
            }
            
            const session = doc.data();
            
            // Si el participante rechazó o colgó
            if (session.status === "rejected" || session.status === "ended") {
                hangupPeer(participantNumber);
                return;
            }
            
            // Si contestó
            if (session.status === "answered" && session.answerSdp && !remoteDescriptionSet) {
                remoteDescriptionSet = true;
                const pc = activePeers[participantNumber]?.peerConnection;
                if (pc) {
                    const desc = new RTCSessionDescription({ type: 'answer', sdp: session.answerSdp });
                    pc.setRemoteDescription(desc).catch(e => console.error("setRemoteDescription failed:", e));
                }
            }
            
            // Escuchar estado de retención (hold)
            if (activePeers[participantNumber]) {
                const prevHold = activePeers[participantNumber].hold;
                // Si la sesión indica hold por la otra parte
                const isHeld = session.hold === true && session.holdBy !== myNumber;
                
                if (isHeld !== prevHold) {
                    activePeers[participantNumber].hold = isHeld;
                    const audioEl = document.getElementById(`remote-audio-${participantNumber}`);
                    if (audioEl) {
                        audioEl.muted = isHeld;
                    }
                    console.log(`Participant ${participantNumber} hold state: ${isHeld}`);
                    updateConferenceUI();
                }
                
                // Escuchar invitación a teleconferencia (B y C reciben señal de A)
                if (session.conferenceWith && !organizerMode && !conferenceMode) {
                    conferenceMode = true;
                    console.log(`Invitado a conferencia. Conectar con: ${session.conferenceWith}`);
                    initiateParticipantToParticipantCall(myNumber, session.conferenceWith);
                }
            }
        });
        
    // Guardar el listener para poder desmontarlo
    if (activePeers[participantNumber]) {
        activePeers[participantNumber].sessionListener = listener;
    }
}

// Iniciar llamada directa entre participantes secundarios (Mesh: B <-> C)
function initiateParticipantToParticipantCall(me, other) {
    if (activePeers[other]) return; // Conexión ya establecida
    
    const isCaller = me < other;
    const callId = isCaller ? `${me}_${other}` : `${other}_${me}`;
    participantToParticipantCallId = callId;
    
    console.log(`Llamada Secundaria Mesh (${callId}). ¿Iniciador?: ${isCaller}`);
    
    const pc = new RTCPeerConnection(peerConfig);
    activePeers[other] = {
        peerConnection: pc,
        isCaller: isCaller,
        status: "ringing",
        hold: false
    };
    
    // Adjuntar tracks locales
    if (localStream) {
        localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
    }
    
    pc.onconnectionstatechange = () => {
        console.log(`Mesh state with ${other}:`, pc.connectionState);
        if (pc.connectionState === "connected") {
            activePeers[other].status = "connected";
            updateConferenceUI();
        } else if (pc.connectionState === "failed" || pc.connectionState === "disconnected") {
            hangupPeer(other);
        }
    };
    
    pc.ontrack = event => {
        console.log(`Track mesh recibido de ${other}`);
        if (event.streams && event.streams[0]) {
            addRemoteAudio(other, event.streams[0]);
        } else {
            addRemoteAudio(other, event.track);
        }
    };
    
    pc.onicecandidate = event => {
        if (event.candidate) {
            db.collection("calls").doc(callId).collection("candidates").add({
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex,
                sdp: event.candidate.candidate,
                caller: isCaller
            });
        }
    };
    
    // Escuchar candidatos ICE remotos
    db.collection("calls").doc(callId).collection("candidates")
        .where("caller", "==", !isCaller)
        .onSnapshot(snap => {
            if (snap) {
                snap.docChanges().forEach(change => {
                    if (change.type === "added") {
                        const d = change.doc.data();
                        pc.addIceCandidate(new RTCIceCandidate({
                            sdpMid: d.sdpMid,
                            sdpMLineIndex: d.sdpMLineIndex,
                            candidate: d.sdp
                        })).catch(() => {});
                    }
                });
            }
        });
        
    if (isCaller) {
        pc.createOffer().then(offer => {
            pc.setLocalDescription(offer).then(() => {
                db.collection("calls").doc(callId).set({
                    callerNumber: me,
                    calleeNumber: other,
                    status: "ringing",
                    offerSdp: offer.sdp,
                    answerSdp: null,
                    hold: false
                });
                
                // Escuchar respuesta
                secondaryCallListener = db.collection("calls").doc(callId)
                    .onSnapshot(doc => {
                        if (!doc.exists) {
                            hangupPeer(other);
                            return;
                        }
                        const s = doc.data();
                        if (s.status === "answered" && s.answerSdp) {
                            pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: s.answerSdp }))
                                .catch(e => console.error("setRemoteDescription answer mesh failed:", e));
                        }
                        if (s.status === "ended") {
                            hangupPeer(other);
                        }
                    });
            });
        });
    } else {
        // Callee
        secondaryCallListener = db.collection("calls").doc(callId)
            .onSnapshot(doc => {
                if (!doc.exists) {
                    hangupPeer(other);
                    return;
                }
                const s = doc.data();
                if (s.status === "ringing" && s.offerSdp && pc.signalingState === "stable") {
                    pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: s.offerSdp })).then(() => {
                        pc.createAnswer().then(answer => {
                            pc.setLocalDescription(answer).then(() => {
                                db.collection("calls").doc(callId).update({
                                    status: "answered",
                                    answerSdp: answer.sdp
                                });
                            });
                        });
                    });
                }
                if (s.status === "ended") {
                    hangupPeer(other);
                }
            });
    }
    
    updateConferenceUI();
}

// Escuchar Candidatos ICE de llamada estándar
function listenRemoteIceCandidates(sessionDocId, listenForCaller, pc) {
    const listener = db.collection("calls").doc(sessionDocId).collection("candidates")
        .where("caller", "==", listenForCaller)
        .onSnapshot(snapshot => {
            if (snapshot) {
                snapshot.docChanges().forEach(change => {
                    if (change.type === "added") {
                        const data = change.doc.data();
                        const cand = new RTCIceCandidate({
                            sdpMid: data.sdpMid,
                            sdpMLineIndex: data.sdpMLineIndex,
                            candidate: data.sdp
                        });
                        pc.addIceCandidate(cand).catch(e => {});
                    }
                });
            }
        });
    if (activePeers[sessionDocId]) {
        activePeers[sessionDocId].candidatesListener = listener;
    }
}

function sendIceCandidateToFirebase(sessionDocId, isCaller, candidate) {
    db.collection("calls").doc(sessionDocId).collection("candidates").add({
        sdpMid: candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex,
        sdp: candidate.candidate,
        caller: isCaller
    });
}

// Recibir llamada entrante
function showIncomingCallPopup(callerNumber) {
    // Si ya estamos en una llamada/conferencia, rechazar automáticamente la entrante (ocupado)
    if (Object.keys(activePeers).length > 0) {
        db.collection("calls").doc(myNumber).update({ status: "rejected" }).catch(() => {});
        return;
    }
    
    currentCallNumber = callerNumber;
    isIncomingCall = true;
    document.getElementById("incoming-caller-label").innerText = "Número: " + callerNumber;
    document.getElementById("incoming-call-modal").style.display = "flex";
    
    ringtoneAudio.currentTime = 0;
    ringtoneAudio.play().catch(() => {});

    showDesktopNotification(
        "📞 Llamada entrante",
        "Llamada de: " + callerNumber,
        callerNumber,
        true
    );
}

function hideIncomingCallPopup() {
    document.getElementById("incoming-call-modal").style.display = "none";
    ringtoneAudio.pause();
}

// Responder llamada entrante
function acceptIncomingCall() {
    hideIncomingCallPopup();

    document.getElementById("call-user-name").innerText = currentCallNumber;
    document.getElementById("call-user-number").innerText = currentCallNumber;
    document.getElementById("call-status-label").innerText = "Conectando...";
    document.getElementById("call-timer-label").style.display = "none";
    document.getElementById("btn-show-add-participant").style.display = "none";
    document.getElementById("conference-container").style.display = "none";
    document.getElementById("add-participant-container").style.display = "none";
    document.getElementById("call-overlay").style.display = "flex";
    document.getElementById("call-overlay").classList.remove("minimized");

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        alert("Error de seguridad: Tu navegador bloquea el acceso al micrófono.");
        rejectIncomingCall();
        return;
    }

    navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
        localStream = stream;
        startMicDiagnostics(localStream);
        
        const pc = new RTCPeerConnection(peerConfig);
        peerConnection = pc; // Referencia principal compatible
        activePeers[currentCallNumber] = {
            peerConnection: pc,
            isCaller: false,
            status: "ringing",
            hold: false
        };
        
        localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
        
        pc.onconnectionstatechange = () => {
            if (pc.connectionState === "connected") {
                activePeers[currentCallNumber].status = "connected";
                document.getElementById("call-status-label").innerText = "Llamada Conectada";
                startCallTimer();
                updateConferenceUI();
            } else if (pc.connectionState === "failed" || pc.connectionState === "disconnected") {
                hangupPeer(currentCallNumber);
            }
        };

        pc.ontrack = event => {
            if (event.streams && event.streams[0]) {
                addRemoteAudio(currentCallNumber, event.streams[0]);
            } else {
                addRemoteAudio(currentCallNumber, event.track);
            }
        };

        pc.onicecandidate = event => {
            if (event.candidate) {
                sendIceCandidateToFirebase(myNumber, false, event.candidate);
            }
        };

        db.collection("calls").doc(myNumber).get().then(doc => {
            if (doc.exists) {
                const session = doc.data();
                const desc = new RTCSessionDescription({ type: 'offer', sdp: session.offerSdp });
                
                pc.setRemoteDescription(desc).then(() => {
                    listenRemoteIceCandidates(myNumber, true, pc);
                    pc.createAnswer().then(answer => {
                        pc.setLocalDescription(answer).then(() => {
                            db.collection("calls").doc(myNumber).update({
                                status: "answered",
                                answerSdp: answer.sdp
                            });
                            listenToCallSessionUpdates(myNumber, currentCallNumber);
                            updateConferenceUI();
                        });
                    });
                });
            } else {
                endCallLocally();
            }
        });

    }).catch(err => {
        alert("Error de micrófono: " + err.message);
        rejectIncomingCall();
    });
}

function rejectIncomingCall() {
    hideIncomingCallPopup();
    if (currentCallNumber) {
        db.collection("calls").doc(myNumber).update({ status: "rejected" }).catch(() => {});
    }
}

// Colgar llamadas
function rejectOrHangupCall() {
    const peerKeys = Object.keys(activePeers);
    peerKeys.forEach(num => {
        hangupPeer(num);
    });
    endCallLocally();
}

// Colgar un participante específico
function hangupPeer(phoneNumber) {
    const peer = activePeers[phoneNumber];
    if (!peer) return;
    
    console.log(`Colgando conexión con ${phoneNumber}`);
    
    // Detener escuchas de firebase de este peer
    if (peer.sessionListener) peer.sessionListener();
    if (peer.candidatesListener) peer.candidatesListener();
    
    // Cerrar conexión WebRTC
    try {
        peer.peerConnection.close();
    } catch(e) {}
    
    // Remover elemento de audio
    removeRemoteAudio(phoneNumber);
    
    // Notificar en Firestore
    const isCaller = peer.isCaller;
    const docId = isCaller ? phoneNumber : myNumber;
    
    // Si somos el organizador o el caller de la conexión
    if (docId && docId === phoneNumber) {
        // Borrar documento completo
        db.collection("calls").doc(docId).delete().catch(() => {});
        db.collection("calls").doc(docId).collection("candidates").get().then(s => {
            s.forEach(d => d.ref.delete());
        }).catch(() => {});
    } else if (docId && docId === myNumber) {
        // Si somos el callee, solo marcamos finalizado (el caller lo borrará)
        db.collection("calls").doc(docId).update({ status: "ended" }).catch(() => {});
    }
    
    // Eliminar del mapa
    delete activePeers[phoneNumber];
    
    // Si la llamada secundaria B_C existe y este era uno de los involucrados, colgarla
    if (participantToParticipantCallId && !organizerMode) {
        db.collection("calls").doc(participantToParticipantCallId).update({ status: "ended" }).catch(() => {});
        participantToParticipantCallId = null;
        if (secondaryCallListener) {
            secondaryCallListener();
            secondaryCallListener = null;
        }
    }
    
    updateConferenceUI();
    
    // Si ya no quedan participantes, finalizar todo localmente
    if (Object.keys(activePeers).length === 0) {
        endCallLocally();
    }
}

// Retener/Reanudar participante
function toggleParticipantHold(phoneNumber, forceHoldState = null) {
    const peer = activePeers[phoneNumber];
    if (!peer) return;
    
    const targetHold = (forceHoldState !== null) ? forceHoldState : !peer.hold;
    console.log(`Aplicando Hold = ${targetHold} a ${phoneNumber}`);
    
    // Actualizar base de datos
    const docId = peer.isCaller ? phoneNumber : myNumber;
    db.collection("calls").doc(docId).update({
        hold: targetHold,
        holdBy: myNumber
    }).catch(e => console.error("Error al actualizar hold:", e));
    
    // Mapear localmente
    peer.hold = targetHold;
    
    // Silenciar el track que enviamos a este peer
    peer.peerConnection.getSenders().forEach(sender => {
        if (sender.track) {
            sender.track.enabled = !targetHold;
        }
    });
    
    // Silenciar el audio que recibimos de este peer
    const audioEl = document.getElementById(`remote-audio-${phoneNumber}`);
    if (audioEl) {
        audioEl.muted = targetHold;
    }
    
    updateConferenceUI();
}

// Fusionar en conferencia de malla (A fusiona B y C)
function mergeCallsInConference() {
    const peerKeys = Object.keys(activePeers);
    if (peerKeys.length < 2) return;
    
    const B = peerKeys[0];
    const C = peerKeys[1];
    
    console.log(`Fusionando llamadas en conferencia: ${B} y ${C}`);
    
    // Reanudar a ambos participantes en firestore
    const docB = activePeers[B].isCaller ? B : myNumber;
    const docC = activePeers[C].isCaller ? C : myNumber;
    
    db.collection("calls").doc(docB).update({ hold: false, conferenceWith: C }).catch(() => {});
    db.collection("calls").doc(docC).update({ hold: false, conferenceWith: B }).catch(() => {});
    
    // Reanudar tracks locales
    activePeers[B].hold = false;
    activePeers[C].hold = false;
    
    [B, C].forEach(num => {
        activePeers[num].peerConnection.getSenders().forEach(sender => {
            if (sender.track) sender.track.enabled = true;
        });
        const audioEl = document.getElementById(`remote-audio-${num}`);
        if (audioEl) audioEl.muted = false;
    });
    
    conferenceMode = true;
    updateConferenceUI();
    alert("¡Teleconferencia establecida!");
}

// Alternar participantes (Pone al activo en espera y reanuda al retenido)
function swapCallsOnHold() {
    const peerKeys = Object.keys(activePeers);
    if (peerKeys.length < 2) return;
    
    const first = peerKeys[0];
    const second = peerKeys[1];
    
    const firstHold = activePeers[first].hold;
    const secondHold = activePeers[second].hold;
    
    // Si uno está retenido y el otro no, los invertimos. Si ambos están igual, retenemos el primero y reanudamos el segundo.
    if (firstHold === secondHold) {
        toggleParticipantHold(first, true);
        toggleParticipantHold(second, false);
    } else {
        toggleParticipantHold(first, !firstHold);
        toggleParticipantHold(second, !secondHold);
    }
}

// Minimizar / Maximizar llamada activa
function toggleCallMinimize() {
    const overlay = document.getElementById("call-overlay");
    overlay.classList.toggle("minimized");
}

function endCallLocally() {
    // Parar temporizador
    clearInterval(callTimer);
    callDurationSeconds = 0;
    
    // Ocultar overlays
    document.getElementById("call-overlay").style.display = "none";
    document.getElementById("incoming-call-modal").style.display = "none";
    
    // Parar sonidos
    ringtoneAudio.pause();
    ringtoneAudio.currentTime = 0;
    
    // Limpiar audio remoto
    remoteAudio.srcObject = null;
    remoteAudio.pause();

    // Ocultar indicadores de llamada
    document.getElementById("btn-unlock-audio").style.display = "none";
    document.getElementById("mic-indicator").style.display = "none";
    
    // Registrar historial localmente
    saveCallRecordLocally();

    // Limpiar WebRTC
    if (localStream) {
        localStream.getTracks().forEach(track => track.stop());
        localStream = null;
    }
    if (peerConnection) {
        peerConnection.ontrack = null;
        peerConnection.onicecandidate = null;
        peerConnection.close();
        peerConnection = null;
    }

    if (callSessionListener) {
        callSessionListener();
        callSessionListener = null;
    }

    // Limpiar Firebase: el caller borra el documento; el callee solo actualiza estado
    const sessionDocId = isIncomingCall ? myNumber : currentCallNumber;
    if (sessionDocId) {
        if (!isIncomingCall) {
            // Caller: borrar candidatos y documento
            setTimeout(() => {
                db.collection("calls").doc(sessionDocId).collection("candidates").get().then(snap => {
                    snap.forEach(d => d.ref.delete());
                    db.collection("calls").doc(sessionDocId).delete();
                });
            }, 1500);
        } else {
            // Callee: solo marcar como ended (el caller borrará el doc)
            db.collection("calls").doc(sessionDocId).update({ status: "ended" }).catch(() => {});
        }
    }

    isIncomingCall = false;
    currentCallNumber = "";
}

// Diagnóstico de micrófono: muestra si el mic está capturando audio
let micAudioContext = null;
function startMicDiagnostics(stream) {
    try {
        const micIndicator = document.getElementById("mic-indicator");
        const micIcon = document.getElementById("mic-indicator-icon");
        const micText = document.getElementById("mic-indicator-text");
        micIndicator.style.display = "flex";

        const audioTracks = stream.getAudioTracks();
        if (!audioTracks || audioTracks.length === 0) {
            micIcon.style.color = "#f44336";
            micText.innerText = "Micrófono: NO encontrado";
            return;
        }

        const track = audioTracks[0];
        micText.innerText = "Micrófono: " + (track.label || "activo");
        micIcon.style.color = "#4caf50";

        // Usar AudioContext para detectar si hay señal de audio
        micAudioContext = new (window.AudioContext || window.webkitAudioContext)();
        const source = micAudioContext.createMediaStreamSource(stream);
        const analyser = micAudioContext.createAnalyser();
        analyser.fftSize = 256;
        source.connect(analyser);

        const dataArray = new Uint8Array(analyser.frequencyBinCount);
        let silentCount = 0;
        const checkMic = setInterval(() => {
            // Si la llamada terminó, limpiar
            if (!localStream || !peerConnection) {
                clearInterval(checkMic);
                if (micAudioContext) { micAudioContext.close(); micAudioContext = null; }
                return;
            }
            analyser.getByteFrequencyData(dataArray);
            const avg = dataArray.reduce((a, b) => a + b, 0) / dataArray.length;
            if (avg > 3) {
                silentCount = 0;
                micIcon.style.color = "#4caf50";
                micText.innerText = "Micrófono: 🔶 Capturando audio";
            } else {
                silentCount++;
                if (silentCount > 5) {
                    micIcon.style.color = "#ff9800";
                    micText.innerText = "Micrófono: ⚠️ Sin señal (hablá cerca del mic)";
                }
            }
        }, 500);
    } catch(e) {
        console.warn("No se pudo iniciar diagnóstico de micrófono:", e);
    }
}

function startCallTimer() {
    const timerLabel = document.getElementById("call-timer-label");
    timerLabel.style.display = "block";
    callDurationSeconds = 0;
    timerLabel.innerText = "00:00";
    
    clearInterval(callTimer);
    callTimer = setInterval(() => {
        callDurationSeconds++;
        const minutes = Math.floor(callDurationSeconds / 60);
        const seconds = callDurationSeconds % 60;
        timerLabel.innerText = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
    }, 1000);
}

function toggleCallMute() {
    if (localStream) {
        isMuted = !isMuted;
        localStream.getAudioTracks().forEach(track => track.enabled = !isMuted);
        
        const muteBtn = document.getElementById("btn-call-mute");
        if (isMuted) {
            muteBtn.classList.add("active");
            muteBtn.innerHTML = '<span class="material-icons">mic_off</span>';
        } else {
            muteBtn.classList.remove("active");
            muteBtn.innerHTML = '<span class="material-icons">mic</span>';
        }
    }
}

function toggleCallSpeaker() {
    // En navegadores, el audio ya se reproduce por el altavoz por defecto.
    // Solo actualizamos el estado visual del botón.
    const speakerBtn = document.getElementById("btn-call-speaker");
    speakerBtn.classList.toggle("active");
}

function saveCallRecordLocally() {
    if (!currentCallNumber) return;
    
    const record = {
        phoneNumber: currentCallNumber,
        type: isIncomingCall ? (callDurationSeconds > 0 ? "incoming" : "missed") : "outgoing",
        timestamp: Date.now(),
        duration: callDurationSeconds
    };
    
    db.collection("users").doc(currentUser.uid).collection("call_history").add(record);
}

function loadCallHistoryList() {
    const listContainer = document.getElementById("history-list");
    listContainer.innerHTML = '<div class="text-center p-4">Cargando historial...</div>';

    db.collection("users").doc(currentUser.uid).collection("call_history")
        .get()
        .then(snapshot => {
            listContainer.innerHTML = "";
            if (snapshot.empty) {
                listContainer.innerHTML = '<div class="empty-state"><h4>Historial de llamadas vacío</h4></div>';
                return;
            }

            // Ordenar por fecha descendente
            const records = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }))
                .sort((a, b) => b.timestamp - a.timestamp);

            records.forEach(rec => {
                const dateStr = new Date(rec.timestamp).toLocaleString();
                const typeIcon = rec.type === "incoming" ? "call_received" : (rec.type === "missed" ? "call_missed" : "call_made");
                const typeColor = rec.type === "missed" ? "var(--hangup-red)" : "var(--call-green)";
                const durationStr = rec.duration > 0 ? `${Math.floor(rec.duration / 60)}m ${rec.duration % 60}s` : "Perdida";

                const phoneEscaped = escapeHTML(rec.phoneNumber);

                const item = document.createElement("div");
                item.className = "list-item";
                item.innerHTML = `
                    <div class="list-item-avatar">
                        <span class="material-icons" style="color: ${typeColor};">${typeIcon}</span>
                    </div>
                    <div class="list-item-info">
                        <div class="list-item-title">${phoneEscaped}</div>
                        <div class="list-item-subtitle">${dateStr} • Duración: ${durationStr}</div>
                    </div>
                `;
                listContainer.appendChild(item);
            });
        });
}

function clearCallHistory() {
    if (confirm("¿Estás seguro de que quieres limpiar tu historial de llamadas?")) {
        db.collection("users").doc(currentUser.uid).collection("call_history").get().then(snap => {
            const batch = db.batch();
            snap.forEach(doc => batch.delete(doc.ref));
            batch.commit().then(() => {
                alert("Historial limpiado");
                loadCallHistoryList();
            });
        });
    }
}

// --- DIALER TECLADO ---

function setupDialer() {
    const dialInput = document.getElementById("dial-input");
    const dialBtns = document.querySelectorAll(".dial-btn");
    
    dialBtns.forEach(btn => {
        btn.addEventListener("click", () => {
            const val = btn.getAttribute("data-val");
            const start = dialInput.selectionStart ?? dialInput.value.length;
            const end = dialInput.selectionEnd ?? dialInput.value.length;
            const text = dialInput.value;
            dialInput.value = text.slice(0, start) + val + text.slice(end);
            
            // Reposicionar el cursor justo después del carácter insertado
            const newCursorPos = start + val.length;
            dialInput.setSelectionRange(newCursorPos, newCursorPos);
            dialInput.focus();
            
            // Disparar evento input para sincronizar cualquier lógica
            dialInput.dispatchEvent(new Event("input"));
        });
    });

    document.getElementById("btn-dial-delete").addEventListener("click", () => {
        const start = dialInput.selectionStart ?? dialInput.value.length;
        const end = dialInput.selectionEnd ?? dialInput.value.length;
        const text = dialInput.value;
        
        if (start !== end) {
            // Si hay una selección activa, borrar la selección
            dialInput.value = text.slice(0, start) + text.slice(end);
            dialInput.setSelectionRange(start, start);
        } else if (start > 0) {
            // Borrar el carácter anterior al cursor
            dialInput.value = text.slice(0, start - 1) + text.slice(start);
            dialInput.setSelectionRange(start - 1, start - 1);
        }
        
        dialInput.focus();
        dialInput.dispatchEvent(new Event("input"));
    });

    // Sanitizar entrada del teclado físico o pegado de texto
    dialInput.addEventListener("input", () => {
        const originalValue = dialInput.value;
        const selectionStart = dialInput.selectionStart;
        
        // Solo permitir dígitos, asterisco (*), numeral (#) y más (+)
        const sanitized = originalValue.replace(/[^0-9*#+]/g, '');
        
        if (originalValue !== sanitized) {
            dialInput.value = sanitized;
            
            // Ajustar posición del cursor para no saltar al final
            const removedBeforeCursor = (originalValue.slice(0, selectionStart).match(/[^0-9*#+]/g) || []).length;
            const newCursorPos = Math.max(0, selectionStart - removedBeforeCursor);
            dialInput.setSelectionRange(newCursorPos, newCursorPos);
        }
    });

    // Iniciar llamada al presionar Enter en el input
    dialInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            e.preventDefault();
            initiateVoipCall(dialInput.value.trim());
        }
    });

    document.getElementById("btn-initiate-voip").addEventListener("click", () => {
        initiateVoipCall(dialInput.value.trim());
    });
}

// --- NOTIFICACIONES Y SONIDOS ---

function checkNotificationPermission() {
    if (!("Notification" in window)) return;
    
    if (Notification.permission === "default") {
        document.getElementById("notif-banner").style.display = "flex";
    }
}

function requestNotificationPermission() {
    if (!("Notification" in window)) return;
    
    Notification.requestPermission().then(permission => {
        document.getElementById("notif-banner").style.display = "none";
        if (permission === "granted") {
            alert("¡Notificaciones de escritorio habilitadas!");
        }
    });
}

function showDesktopNotification(title, text, contactNumber, isCall = false) {
    if (Notification.permission === "granted") {
        // Para llamadas, mostrar aunque la pestaña esté activa
        if (!isCall && !document.hidden) return;
        const notif = new Notification(title, {
            body: text,
            icon: "https://fonts.gstatic.com/s/i/materialicons/forum/v6/24px.svg",
            requireInteraction: isCall // La notificación de llamada no se cierra sola
        });
        notif.onclick = () => {
            window.focus();
            if (!isCall) openDirectChat(contactNumber, contactNumber);
            notif.close();
        };
    }
}

function playNotificationSound() {
    notificationAudio.currentTime = 0;
    notificationAudio.play().catch(() => {});
}

// --- UTILERÍAS ---

function getChatRoomId(number1, number2) {
    return number1 < number2 ? `${number1}_${number2}` : `${number2}_${number1}`;
}

function formatTimestamp(timestamp) {
    if (!timestamp) return "";
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function showView(containerId) {
    const containers = ["app-loader", "login-container", "number-container", "verification-container", "dashboard-container"];
    containers.forEach(id => {
        document.getElementById(id).style.display = (id === containerId) ? "flex" : "none";
    });
}

function showLoader(show, message = "Cargando...") {
    const loader = document.getElementById("app-loader");
    loader.querySelector("h2").innerText = message;
    loader.style.display = show ? "flex" : "none";
}
