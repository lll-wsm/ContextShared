let token = localStorage.getItem('cs_token') || '';
let ws = null;
let reconnectTimer = null;
let heartbeatTimer = null;
let currentCategory = 'downloads';

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
        noClipContent: "暂无复制内容",
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
        noClipContent: "No clipboard content yet",
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
    fetchFiles(currentCategory);
});

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

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}`;
    const socket = new WebSocket(wsUrl);
    ws = socket;

    socket.onopen = () => {
        if (ws !== socket) return;
        document.getElementById('statusIndicator').className = 'status-indicator connected';
        document.getElementById('statusText').innerText = t('connected');
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
        document.getElementById('statusIndicator').className = 'status-indicator disconnected';
        document.getElementById('statusText').innerText = t('disconnected');
        if (!reconnectTimer) {
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
        if (info.authRequired && !token) {
            document.getElementById('pinModal').classList.add('active');
        }
    } catch (e) {
        console.error('Fetch info error', e);
    }
}

async function fetchFiles(category) {
    try {
        const res = await fetch(`/api/files/list?category=${encodeURIComponent(category)}&token=${encodeURIComponent(token)}`);
        if (res.status === 401) {
            document.getElementById('pinModal').classList.add('active');
            return;
        }
        const files = await res.json();
        renderFileList(files);
    } catch (e) {
        console.error('Fetch files error', e);
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
        grid.innerHTML = `<div class="empty-state">${t('emptyDir')}</div>`;
        return;
    }
    grid.innerHTML = files.map(file => {
        const safeName = escapeHtml(file.name);
        const encodedPath = encodeURIComponent(file.path || '');
        const encodedToken = encodeURIComponent(token || '');
        return `
        <div class="file-card">
            <div class="file-name" title="${safeName}">📄 ${safeName}</div>
            <div class="file-meta">${formatBytes(file.size)}</div>
            <a href="/api/files/download?path=${encodedPath}&token=${encodedToken}" class="btn btn-secondary btn-sm" download>${t('download')}</a>
        </div>
    `;
    }).join('');
}

function initEventListeners() {
    // 语言手动切换按钮 (Language Toggle Button)
    const langBtn = document.getElementById('langSwitchBtn');
    if (langBtn) {
        langBtn.addEventListener('click', () => {
            currentLang = currentLang === 'zh' ? 'en' : 'zh';
            localStorage.setItem('cs_lang', currentLang);
            applyTranslations();
            fetchFiles(currentCategory);
        });
    }

    // 复制手机剪贴板
    document.getElementById('btnCopyPhoneClip').addEventListener('click', () => {
        const text = document.getElementById('phoneClipContent').innerText;
        if (text && text !== t('noClipContent') && text !== '暂无复制内容') {
            copyToClipboard(text).then(() => {
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
            fetchFiles(currentCategory);
        } else {
            alert(t('pinError'));
        }
    });

    // 文件选择与拖拽
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('fileInput');
    dropzone.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => handleFilesUpload(e.target.files));

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

    // 标签切换
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            currentCategory = e.target.getAttribute('data-category');
            fetchFiles(currentCategory);
        });
    });
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
    xhr.open('POST', `/api/files/upload?token=${encodeURIComponent(token)}`);

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
            fetchFiles(currentCategory);
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
