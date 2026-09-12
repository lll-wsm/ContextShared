let token = localStorage.getItem('cs_token') || '';
let ws = null;
let reconnectTimer = null;
let heartbeatTimer = null;
let currentDir = '';
let browseRoot = '';
let currentVolumeRoot = '';
let volumes = [];
let accessPollTimer = null;
/** 最近一次从手机收到的剪贴板内容（空字符串表示还没收到） */
let phoneClipText = '';

// 国际化双语字典 (i18n Dictionary)
const I18N = {
    zh: {
        appTitle: "内容共享 - 极速跨端互联",
        logoName: "内容共享",
        connected: "已连接",
        disconnected: "连接已断开 (重连中...)",
        clipTitle: "📋 剪贴板同步",
        clipBadge: "实时监听中",
        phoneRecentClip: "手机端最近复制：",
        noClipContent: "暂无内容 · 在手机上复制后切回本 App 即可同步",
        copyToPc: "复制到电脑",
        copiedToClipboard: "已复制到电脑剪贴板",
        copyFailed: "复制失败，请手动复制",
        textPlaceholder: "输入要发送到手机的文本或网址... (按 Ctrl/Cmd+Enter 发送)",
        sendToClipboard: "📤 发送到手机剪贴板",
        openInPhone: "🌐 手机浏览器打开",
        openUrlSent: "已发送打开指令至手机",
        fileTransferTitle: "📁 文件极速快传",
        dragDropBadge: "拖拽即传",
        dropzoneText: "将文件拖拽至此，或",
        dropzoneBtn: "点击选择文件",
        phoneFilesTitle: "📂 手机文件库",
        tabDownloads: "📥 下载/共享",
        tabPhotos: "🖼 照片/相册",
        tabDocuments: "📑 文档",
        emptyDir: "目录为空",
        download: "下载",
        volumeInternal: "📱 内部存储",
        volumeRemovable: "💾 外置存储",
        storageBtn: "🗂 存储",
        refreshDir: "刷新",
        uploadHere: "上传到此目录",
        folder: "文件夹",
        dirError: "目录无法访问",
        permBannerText: "未授予「所有文件访问权限」，当前只能看到媒体文件和共享目录",
        grantAccess: "去手机授权",
        grantOpening: "已在手机上打开设置页，请开启「允许管理所有文件」",
        grantWaiting: "等待手机上完成授权...",
        grantFailed: "无法自动打开设置页，请在手机上打开 App 后点「去授权」",
        pinModalTitle: "🔒 设备配对验证",
        pinModalDesc: "请输入手机屏幕上显示的 4 位连接验证码：",
        pinInputPlaceholder: "输入4位PIN码",
        pinSubmitBtn: "验证并连接",
        pinError: "PIN 码错误，请重新输入",
        dragOverlayText: "松开鼠标即可立即上传至手机",
        uploadDone: "✅ 完成",
        uploadFail: "❌ 失败",
        loading: "加载中...",
        phoneTag: "手机"
    },
    en: {
        appTitle: "ContextShared - Fast Cross-Device Transfer",
        logoName: "ContextShared",
        connected: "Connected",
        disconnected: "Disconnected (Reconnecting...)",
        clipTitle: "📋 Clipboard Sync",
        clipBadge: "Live Listening",
        phoneRecentClip: "Latest copied on phone:",
        noClipContent: "Nothing yet · copy on the phone, then switch back to this app",
        copyToPc: "Copy to PC",
        copiedToClipboard: "Copied to PC clipboard",
        copyFailed: "Copy failed, please copy manually",
        textPlaceholder: "Enter text or URL to send to phone... (Press Ctrl/Cmd+Enter to send)",
        sendToClipboard: "📤 Send to Phone Clipboard",
        openInPhone: "🌐 Open in Phone Browser",
        openUrlSent: "Open URL command sent to phone",
        fileTransferTitle: "📁 Fast File Transfer",
        dragDropBadge: "Drag & Drop",
        dropzoneText: "Drag files here, or",
        dropzoneBtn: "browse files",
        phoneFilesTitle: "📂 Phone Storage",
        tabDownloads: "📥 Downloads/Shared",
        tabPhotos: "🖼 Photos/Camera",
        tabDocuments: "📑 Documents",
        emptyDir: "Directory is empty",
        download: "Download",
        volumeInternal: "📱 Internal storage",
        volumeRemovable: "💾 SD card",
        storageBtn: "🗂 Storage",
        refreshDir: "Refresh",
        uploadHere: "Upload here",
        folder: "Folder",
        dirError: "Directory not accessible",
        permBannerText: "\"All files access\" is not granted: only media files and the shared folder are visible",
        grantAccess: "Grant on phone",
        grantOpening: "Settings opened on the phone. Turn on \"Allow management of all files\".",
        grantWaiting: "Waiting for the grant on the phone...",
        grantFailed: "Could not open settings automatically. Open the app on the phone and tap \"Grant\".",
        pinModalTitle: "🔒 Device Authentication",
        pinModalDesc: "Please enter the 4-digit PIN displayed on the phone:",
        pinInputPlaceholder: "Enter 4-digit PIN",
        pinSubmitBtn: "Verify & Connect",
        pinError: "Invalid PIN, please try again",
        dragOverlayText: "Drop files anywhere to upload to phone",
        uploadDone: "✅ Completed",
        uploadFail: "❌ Failed",
        loading: "Loading...",
        phoneTag: "Phone"
    }
};

// 自动根据系统语言初始化 (Auto-detect system / browser language)
let currentLang = localStorage.getItem('cs_lang') || (
    (navigator.language || navigator.userLanguage || 'zh').toLowerCase().startsWith('zh') ? 'zh' : 'en'
);

function t(key) {
    const dict = I18N[currentLang] || I18N.zh;
    return dict[key] || key;
}

function applyTranslations() {
    document.title = t('appTitle');
    const logoText = document.getElementById('appLogoText');
    if (logoText) logoText.innerText = t('logoName');

    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (key && I18N[currentLang] && I18N[currentLang][key]) {
            el.innerText = I18N[currentLang][key];
        }
    });

    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
        const key = el.getAttribute('data-i18n-placeholder');
        if (key && I18N[currentLang] && I18N[currentLang][key]) {
            el.placeholder = I18N[currentLang][key];
        }
    });

    const statusText = document.getElementById('statusText');
    if (statusText) {
        const isConnected = document.getElementById('statusIndicator').classList.contains('connected');
        statusText.innerText = isConnected ? t('connected') : t('disconnected');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    applyTranslations();
    initWebSocket();
    initEventListeners();
    fetchDeviceInfo();
    loadVolumes();
});

function setConnectionState(connected) {
    const indicator = document.getElementById('statusIndicator');
    const statusText = document.getElementById('statusText');
    if (!indicator || !statusText) return;
    indicator.className = 'status-indicator ' + (connected ? 'connected' : 'disconnected');
    statusText.innerText = t(connected ? 'connected' : 'disconnected');
}

function showPinModal() {
    document.getElementById('pinModal').classList.add('active');
}

/** token 失效（例如手机端服务重启后内存里的 token 清空）时重新要求配对，并停掉重连循环。 */
function clearAuthAndRequirePin() {
    token = '';
    localStorage.removeItem('cs_token');
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }
    if (ws) {
        ws.onclose = null;
        ws.onerror = null;
        ws.close();
        ws = null;
    }
    setConnectionState(false);
    showPinModal();
}

function initWebSocket() {
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }
    if (ws) {
        ws.onclose = null;
        ws.onerror = null;
        ws.close();
        ws = null;
    }

    // 尚未通过 PIN 配对时不建立 WebSocket：服务端会拒绝未鉴权握手（401），
    // 若照常重连就会变成每 2 秒一次的无意义连接。
    if (!token) {
        setConnectionState(false);
        return;
    }

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}`;
    const socket = new WebSocket(wsUrl);
    ws = socket;

    socket.onopen = () => {
        if (ws !== socket) return;
        setConnectionState(true);
        // 握手成功说明服务端已校验通过 token，此时才关闭配对弹窗
        document.getElementById('pinModal').classList.remove('active');

        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
        }
        heartbeatTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({
                    type: 'PING',
                    timestamp: Date.now()
                }));
            }
        }, 15000);
    };

    socket.onmessage = (event) => {
        if (ws !== socket) return;
        try {
            const data = JSON.parse(event.data);
            handleWsMessage(data);
        } catch (e) {
            console.error('Invalid WS payload', e);
        }
    };

    socket.onclose = () => {
        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
            heartbeatTimer = null;
        }
        if (ws !== socket) return;
        setConnectionState(false);
        if (token && !reconnectTimer) {
            reconnectTimer = setTimeout(() => {
                reconnectTimer = null;
                initWebSocket();
            }, 2000);
        }
    };

    socket.onerror = () => {
        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
            heartbeatTimer = null;
        }
        if (ws !== socket) return;
        socket.close();
    };
}

function handleWsMessage(msg) {
    if (msg.type === 'CLIPBOARD_PUSH') {
        const text = typeof msg.payload === 'string' ? msg.payload : JSON.stringify(msg.payload);
        phoneClipText = text;
        document.getElementById('phoneClipContent').innerText = text;
    } else if (msg.type === 'DEVICE_STATUS') {
        updateDeviceStatus(msg.payload);
    }
}

function updateDeviceStatus(info) {
    if (!info) return;
    if (info.deviceName) document.getElementById('deviceTag').innerText = info.deviceName;
    if (info.batteryLevel !== undefined) {
        document.getElementById('batteryTag').innerText = `🔋 ${info.batteryLevel}%${info.isCharging ? ' ⚡' : ''}`;
    }
}

async function fetchDeviceInfo() {
    try {
        const res = await fetch('/api/info');
        const info = await res.json();
        updateDeviceStatus(info);
        if (info.browseRoot) browseRoot = info.browseRoot;
        if (typeof info.hasAllFilesAccess === 'boolean') updatePermBanner(info.hasAllFilesAccess);
        if (info.authRequired && !token) {
            showPinModal();
        }
    } catch (e) {
        console.error('Fetch info error', e);
    }
}

async function browseDirectory(path) {
    const grid = document.getElementById('fileGrid');
    if (grid) grid.innerHTML = `<div class="loading-spinner">${escapeHtml(t('loading'))}</div>`;
    try {
        const res = await fetch(`/api/files/list?path=${encodeURIComponent(path || '')}&token=${encodeURIComponent(token)}`);
        if (res.status === 401) {
            clearAuthAndRequirePin();
            return;
        }
        if (!res.ok) {
            let message = t('dirError');
            try {
                const err = await res.json();
                if (err && err.error) message = err.error;
            } catch (e) { /* 非 JSON 响应 */ }
            renderDirMessage(message);
            return;
        }
        const data = await res.json();
        currentDir = data.path || '';
        currentVolumeRoot = data.volumeRoot || data.root || '';
        if (data.root) browseRoot = data.root;
        const tag = document.getElementById('volumeTag');
        if (tag && currentVolumeRoot) tag.innerText = volumeLabel(currentVolumeRoot);
        if (typeof data.hasAllFilesAccess === 'boolean') updatePermBanner(data.hasAllFilesAccess);
        renderBreadcrumb(data);
        renderFileList(data.entries || []);
    } catch (e) {
        console.error('Browse error', e);
        renderDirMessage(t('dirError'));
    }
}

// ------------------------------------------------------------------
// 存储卷（内部存储 / 外置 SD 卡）：先选卷，再逐级向内浏览
// ------------------------------------------------------------------

async function loadVolumes() {
    try {
        const res = await fetch(`/api/fs/volumes?token=${encodeURIComponent(token)}`);
        if (res.status === 401) {
            clearAuthAndRequirePin();
            return;
        }
        const data = await res.json();
        volumes = data.volumes || [];
        if (typeof data.hasAllFilesAccess === 'boolean') updatePermBanner(data.hasAllFilesAccess);
        if (volumes.length === 0) {
            renderDirMessage(t('dirError'));
            return;
        }
        // 只有一个存储卷时直接进入它的根目录；有多个（含外置卡）先让用户选
        if (volumes.length === 1) {
            browseDirectory(volumes[0].path);
        } else {
            renderVolumePicker();
        }
    } catch (e) {
        console.error('Load volumes error', e);
        renderDirMessage(t('dirError'));
    }
}

function volumeLabel(path) {
    const volume = volumes.find(v => v.path === path);
    if (!volume) return t('volumeInternal');
    if (volume.label) return volume.label;
    return volume.removable ? t('volumeRemovable') : t('volumeInternal');
}

function renderVolumePicker() {
    currentDir = '';
    currentVolumeRoot = '';
    const tag = document.getElementById('volumeTag');
    if (tag) tag.innerText = t('storageBtn');
    const box = document.getElementById('breadcrumb');
    if (box) {
        box.innerHTML = `<span class="current">${escapeHtml(t('storageBtn'))}</span>`;
        box.dataset.parent = '';
    }
    const up = document.getElementById('btnGoUp');
    if (up) up.disabled = true;

    const grid = document.getElementById('fileGrid');
    grid.innerHTML = volumes.map((v, i) => `
        <div class="file-card dir" data-vol-index="${i}">
            <div class="file-name" title="${escapeHtml(v.path)}">${v.removable ? '💾' : '📱'} ${escapeHtml(volumeLabel(v.path))}</div>
            <div class="file-meta">${escapeHtml(v.path)}</div>
        </div>`).join('');
    grid.querySelectorAll('.file-card.dir').forEach(card => {
        const idx = parseInt(card.getAttribute('data-vol-index'), 10);
        card.addEventListener('click', () => browseDirectory(volumes[idx].path));
    });
}

function reloadDirectory() {
    return browseDirectory(currentDir);
}

function renderDirMessage(message) {
    const grid = document.getElementById('fileGrid');
    if (grid) grid.innerHTML = `<div class="empty-state">${escapeHtml(message)}</div>`;
}

function updatePermBanner(hasAccess) {
    const banner = document.getElementById('permBanner');
    if (!banner) return;
    banner.style.display = hasAccess ? 'none' : 'flex';
}

function renderBreadcrumb(data) {
    const box = document.getElementById('breadcrumb');
    if (!box) return;
    const root = data.volumeRoot || data.root || browseRoot || '';
    const current = data.path || root;
    const relative = current.indexOf(root) === 0 ? current.slice(root.length) : current;
    const segments = relative.split('/').filter(Boolean);

    let html = `<button class="crumb" data-crumb="${escapeHtml(root)}">${escapeHtml(volumeLabel(root))}</button>`;
    let acc = root;
    segments.forEach(seg => {
        acc = acc.replace(/\/$/, '') + '/' + seg;
        html += `<span class="sep">/</span><button class="crumb" data-crumb="${escapeHtml(acc)}">${escapeHtml(seg)}</button>`;
    });
    box.innerHTML = html;
    box.querySelectorAll('.crumb').forEach(btn => {
        btn.addEventListener('click', () => browseDirectory(btn.getAttribute('data-crumb')));
    });
    box.dataset.parent = data.canGoUp ? (data.parent || '') : '';
    const up = document.getElementById('btnGoUp');
    if (up) up.disabled = !data.canGoUp;
}

async function requestFileAccess() {
    const hint = document.getElementById('permGrantHint');
    if (hint) hint.innerText = t('grantOpening');
    try {
        const res = await fetch(`/api/action/request-file-access?token=${encodeURIComponent(token)}`, { method: 'POST' });
        const data = await res.json();
        if (data.status === 'granted' || data.status === 'not_required') {
            updatePermBanner(true);
            if (hint) hint.innerText = '';
            reloadDirectory();
            return;
        }
        if (data.status === 'failed') {
            if (hint) hint.innerText = data.message || t('grantFailed');
            return;
        }
        if (hint) hint.innerText = t('grantWaiting');
        startAccessPolling();
    } catch (e) {
        console.error('Request file access error', e);
        if (hint) hint.innerText = t('grantFailed');
    }
}

function startAccessPolling() {
    if (accessPollTimer) return;
    let attempts = 0;
    accessPollTimer = setInterval(async () => {
        attempts++;
        try {
            const info = await (await fetch('/api/info')).json();
            if (info && info.hasAllFilesAccess) {
                stopAccessPolling();
                updatePermBanner(true);
                const hint = document.getElementById('permGrantHint');
                if (hint) hint.innerText = '';
                reloadDirectory();
                return;
            }
        } catch (e) { /* 忽略，继续轮询 */ }
        if (attempts >= 40) stopAccessPolling();
    }, 3000);
}

function stopAccessPolling() {
    if (accessPollTimer) {
        clearInterval(accessPollTimer);
        accessPollTimer = null;
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function copyToClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(text);
    } else {
        return new Promise((resolve, reject) => {
            const textArea = document.createElement("textarea");
            textArea.value = text;
            textArea.style.position = "fixed";
            textArea.style.left = "-999999px";
            textArea.style.top = "-999999px";
            document.body.appendChild(textArea);
            textArea.focus();
            textArea.select();
            try {
                const successful = document.execCommand('copy');
                document.body.removeChild(textArea);
                if (successful) {
                    resolve();
                } else {
                    reject(new Error('execCommand copy failed'));
                }
            } catch (err) {
                document.body.removeChild(textArea);
                reject(err);
            }
        });
    }
}

function renderFileList(files) {
    const grid = document.getElementById('fileGrid');
    if (!files || files.length === 0) {
        grid.innerHTML = `<div class="empty-state">${escapeHtml(t('emptyDir'))}</div>`;
        return;
    }
    const encodedToken = encodeURIComponent(token || '');
    const dirPaths = [];
    const cards = files.map(file => {
        const safeName = escapeHtml(file.name);
        const encodedPath = encodeURIComponent(file.path || '');
        if (file.isDirectory) {
            dirPaths.push(file.path || '');
            return `
        <div class="file-card dir" data-dir-index="${dirPaths.length - 1}">
            <div class="file-name" title="${safeName}">📁 ${safeName}</div>
            <div class="file-meta">${t('folder')}</div>
        </div>`;
        }
        return `
        <div class="file-card">
            <div class="file-name" title="${safeName}">📄 ${safeName}</div>
            <div class="file-meta">${formatBytes(file.size)}</div>
            <a href="/api/files/download?path=${encodedPath}&token=${encodedToken}" class="btn btn-secondary btn-sm" download="${safeName}">${t('download')}</a>
        </div>`;
    });
    grid.innerHTML = cards.join('');
    grid.querySelectorAll('.file-card.dir').forEach(card => {
        const idx = parseInt(card.getAttribute('data-dir-index'), 10);
        card.addEventListener('click', () => browseDirectory(dirPaths[idx]));
    });
}

function initEventListeners() {
    // 语言手动切换按钮 (Language Toggle Button)
    const langBtn = document.getElementById('langSwitchBtn');
    if (langBtn) {
        langBtn.addEventListener('click', () => {
            currentLang = currentLang === 'zh' ? 'en' : 'zh';
            localStorage.setItem('cs_lang', currentLang);
            applyTranslations();
            reloadDirectory();
        });
    }

    // 复制手机剪贴板
    document.getElementById('btnCopyPhoneClip').addEventListener('click', () => {
        if (phoneClipText) {
            copyToClipboard(phoneClipText).then(() => {
                alert(t('copiedToClipboard'));
            }).catch((err) => {
                console.error('Copy failed', err);
                alert(t('copyFailed'));
            });
        }
    });

    // 发送剪贴板到手机
    document.getElementById('btnSendClipboard').addEventListener('click', sendClipboardText);
    document.getElementById('textToSend').addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            sendClipboardText();
        }
    });

    // 手机浏览器打开
    document.getElementById('btnOpenUrl').addEventListener('click', async () => {
        const text = document.getElementById('textToSend').value.trim();
        if (!text) return;
        await fetch('/api/action/open-url', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: text, token: token })
        });
        alert(t('openUrlSent'));
    });

    // PIN 码提交
    document.getElementById('btnSubmitPin').addEventListener('click', async () => {
        const pin = document.getElementById('pinCodeInput').value.trim();
        if (!pin) return;
        const res = await fetch('/api/auth/verify', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pin: pin })
        });
        const data = await res.json();
        if (data.status === 'success' && data.token) {
            token = data.token;
            localStorage.setItem('cs_token', token);
            document.getElementById('pinModal').classList.remove('active');
            initWebSocket();
            browseDirectory('');
        } else {
            alert(t('pinError'));
        }
    });

    // 文件选择与拖拽
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('fileInput');
    dropzone.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => {
        handleFilesUpload(e.target.files);
        fileInput.value = '';   // 允许重复选择同一个文件
    });

    // 全局拖拽监听
    window.addEventListener('dragover', (e) => {
        e.preventDefault();
        document.getElementById('dragOverlay').classList.add('active');
    });
    window.addEventListener('dragleave', (e) => {
        if (e.relatedTarget === null) {
            document.getElementById('dragOverlay').classList.remove('active');
        }
    });
    window.addEventListener('drop', (e) => {
        e.preventDefault();
        document.getElementById('dragOverlay').classList.remove('active');
        if (e.dataTransfer && e.dataTransfer.files.length > 0) {
            handleFilesUpload(e.dataTransfer.files);
        }
    });

    // 目录工具条与权限引导
    const btnGoUp = document.getElementById('btnGoUp');
    if (btnGoUp) {
        btnGoUp.addEventListener('click', () => {
            const parent = document.getElementById('breadcrumb').dataset.parent;
            if (parent) browseDirectory(parent);
        });
    }
    const btnRefreshDir = document.getElementById('btnRefreshDir');
    if (btnRefreshDir) btnRefreshDir.addEventListener('click', reloadDirectory);
    const btnUploadHere = document.getElementById('btnUploadHere');
    if (btnUploadHere) btnUploadHere.addEventListener('click', () => fileInput.click());
    const btnStorage = document.getElementById('btnStorage');
    if (btnStorage) {
        btnStorage.addEventListener('click', () => {
            if (volumes.length > 1) {
                renderVolumePicker();
            } else if (volumes.length === 1) {
                browseDirectory(volumes[0].path);
            } else {
                loadVolumes();
            }
        });
    }
    const btnGrantAccess = document.getElementById('btnGrantAccess');
    if (btnGrantAccess) btnGrantAccess.addEventListener('click', requestFileAccess);
}

function sendClipboardText() {
    const text = document.getElementById('textToSend').value;
    if (!text) return;
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'CLIPBOARD_SEND',
            payload: text,
            timestamp: Date.now()
        }));
        document.getElementById('textToSend').value = '';
    }
}

function handleFilesUpload(files) {
    if (!files || files.length === 0) return;
    for (let file of files) {
        uploadSingleFile(file);
    }
}

function uploadSingleFile(file) {
    const list = document.getElementById('uploadList');
    const item = document.createElement('div');
    item.className = 'upload-item';
    const safeName = escapeHtml(file.name);
    item.innerHTML = `
        <div style="flex: 1; margin-right: 10px;">
            <div>${safeName} (${formatBytes(file.size)})</div>
            <div class="progress-bar-bg"><div class="progress-bar-fill"></div></div>
        </div>
        <span class="status-percent">0%</span>
    `;
    list.prepend(item);

    const fill = item.querySelector('.progress-bar-fill');
    const percentText = item.querySelector('.status-percent');

    const formData = new FormData();
    formData.append('file', file);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/files/upload?path=${encodeURIComponent(currentDir || '')}&token=${encodeURIComponent(token)}`);

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const p = Math.round((e.loaded / e.total) * 100);
            fill.style.width = p + '%';
            percentText.innerText = p + '%';
        }
    };

    xhr.onload = () => {
        if (xhr.status === 200) {
            percentText.innerText = t('uploadDone');
            reloadDirectory();
        } else {
            percentText.innerText = t('uploadFail');
        }
    };

    xhr.send(formData);
}

function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}
