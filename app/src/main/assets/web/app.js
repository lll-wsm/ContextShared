let token = localStorage.getItem('cs_token') || '';
let ws = null;
let reconnectTimer = null;
let currentCategory = 'downloads';

document.addEventListener('DOMContentLoaded', () => {
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
        document.getElementById('statusText').innerText = '已连接';
        document.getElementById('pinModal').classList.remove('active');
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
        if (ws !== socket) return;
        document.getElementById('statusIndicator').className = 'status-indicator disconnected';
        document.getElementById('statusText').innerText = '连接已断开 (重连中...)';
        if (!reconnectTimer) {
            reconnectTimer = setTimeout(() => {
                reconnectTimer = null;
                initWebSocket();
            }, 2000);
        }
    };

    socket.onerror = () => {
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
        grid.innerHTML = '<div class="empty-state">目录为空</div>';
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
            <a href="/api/files/download?path=${encodedPath}&token=${encodedToken}" class="btn btn-secondary btn-sm" download>下载</a>
        </div>
    `;
    }).join('');
}

function initEventListeners() {
    // 复制手机剪贴板
    document.getElementById('btnCopyPhoneClip').addEventListener('click', () => {
        const text = document.getElementById('phoneClipContent').innerText;
        if (text && text !== '暂无复制内容') {
            copyToClipboard(text).then(() => {
                alert('已复制到电脑剪贴板');
            }).catch((err) => {
                console.error('Copy failed', err);
                alert('复制失败，请手动复制');
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
        alert('已发送打开指令至手机');
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
            alert('PIN 码错误，请重新输入');
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
            percentText.innerText = '✅ 完成';
            fetchFiles(currentCategory);
        } else {
            percentText.innerText = '❌ 失败';
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
