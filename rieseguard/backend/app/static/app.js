const API_BASE = ''; // Root directory since served locally

// State
let token = localStorage.getItem('parent_token') || null;
let currentEmail = localStorage.getItem('parent_email') || null;
let isLoginTab = true;

// DOM Elements
const authScreen = document.getElementById('auth-screen');
const dashboardScreen = document.getElementById('dashboard-screen');
const authForm = document.getElementById('auth-form');
const authSubmitBtn = document.getElementById('auth-submit-btn');
const tabLogin = document.getElementById('tab-login');
const tabRegister = document.getElementById('tab-register');
const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const userDisplay = document.getElementById('user-display');
const logoutBtn = document.getElementById('logout-btn');
const devicesGrid = document.getElementById('devices-grid');
const addDeviceBtn = document.getElementById('add-device-btn');

// Modals
const addDeviceModal = document.getElementById('add-device-modal');
const addDeviceForm = document.getElementById('add-device-form');
const deviceNameInput = document.getElementById('device-name');
const cancelDeviceBtn = document.getElementById('cancel-device-btn');

const pairingModal = document.getElementById('pairing-modal');
const pairUrl = document.getElementById('pair-url');
const pairId = document.getElementById('pair-id');
const pairToken = document.getElementById('pair-token');
const closeModalBtn = document.getElementById('close-modal-btn');

const appsModal = document.getElementById('apps-modal');
const closeAppsModalBtn = document.getElementById('close-apps-modal-btn');
const appSearchInput = document.getElementById('app-search-input');
const appsListContainer = document.getElementById('apps-list-container');
let currentAppDeviceID = null;
let installedApps = [];

// Initialize view
checkAuthState();

// Event Listeners
tabLogin.addEventListener('click', () => setTab(true));
tabRegister.addEventListener('click', () => setTab(false));
authForm.addEventListener('submit', handleAuthSubmit);
logoutBtn.addEventListener('click', logout);
addDeviceBtn.addEventListener('click', () => showModal(addDeviceModal, true));
cancelDeviceBtn.addEventListener('click', () => showModal(addDeviceModal, false));
closeModalBtn.addEventListener('click', () => showModal(pairingModal, false));
closeAppsModalBtn.addEventListener('click', () => showModal(appsModal, false));
appSearchInput.addEventListener('input', filterAppsList);

addDeviceForm.addEventListener('submit', handleAddDevice);

// Functions
function checkAuthState() {
    if (token) {
        authScreen.classList.add('hidden');
        dashboardScreen.classList.remove('hidden');
        userDisplay.textContent = `Eltern-Konto: ${currentEmail}`;
        loadDevices();
    } else {
        authScreen.classList.remove('hidden');
        dashboardScreen.classList.add('hidden');
    }
}

function setTab(loginTab) {
    isLoginTab = loginTab;
    if (isLoginTab) {
        tabLogin.classList.add('active');
        tabRegister.classList.remove('active');
        authSubmitBtn.textContent = 'Anmelden';
    } else {
        tabLogin.classList.remove('active');
        tabRegister.classList.add('active');
        authSubmitBtn.textContent = 'Konto erstellen';
    }
}

async function handleAuthSubmit(e) {
    e.preventDefault();
    const email = emailInput.value;
    const password = passwordInput.value;

    const endpoint = isLoginTab ? '/parent/token' : '/parent/register';
    
    let body;
    let headers = {};

    if (isLoginTab) {
        // Form-data request for OAuth2 endpoint
        const formData = new FormData();
        formData.append('username', email);
        formData.append('password', password);
        body = formData;
    } else {
        // JSON request for registration
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify({ email, password });
    }

    try {
        const response = await fetch(`${API_BASE}${endpoint}`, {
            method: 'POST',
            headers: headers,
            body: body
        });

        const data = await response.json();

        if (!response.ok) {
            alert(`Fehler: ${data.detail || 'Etwas ist schiefgelaufen'}`);
            return;
        }

        if (isLoginTab) {
            token = data.access_token;
            currentEmail = email;
            localStorage.setItem('parent_token', token);
            localStorage.setItem('parent_email', currentEmail);
            checkAuthState();
            authForm.reset();
        } else {
            alert('Registrierung erfolgreich! Bitte melde dich jetzt an.');
            setTab(true);
        }
    } catch (err) {
        console.error(err);
        alert('Netzwerkfehler beim Anmelden');
    }
}

function logout() {
    token = null;
    currentEmail = null;
    localStorage.removeItem('parent_token');
    localStorage.removeItem('parent_email');
    checkAuthState();
}

function showModal(modal, show) {
    if (show) {
        modal.classList.remove('hidden');
    } else {
        modal.classList.add('hidden');
    }
}

async function loadDevices() {
    devicesGrid.innerHTML = `
        <div class="loading-spinner-wrapper">
            <div class="spinner"></div>
            <p>Lade Geräte...</p>
        </div>
    `;

    try {
        const response = await fetch(`${API_BASE}/parent/devices`, {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        if (response.status === 401) {
            logout();
            return;
        }

        const devices = await response.json();
        
        if (devices.length === 0) {
            devicesGrid.innerHTML = `
                <div class="loading-spinner-wrapper">
                    <p>Noch keine Geräte registriert. Klicke auf "+ Gerät hinzufügen".</p>
                </div>
            `;
            return;
        }

        devicesGrid.innerHTML = '';
        devices.forEach(device => {
            const card = createDeviceCard(device);
            devicesGrid.appendChild(card);
        });

    } catch (err) {
        console.error(err);
        devicesGrid.innerHTML = `
            <div class="loading-spinner-wrapper">
                <p>Fehler beim Laden der Geräte</p>
            </div>
        `;
    }
}

function createDeviceCard(device) {
    const card = document.createElement('div');
    card.className = 'glass-card device-card fade-in';

    // Calculate Online Status (last sync within 1 minute)
    let isOnline = false;
    let syncTimeText = 'Noch nie';
    
    if (device.last_seen) {
        const lastSeenDate = new Date(device.last_seen);
        const timeDiff = new Date() - lastSeenDate;
        isOnline = timeDiff < 65000; // 65 seconds to allow buffer for 30s worker
        
        // Format last seen date nicely
        syncTimeText = lastSeenDate.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    const onlineClass = isOnline ? 'status-online' : 'status-offline';
    const onlineText = isOnline ? 'Online' : 'Offline';

    const lockStateText = device.locked ? 'GESPERRT' : 'OFFEN';
    const lockColorClass = device.locked ? 'text-danger' : 'text-success';

    card.innerHTML = `
        <div class="device-info-top">
            <span class="device-name">${escapeHTML(device.name)}</span>
            <div class="status-indicator ${onlineClass}">
                <span class="status-dot"></span>
                <span>${onlineText}</span>
            </div>
        </div>
        
        <div class="device-details">
            <div>Geräte ID: <span>${device.id}</span></div>
            <div>Token: <span class="font-mono" style="font-size: 11px;">${device.device_token}</span></div>
            <div>Sperrstatus: <span class="${lockColorClass}" style="font-weight: 700;">${lockStateText}</span>${device.locked && device.lock_reason ? `<br><small style="opacity: 0.8; font-style: italic;">Grund: ${escapeHTML(device.lock_reason)}</small>` : ''}</div>
            <div>Letzter Sync: <span>${syncTimeText}</span></div>
        </div>

        <div class="device-schedule" style="margin-top: 12px; padding: 12px; background: rgba(255,255,255,0.03); border-radius: 8px; border: 1px solid rgba(255,255,255,0.05);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                <span style="font-weight: 600; font-size: 13px; display: flex; align-items: center; gap: 6px;">🛌 Tägliche Schlafenszeit</span>
                <label class="switch" style="scale: 0.8; margin: 0;">
                    <input type="checkbox" class="schedule-toggle" ${device.schedule_active ? 'checked' : ''}>
                    <span class="slider round"></span>
                </label>
            </div>
            <div class="schedule-times" style="display: flex; gap: 8px; align-items: center; justify-content: space-between; ${device.schedule_active ? '' : 'opacity: 0.5; pointer-events: none;'}">
                <div style="font-size: 11px;">Von: <input type="time" class="schedule-start" value="${device.schedule_start || '21:00'}" style="background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1); color: white; border-radius: 4px; padding: 2px 4px; font-family: monospace;"></div>
                <div style="font-size: 11px;">Bis: <input type="time" class="schedule-end" value="${device.schedule_end || '07:00'}" style="background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1); color: white; border-radius: 4px; padding: 2px 4px; font-family: monospace;"></div>
                <button class="btn btn-primary save-schedule-btn" style="padding: 2px 6px; font-size: 10px; line-height: 1.2; height: auto; min-height: 0;">OK</button>
            </div>
        </div>

        <div class="device-actions" style="display: flex; gap: 8px; width: 100%; margin-top: 12px;">
            ${device.locked 
                ? `<button class="btn btn-secondary btn-sm action-unlock-btn" data-id="${device.id}" style="flex: 1;">Entsperren</button>` 
                : `<button class="btn btn-danger btn-sm action-lock-btn" data-id="${device.id}" style="flex: 1;">Sperren</button>`
            }
            <button class="btn btn-secondary btn-sm action-apps-btn" data-id="${device.id}" style="background: rgba(255,255,255,0.08); flex: 1;">Apps</button>
        </div>
    `;

    // Hook buttons
    const lockBtn = card.querySelector('.action-lock-btn');
    const unlockBtn = card.querySelector('.action-unlock-btn');
    const appsBtn = card.querySelector('.action-apps-btn');

    if (lockBtn) {
        lockBtn.addEventListener('click', () => toggleLock(device.id, true));
    }
    if (unlockBtn) {
        unlockBtn.addEventListener('click', () => toggleLock(device.id, false));
    }
    if (appsBtn) {
        appsBtn.addEventListener('click', () => showAppsModal(device.id));
    }

    // Hook schedule elements
    const scheduleToggle = card.querySelector('.schedule-toggle');
    const saveScheduleBtn = card.querySelector('.save-schedule-btn');

    scheduleToggle.addEventListener('change', () => {
        const timesDiv = card.querySelector('.schedule-times');
        if (scheduleToggle.checked) {
            timesDiv.style.opacity = '1';
            timesDiv.style.pointerEvents = 'auto';
        } else {
            timesDiv.style.opacity = '0.5';
            timesDiv.style.pointerEvents = 'none';
        }
        saveDeviceSchedule(device.id, card);
    });

    saveScheduleBtn.addEventListener('click', () => {
        saveDeviceSchedule(device.id, card);
    });

    return card;
}

async function saveDeviceSchedule(deviceId, card) {
    const active = card.querySelector('.schedule-toggle').checked;
    const start = card.querySelector('.schedule-start').value;
    const end = card.querySelector('.schedule-end').value;

    const url = `${API_BASE}/parent/devices/${deviceId}/schedule?schedule_active=${active}&schedule_start=${encodeURIComponent(start)}&schedule_end=${encodeURIComponent(end)}`;

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        if (!response.ok) {
            alert('Fehler beim Speichern des Zeitplans');
            return;
        }

        // Reload devices
        loadDevices();
    } catch (err) {
        console.error(err);
        alert('Netzwerkfehler');
    }
}

async function toggleLock(deviceId, lock) {
    let url = `${API_BASE}/parent/devices/${deviceId}/unlock`;
    
    if (lock) {
        const reason = prompt("Geben Sie einen individuellen Sperrgrund ein:", "Das Gerät wurde gesperrt.");
        if (reason === null) return; // Parent cancelled prompt
        url = `${API_BASE}/parent/devices/${deviceId}/lock?reason=${encodeURIComponent(reason)}`;
    }

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        if (!response.ok) {
            alert('Fehler beim Ändern des Gerätestatus');
            return;
        }

        // Refresh devices list
        loadDevices();
    } catch (err) {
        console.error(err);
        alert('Netzwerkfehler');
    }
}

async function handleAddDevice(e) {
    e.preventDefault();
    const name = deviceNameInput.value;

    try {
        const response = await fetch(`${API_BASE}/parent/devices?name=${encodeURIComponent(name)}`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        const data = await response.json();

        if (!response.ok) {
            alert(`Fehler: ${data.detail || 'Gerät konnte nicht erstellt werden'}`);
            return;
        }

        // Close add modal & clear input
        showModal(addDeviceModal, false);
        addDeviceForm.reset();

        // Open pairing modal
        // Automatically determine Server URL based on access origin
        const currentOrigin = window.location.origin;
        pairUrl.textContent = currentOrigin;
        pairId.textContent = data.device_id;
        pairToken.textContent = data.device_token;
        
        showModal(pairingModal, true);

        // Refresh devices list
        loadDevices();

    } catch (err) {
        console.error(err);
        alert('Netzwerkfehler');
    }
}

// Helpers
function escapeHTML(str) {
    return str.replace(/[&<>'"]/g, 
        tag => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            "'": '&#39;',
            '"': '&quot;'
        }[tag] || tag)
    );
}

// Auto-refresh devices grid every 5 seconds while active
setInterval(() => {
    if (token) {
        loadDevices();
    }
}, 5000);

// App Blocking UI Helpers
async function showAppsModal(deviceId) {
    currentAppDeviceID = deviceId;
    appSearchInput.value = '';
    showModal(appsModal, true);
    loadAppsList(deviceId);
}

async function loadAppsList(deviceId) {
    appsListContainer.innerHTML = `
        <div class="loading-spinner-wrapper" style="text-align: center; padding: 20px;">
            <div class="spinner" style="margin: 0 auto 10px;"></div>
            <p style="margin: 0; font-size: 14px; opacity: 0.7;">Lade Apps...</p>
        </div>
    `;
    
    try {
        const response = await fetch(`${API_BASE}/parent/devices/${deviceId}/apps`, {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });
        
        if (!response.ok) {
            appsListContainer.innerHTML = `<p style="text-align: center; color: var(--danger); font-size: 14px;">Fehler beim Laden der Apps</p>`;
            return;
        }
        
        installedApps = await response.json();
        renderAppsList(installedApps);
    } catch (err) {
        console.error(err);
        appsListContainer.innerHTML = `<p style="text-align: center; color: var(--danger); font-size: 14px;">Netzwerkfehler</p>`;
    }
}

function renderAppsList(apps) {
    if (apps.length === 0) {
        appsListContainer.innerHTML = `<p style="text-align: center; opacity: 0.6; padding: 20px; font-size: 14px;">Noch keine Apps gemeldet. Bitte starte die RieseGuard-App auf dem Kindergerät einmal, um die Synchronisierung auszuführen.</p>`;
        return;
    }
    
    appsListContainer.innerHTML = '';
    apps.forEach(app => {
        const row = document.createElement('div');
        row.className = 'app-item-row';
        row.style.display = 'flex';
        row.style.alignItems = 'center';
        row.style.justifyContent = 'space-between';
        
        const initial = app.app_name ? app.app_name.charAt(0).toUpperCase() : '?';
        const badgeColor = app.is_blocked ? 'rgba(239, 68, 68, 0.15)' : 'rgba(34, 197, 94, 0.15)';
        const badgeTextColor = app.is_blocked ? '#ef4444' : '#22c55e';
        const badgeText = app.is_blocked ? 'Gesperrt' : 'Erlaubt';

        row.innerHTML = `
            <div class="app-icon-circle" style="width: 36px; height: 36px; border-radius: 50%; background: rgba(255,255,255,0.06); display: flex; align-items: center; justify-content: center; font-weight: 700; color: var(--primary); font-size: 15px; margin-right: 12px; border: 1px solid rgba(255,255,255,0.08); flex-shrink: 0; user-select: none;">
                ${initial}
            </div>
            <div class="app-meta" style="flex: 1; min-width: 0; text-align: left;">
                <span class="app-display-name" style="display: block; font-weight: 600; font-size: 14px; color: #fff; text-overflow: ellipsis; overflow: hidden; white-space: nowrap;">${escapeHTML(app.app_name)}</span>
                <span class="app-package-id" style="display: block; font-size: 11px; color: var(--text-secondary); font-family: monospace; text-overflow: ellipsis; overflow: hidden; white-space: nowrap;">${escapeHTML(app.package_name)}</span>
            </div>
            <span class="app-status-badge" style="background: ${badgeColor}; color: ${badgeTextColor}; padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; margin-right: 12px; display: inline-block; white-space: nowrap; transition: all 0.2s ease;">
                ${badgeText}
            </span>
            <label class="switch">
                <input type="checkbox" class="app-toggle-cb" data-package="${escapeHTML(app.package_name)}" ${app.is_blocked ? 'checked' : ''}>
                <span class="slider"></span>
            </label>
        `;
        
        const cb = row.querySelector('.app-toggle-cb');
        cb.addEventListener('change', async (e) => {
            const success = await handleAppToggle(app.package_name, e.target.checked);
            if (success) {
                app.is_blocked = e.target.checked;
                const badge = row.querySelector('.app-status-badge');
                if (badge) {
                    badge.style.background = app.is_blocked ? 'rgba(239, 68, 68, 0.15)' : 'rgba(34, 197, 94, 0.15)';
                    badge.style.color = app.is_blocked ? '#ef4444' : '#22c55e';
                    badge.textContent = app.is_blocked ? 'Gesperrt' : 'Erlaubt';
                }
            }
        });
        
        appsListContainer.appendChild(row);
    });
}

function filterAppsList() {
    const query = appSearchInput.value.toLowerCase();
    const filtered = installedApps.filter(app => 
        app.app_name.toLowerCase().includes(query) || 
        app.package_name.toLowerCase().includes(query)
    );
    renderAppsList(filtered);
}

async function handleAppToggle(packageName, isBlocked) {
    try {
        const response = await fetch(`${API_BASE}/parent/devices/${currentAppDeviceID}/apps/toggle?package_name=${encodeURIComponent(packageName)}&is_blocked=${isBlocked}`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });
        
        if (!response.ok) {
            alert('Fehler beim Ändern des App-Status');
            loadAppsList(currentAppDeviceID); // revert UI
            return false;
        }
        return true;
    } catch (err) {
        console.error(err);
        alert('Netzwerkfehler');
        loadAppsList(currentAppDeviceID); // revert UI
        return false;
    }
}
