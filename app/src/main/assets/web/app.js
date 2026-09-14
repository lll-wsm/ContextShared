let token = localStorage.getItem('cs_token') || '';
let ws = null;
let reconnectTimer = null;
let heartbeatTimer = null;
let currentDir = '';
let browseRoot = '';
let currentVolumeRoot = '';
let volumes = [];
let accessPollTimer = null;
/** 当前目录的原始条目（渲染时会再做筛选/排序） */
let currentEntries = [];
/** 已勾选的路径 */
let selectedPaths = new Set();
/** 排序：name | size | time ；sortDesc 降序 */
let sortKey = localStorage.getItem('cs_sort') || 'name';
let sortDesc = localStorage.getItem('cs_sort_desc') === '1';
/** 当前目录内筛选关键字 */
let filterText = '';
/** 搜索模式下的结果提示（null = 正常浏览） */
let searchState = null;
/** Shift 连选锚点 */
let lastCheckedIndex = -1;
/** 文件库视图：list（默认，一行一项）/ grid */
let currentView = localStorage.getItem('cs_view') || 'list';
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
        viewList: "☰ 列表",
        viewGrid: "▦ 网格",
        switchToList: "当前：网格视图（点击切换到列表）",
        switchToGrid: "当前：列表视图（点击切换到网格）",
        noMatch: "没有符合筛选条件的项目",
        preview: "预览",
        zipFolder: "打包下载这个文件夹",
        selectAll: "全选",
        selectedNone: "已选 0 项",
        selectedCount: "已选 %d 项",
        downloadSelected: "下载选中",
        zipSelected: "打包 ZIP",
        clearSelection: "取消选择",
        multiDownloadHint: "正在依次下载 %d 个文件，浏览器可能会询问“允许下载多个文件”",
        zipForFolders: "选中项含文件夹，已改为打包 ZIP 下载",
        zipStarted: "正在打包，稍后浏览器会开始下载（可关闭本页）",
        zipStartedMany: "正在打包 %d 项，稍后浏览器会开始下载（可关闭本页）",
        uploadFolder: "上传文件夹",
        filterPlaceholder: "筛选当前目录…",
        sortName: "名称",
        sortSize: "大小",
        sortTime: "修改时间",
        searchBtn: "🔍 搜索",
        searchPlaceholder: "在当前目录及子目录里搜文件名…",
        searchGo: "开始搜索",
        searchExit: "退出搜索",
        searchNeedKeyword: "请输入要搜索的文件名",
        searching: "搜索中…（大目录可能要几秒）",
        searchResult: "在 %s 下找到 %d 个匹配项",
        searchTruncated: "结果已截断（限 200 条/6 秒/8 层）",
        eta: "剩余",
        dirError: "目录无法访问",
        permBannerText: "未授予「所有文件访问权限」，当前只能看到媒体文件和共享目录",
        grantAccess: "去手机授权",
        grantOpening: "已在手机上打开设置页，请开启「允许管理所有文件」",
        grantWaiting: "等待手机上完成授权...",
        grantFailed: "无法自动打开设置页，请在手机上打开 App 后点「去授权」",
        safCardTitle: "该目录受 Android 系统保护",
        safCardDesc: "Android/data 与 Android/obb 不在「所有文件访问权限」范围内（官方明确排除），必须在手机上用系统文件选择器手动授权一次。点下面的按钮，手机上会弹出选择器，直接选当前文件夹并允许即可（Android 11/12 可行，Android 13+ 系统已禁止，任何第三方应用都无法访问）。",
        safGrantBtn: "在手机上授权访问",
        safGrantOpening: "请查看手机屏幕，在选择器里选当前文件夹 → 允许",
        safGrantWaiting: "等待手机上完成授权…（授权后此页面会自动刷新）",
        safGrantFailed: "授权请求失败，请重试",
        safUnsupported: "当前系统版本已不允许第三方应用访问该目录",
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
        viewList: "☰ List",
        viewGrid: "▦ Grid",
        switchToList: "Current: grid view (tap to switch to list)",
        switchToGrid: "Current: list view (tap to switch to grid)",
        noMatch: "No items match the filter",
        preview: "Preview",
        zipFolder: "Download this folder as ZIP",
        selectAll: "Select all",
        selectedNone: "0 selected",
        selectedCount: "%d selected",
        downloadSelected: "Download selected",
        zipSelected: "ZIP selected",
        clearSelection: "Clear selection",
        multiDownloadHint: "Downloading %d files one by one; the browser may ask to allow multiple downloads",
        zipForFolders: "Selection contains folders, switched to ZIP download",
        zipStarted: "Packing… your browser will start downloading shortly (you can close this page)",
        zipStartedMany: "Packing %d items… your browser will start downloading shortly",
        uploadFolder: "Upload folder",
        filterPlaceholder: "Filter current folder…",
        sortName: "Name",
        sortSize: "Size",
        sortTime: "Modified",
        searchBtn: "🔍 Search",
        searchPlaceholder: "Search file names in this folder and below…",
        searchGo: "Search",
        searchExit: "Exit search",
        searchNeedKeyword: "Enter a file name to search",
        searching: "Searching… (large trees can take a few seconds)",
        searchResult: "Found %d matches under %s",
        searchTruncated: "results truncated (200 items / 6s / 8 levels)",
        eta: "ETA",
        dirError: "Directory not accessible",
        permBannerText: "\"All files access\" is not granted: only media files and the shared folder are visible",
        grantAccess: "Grant on phone",
        grantOpening: "Settings opened on the phone. Turn on \"Allow management of all files\".",
        grantWaiting: "Waiting for the grant on the phone...",
        grantFailed: "Could not open settings automatically. Open the app on the phone and tap \"Grant\".",
        safCardTitle: "This folder is protected by Android",
        safCardDesc: "Android/data and Android/obb are excluded from \"All files access\" (explicitly, per the docs), so a one-time manual grant through the system folder picker is required. Tap the button below, then choose this folder and allow it on the phone (works on Android 11/12; Android 13+ blocks it for every third-party app).",
        safGrantBtn: "Grant access on phone",
        safGrantOpening: "Look at the phone: pick this folder in the picker, then allow",
        safGrantWaiting: "Waiting for the grant on the phone... this page refreshes automatically",
        safGrantFailed: "Request failed, please try again",
        safUnsupported: "This Android version no longer allows third-party apps to access that folder",
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
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closePreview();
    });
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
            let payload = null;
            try {
                payload = await res.json();
                if (payload && payload.error) message = payload.error;
            } catch (e) { /* 非 JSON 响应 */ }
            if (payload && payload.needAuth) {
                // Android/data、Android/obb：需在手机上用系统文件选择器授权一次
                renderSafAuthCard(payload, path);
                return;
            }
            renderDirMessage(message);
            return;
        }
        const data = await res.json();
        currentDir = data.path || '';
        currentVolumeRoot = data.volumeRoot || data.root || '';
        if (data.root) browseRoot = data.root;
        localStorage.setItem('cs_dir', currentDir);
        searchState = null;
        const tag = document.getElementById('volumeTag');
        if (tag && currentVolumeRoot) tag.innerText = volumeLabel(currentVolumeRoot);
        if (typeof data.hasAllFilesAccess === 'boolean') updatePermBanner(data.hasAllFilesAccess);
        renderBreadcrumb(data);
        currentEntries = data.entries || [];
        selectedPaths = new Set();
        lastCheckedIndex = -1;
        renderEntries();
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
            // 记住上次所在目录（刷新/重开后回到原处）
            const saved = localStorage.getItem('cs_dir');
            browseDirectory(saved && saved !== volumes[0].path ? saved : volumes[0].path);
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
    if (volume) {
        if (volume.label) return volume.label;
        return volume.removable ? t('volumeRemovable') : t('volumeInternal');
    }
    // SAF 受限目录（Android/data、Android/obb）：用末级目录名标注
    const parts = String(path || '').split('/').filter(Boolean);
    const last = parts[parts.length - 1] || '';
    if (parts.indexOf('Android') >= 0 && (last === 'data' || last === 'obb')) {
        return '🔒 ' + last;
    }
    return last || t('volumeInternal');
}

/** 受限目录（Android/data、Android/obb）的授权卡片。 */
function renderSafAuthCard(payload, path) {
    const grid = document.getElementById('fileGrid');
    if (!grid) return;
    const supported = payload.supported !== false;
    const button = supported
        ? `<button class="btn btn-primary btn-sm" id="btnSafGrant">${escapeHtml(t('safGrantBtn'))}</button>`
        : '';
    grid.innerHTML = `
        <div class="empty-state" style="grid-column: 1 / -1; text-align: left; line-height: 1.5;">
            <div style="font-weight: 600; margin-bottom: 6px;">🔒 ${escapeHtml(t('safCardTitle'))}</div>
            <div style="font-size: 0.85rem; color: var(--text-muted); margin-bottom: 12px;">${escapeHtml(t('safCardDesc'))}</div>
            ${button}
            <div class="perm-banner-hint" id="safHint">${supported ? '' : escapeHtml(t('safUnsupported'))}</div>
        </div>`;
    const grantButton = document.getElementById('btnSafGrant');
    if (grantButton) {
        grantButton.addEventListener('click', () => requestSafAccess(payload.target, path));
    }
}

async function requestSafAccess(target, path) {
    const hint = document.getElementById('safHint');
    if (hint) hint.innerText = t('safGrantOpening');
    try {
        const res = await fetch(`/api/action/request-saf-access?target=${encodeURIComponent(target)}&token=${encodeURIComponent(token)}`, { method: 'POST' });
        const data = await res.json();
        if (data.status === 'granted') {
            browseDirectory(path);
            return;
        }
        if (data.status === 'unsupported') {
            if (hint) hint.innerText = data.hint || t('safUnsupported');
            return;
        }
        if (hint) hint.innerText = t('safGrantWaiting');
        startSafPolling(target, path);
    } catch (e) {
        console.error('Request SAF access error', e);
        if (hint) hint.innerText = t('safGrantFailed');
    }
}

function startSafPolling(target, path) {
    stopAccessPolling();
    let attempts = 0;
    accessPollTimer = setInterval(async () => {
        attempts++;
        try {
            const data = await (await fetch(`/api/fs/grants?token=${encodeURIComponent(token)}`)).json();
            const granted = (data.grants || []).some(g => g.target === target);
            if (granted) {
                stopAccessPolling();
                browseDirectory(path);
                return;
            }
        } catch (e) { /* 忽略，继续轮询 */ }
        if (attempts >= 40) stopAccessPolling();
    }, 3000);
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

// ------------------------------------------------------------------
// 文件类型图标 / 预览判定
// ------------------------------------------------------------------

const EXT_GROUPS = [
    { kind: 'image', icon: '🖼', exts: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif', 'avif', 'svg'] },
    { kind: 'video', icon: '🎬', exts: ['mp4', 'm4v', 'mov', 'mkv', 'avi', 'webm', '3gp', 'flv', 'ts'] },
    { kind: 'audio', icon: '🎵', exts: ['mp3', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'flac', 'amr'] },
    { kind: 'archive', icon: '🗜', exts: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso'] },
    { kind: 'apk', icon: '📦', exts: ['apk', 'apks', 'xapk', 'aab'] },
    { kind: 'pdf', icon: '📕', exts: ['pdf'] },
    { kind: 'doc', icon: '📄', exts: ['txt', 'md', 'log', 'json', 'xml', 'csv', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'epub'] },
];

function fileKind(file) {
    if (!file || file.isDirectory) return 'dir';
    const name = String(file.name || '');
    const dot = name.lastIndexOf('.');
    const ext = dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
    for (let i = 0; i < EXT_GROUPS.length; i++) {
        if (EXT_GROUPS[i].exts.indexOf(ext) >= 0) return EXT_GROUPS[i].kind;
    }
    return 'other';
}

function fileIcon(file) {
    if (file && file.isDirectory) return '📁';
    const kind = fileKind(file);
    for (let i = 0; i < EXT_GROUPS.length; i++) {
        if (EXT_GROUPS[i].kind === kind) return EXT_GROUPS[i].icon;
    }
    return '📄';
}

/** 能否在浏览器里直接预览（图片/视频/音频/PDF/纯文本） */
function canPreview(file) {
    const kind = fileKind(file);
    if (kind === 'image' || kind === 'video' || kind === 'audio' || kind === 'pdf') return true;
    if (kind === 'doc' && !/\.(docx?|xlsx?|pptx?|epub)$/i.test(String(file.name || ''))) return true;
    return false;
}

// ------------------------------------------------------------------
// 筛选 / 排序 / 渲染（支持多选）
// ------------------------------------------------------------------

function visibleEntries() {
    const keyword = filterText.trim().toLowerCase();
    let list = currentEntries.slice();
    if (keyword) {
        list = list.filter(f => String(f.name || '').toLowerCase().indexOf(keyword) >= 0);
    }
    const direction = sortDesc ? -1 : 1;
    list.sort((a, b) => {
        if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;   // 目录始终在前
        let result = 0;
        if (sortKey === 'size') result = (a.size || 0) - (b.size || 0);
        else if (sortKey === 'time') result = (a.lastModified || 0) - (b.lastModified || 0);
        if (result === 0) result = String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN');
        return result * direction;
    });
    return list;
}

function renderEntries() {
    const grid = document.getElementById('fileGrid');
    if (!grid) return;
    grid.classList.toggle('list', currentView === 'list');
    updateViewButton();
    const entries = visibleEntries();
    if (entries.length === 0) {
        const message = currentEntries.length === 0 ? t('emptyDir') : t('noMatch');
        grid.innerHTML = `<div class="empty-state">${escapeHtml(message)}</div>`;
        updateBatchBar();
        return;
    }
    const encodedToken = encodeURIComponent(token || '');
    const isList = currentView === 'list';
    const html = entries.map((file, index) => {
        const safeName = escapeHtml(file.name);
        const attrPath = escapeHtml(file.path || '');
        const encodedPath = encodeURIComponent(file.path || '');
        const downloadUrl = `/api/files/download?path=${encodedPath}&token=${encodedToken}`;
        const checked = selectedPaths.has(file.path) ? ' checked' : '';
        const selected = selectedPaths.has(file.path) ? ' selected' : '';
        const kind = fileKind(file);
        const classes = [isList ? 'file-row' : 'file-card', 'entry'];
        classes.push(file.isDirectory ? 'entry-dir' : 'entry-file');
        if (kind !== 'dir') classes.push('kind-' + kind);
        const checkbox = `<input type="checkbox" class="entry-check" data-index="${index}" data-path="${attrPath}"${checked}>`;
        const actions = [];
        if (canPreview(file)) {
            actions.push(`<button class="icon-btn entry-preview" data-path="${attrPath}" title="${escapeHtml(t('preview'))}">👁</button>`);
        }
        if (file.isDirectory && canZip(file.path)) {
            actions.push(`<button class="icon-btn entry-zip" data-path="${attrPath}" title="${escapeHtml(t('zipFolder'))}">🗜</button>`);
        }
        const actionHtml = actions.length ? `<span class="row-actions">${actions.join('')}</span>` : '';
        const downloadLink = file.isDirectory ? ''
            : `<a href="${downloadUrl}" class="btn btn-secondary btn-sm" download="${safeName}">${t('download')}</a>`;
        if (isList) {
            return `
        <div class="${classes.join(' ')}${selected}" data-path="${attrPath}">
            ${checkbox}
            <span class="file-row-icon">${fileIcon(file)}</span>
            <span class="file-row-name" title="${safeName}">${safeName}</span>
            <span class="file-row-meta">${file.isDirectory ? t('folder') : formatBytes(file.size)}</span>
            <span class="file-row-date">${formatDate(file.lastModified)}</span>
            ${actionHtml}
            ${downloadLink}
        </div>`;
        }
        return `
        <div class="${classes.join(' ')}${selected}" data-path="${attrPath}">
            <div style="display:flex; align-items:center; gap:0.4rem;">${checkbox}
                <div class="file-name" title="${safeName}">${fileIcon(file)} ${safeName}</div></div>
            <div class="file-meta">${file.isDirectory ? t('folder') : formatBytes(file.size)} · ${formatDate(file.lastModified)}</div>
            <div style="display:flex; align-items:center; gap:0.35rem; flex-wrap:wrap;">${downloadLink}${actionHtml}</div>
        </div>`;
    }).join('');
    grid.innerHTML = html;
    bindEntryEvents();
    updateBatchBar();
}

/** 受限目录（Android/data、obb）也可以打包（走 SAF） */
function canZip(path) {
    return !!path;
}

function bindEntryEvents() {
    const grid = document.getElementById('fileGrid');
    grid.querySelectorAll('.entry-check').forEach(box => {
        box.addEventListener('click', e => e.stopPropagation());
        box.addEventListener('change', e => onCheckboxChange(box, e));
    });
    grid.querySelectorAll('.entry-preview').forEach(btn => {
        btn.addEventListener('click', e => {
            e.stopPropagation();
            openPreview(btn.getAttribute('data-path'));
        });
    });
    grid.querySelectorAll('.entry-zip').forEach(btn => {
        btn.addEventListener('click', e => {
            e.stopPropagation();
            submitZip([btn.getAttribute('data-path')]);
        });
    });
    grid.querySelectorAll('.entry-dir').forEach(row => {
        row.addEventListener('click', e => {
            if (e.target && e.target.closest && e.target.closest('a, button, input')) return;
            browseDirectory(row.getAttribute('data-path'));
        });
    });
    grid.querySelectorAll('.kind-image, .kind-video, .kind-audio, .kind-pdf').forEach(row => {
        row.addEventListener('click', e => {
            if (e.target && e.target.closest && e.target.closest('a, button, input')) return;
            openPreview(row.getAttribute('data-path'));
        });
    });
}

// ------------------------------------------------------------------
// 多选与批量操作
// ------------------------------------------------------------------

function onCheckboxChange(box, event) {
    const path = box.getAttribute('data-path');
    const index = parseInt(box.getAttribute('data-index'), 10);
    const entries = visibleEntries();
    if (event && event.shiftKey && lastCheckedIndex >= 0 && lastCheckedIndex < entries.length) {
        const from = Math.min(lastCheckedIndex, index);
        const to = Math.max(lastCheckedIndex, index);
        for (let i = from; i <= to; i++) selectedPaths.add(entries[i].path);
    } else if (box.checked) {
        selectedPaths.add(path);
    } else {
        selectedPaths.delete(path);
    }
    lastCheckedIndex = index;
    renderEntries();
}

function selectedEntries() {
    return currentEntries.filter(f => selectedPaths.has(f.path));
}

function updateBatchBar() {
    const bar = document.getElementById('batchBar');
    if (!bar) return;
    const count = selectedPaths.size;
    bar.classList.toggle('active', currentEntries.length > 0 || count > 0);
    const countLabel = document.getElementById('selectedCount');
    if (countLabel) {
        countLabel.innerText = count === 0 ? t('selectedNone') : t('selectedCount').replace('%d', String(count));
    }
    const all = document.getElementById('selectAll');
    if (all) {
        const entries = visibleEntries();
        all.checked = entries.length > 0 && entries.every(f => selectedPaths.has(f.path));
        all.disabled = entries.length === 0;
    }
}

function setBatchHint(message) {
    const hint = document.getElementById('batchHint');
    if (hint) hint.innerText = message || '';
}

function batchDownload() {
    const items = selectedEntries();
    if (items.length === 0) return;
    if (items.some(f => f.isDirectory)) {
        // 目录没法“逐个下载”，自动改成打包
        setBatchHint(t('zipForFolders'));
        submitZip(items.map(f => f.path));
        return;
    }
    setBatchHint(t('multiDownloadHint').replace('%d', String(items.length)));
    items.forEach((item, index) => {
        setTimeout(() => triggerDownload(item.path), index * 400);
    });
}

function triggerDownload(path) {
    const link = document.createElement('a');
    link.href = `/api/files/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(token)}`;
    link.setAttribute('download', '');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
}

/** 用隐藏表单 POST 触发 ZIP 下载：纯流式、不占内存、浏览器只下一个文件 */
function submitZip(paths) {
    if (!paths || paths.length === 0) return;
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = `/api/files/zip?token=${encodeURIComponent(token)}`;
    form.style.display = 'none';
    paths.forEach(p => {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'paths';
        input.value = p;
        form.appendChild(input);
    });
    document.body.appendChild(form);
    form.submit();
    setTimeout(() => { if (form.parentNode) form.parentNode.removeChild(form); }, 2000);
    const isMany = paths.length > 1;
    setBatchHint(isMany ? t('zipStartedMany').replace('%d', String(paths.length)) : t('zipStarted'));
}

// ------------------------------------------------------------------
// 预览（图片/视频/音频/PDF/文本）
// ------------------------------------------------------------------

function openPreview(path) {
    const file = currentEntries.filter(f => f.path === path)[0] || { name: path };
    const overlay = document.getElementById('previewOverlay');
    const body = document.getElementById('previewBody');
    if (!overlay || !body) return;
    const inlineUrl = `/api/files/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(token)}&inline=1`;
    const kind = fileKind(file);
    if (kind === 'image') {
        body.innerHTML = `<img src="${inlineUrl}" alt="">`;
    } else if (kind === 'video') {
        body.innerHTML = `<video src="${inlineUrl}" controls autoplay playsinline></video>`;
    } else if (kind === 'audio') {
        body.innerHTML = `<audio src="${inlineUrl}" controls autoplay></audio>`;
    } else {
        body.innerHTML = `<iframe src="${inlineUrl}" title="preview"></iframe>`;
    }
    document.getElementById('previewName').innerText = file.name || '';
    const download = document.getElementById('previewDownload');
    download.href = `/api/files/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(token)}`;
    download.setAttribute('download', file.name || '');
    overlay.style.display = 'flex';
}

function closePreview() {
    const overlay = document.getElementById('previewOverlay');
    if (!overlay || overlay.style.display === 'none') return;
    overlay.style.display = 'none';
    document.getElementById('previewBody').innerHTML = '';   // 同时停掉播放
}

// ------------------------------------------------------------------
// 递归搜索
// ------------------------------------------------------------------

function toggleSearchBar() {
    const bar = document.getElementById('searchBar');
    if (!bar) return;
    const show = bar.style.display === 'none';
    bar.style.display = show ? 'flex' : 'none';
    if (show) {
        const input = document.getElementById('searchInput');
        if (input) input.focus();
    }
}

async function runSearch() {
    const input = document.getElementById('searchInput');
    const hint = document.getElementById('searchHint');
    const query = input ? (input.value || '').trim() : '';
    if (!query) {
        if (hint) hint.innerText = t('searchNeedKeyword');
        return;
    }
    if (hint) hint.innerText = t('searching');
    try {
        const res = await fetch(`/api/fs/search?path=${encodeURIComponent(currentDir || '')}&q=${encodeURIComponent(query)}&token=${encodeURIComponent(token)}`);
        if (res.status === 401) {
            clearAuthAndRequirePin();
            return;
        }
        const data = await res.json();
        if (!res.ok) {
            if (hint) hint.innerText = (data && data.error) || t('dirError');
            return;
        }
        currentEntries = data.entries || [];
        selectedPaths = new Set();
        filterText = '';
        const filter = document.getElementById('filterInput');
        if (filter) filter.value = '';
        searchState = { query: query, root: data.root };
        renderEntries();
        if (hint) {
            let text = t('searchResult').replace('%d', String(currentEntries.length)).replace('%s', String(data.root || ''));
            if (data.truncated) text += ' · ' + t('searchTruncated');
            hint.innerText = text;
        }
    } catch (e) {
        console.error('Search error', e);
        if (hint) hint.innerText = t('dirError');
    }
}

function exitSearch() {
    searchState = null;
    const hint = document.getElementById('searchHint');
    if (hint) hint.innerText = '';
    const bar = document.getElementById('searchBar');
    if (bar) bar.style.display = 'none';
    reloadDirectory();
}


/** 列表视图里显示“MM-DD HH:mm”。 */
function formatDate(timestamp) {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    if (isNaN(d.getTime())) return '';
    const pad = n => String(n).padStart(2, '0');
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 根据当前视图更新切换按钮的文案与提示。 */
function updateViewButton() {
    const button = document.getElementById('btnToggleView');
    if (!button) return;
    const isList = currentView === 'list';
    button.innerText = isList ? t('viewList') : t('viewGrid');
    button.title = isList ? t('switchToGrid') : t('switchToList');
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

    // 上传整个文件夹（webkitdirectory，保留目录结构）
    const dirInput = document.getElementById('dirInput');
    const btnUploadDir = document.getElementById('btnUploadDir');
    if (btnUploadDir && dirInput) {
        btnUploadDir.addEventListener('click', () => dirInput.click());
        dirInput.addEventListener('change', (e) => {
            handleFilesUpload(e.target.files);
            dirInput.value = '';
        });
    }

    // 筛选当前目录
    const filterInput = document.getElementById('filterInput');
    if (filterInput) {
        filterInput.addEventListener('input', () => {
            filterText = filterInput.value || '';
            renderEntries();
        });
    }

    // 排序
    const sortSelect = document.getElementById('sortSelect');
    const btnSortDir = document.getElementById('btnSortDir');
    if (sortSelect) {
        sortSelect.value = sortKey;
        sortSelect.addEventListener('change', () => {
            sortKey = sortSelect.value;
            localStorage.setItem('cs_sort', sortKey);
            renderEntries();
        });
    }
    if (btnSortDir) {
        btnSortDir.innerText = sortDesc ? '↓' : '↑';
        btnSortDir.addEventListener('click', () => {
            sortDesc = !sortDesc;
            localStorage.setItem('cs_sort_desc', sortDesc ? '1' : '0');
            btnSortDir.innerText = sortDesc ? '↓' : '↑';
            renderEntries();
        });
    }

    // 多选 / 批量操作
    const selectAll = document.getElementById('selectAll');
    if (selectAll) {
        selectAll.addEventListener('change', () => {
            const entries = visibleEntries();
            if (selectAll.checked) {
                entries.forEach(f => selectedPaths.add(f.path));
            } else {
                selectedPaths = new Set();
            }
            renderEntries();
        });
    }
    const btnBatchDownload = document.getElementById('btnBatchDownload');
    if (btnBatchDownload) btnBatchDownload.addEventListener('click', batchDownload);
    const btnBatchZip = document.getElementById('btnBatchZip');
    if (btnBatchZip) btnBatchZip.addEventListener('click', () => submitZip(selectedEntries().map(f => f.path)));
    const btnClearSelection = document.getElementById('btnClearSelection');
    if (btnClearSelection) {
        btnClearSelection.addEventListener('click', () => {
            selectedPaths = new Set();
            setBatchHint('');
            renderEntries();
        });
    }

    // 搜索
    const btnSearchToggle = document.getElementById('btnSearchToggle');
    if (btnSearchToggle) btnSearchToggle.addEventListener('click', toggleSearchBar);
    const btnSearchGo = document.getElementById('btnSearchGo');
    if (btnSearchGo) btnSearchGo.addEventListener('click', runSearch);
    const searchInput = document.getElementById('searchInput');
    if (searchInput) {
        searchInput.addEventListener('keydown', e => {
            if (e.key === 'Enter') runSearch();
        });
    }
    const btnSearchExit = document.getElementById('btnSearchExit');
    if (btnSearchExit) btnSearchExit.addEventListener('click', exitSearch);

    // 预览遮罩
    const previewClose = document.getElementById('previewClose');
    if (previewClose) previewClose.addEventListener('click', closePreview);
    const previewOverlay = document.getElementById('previewOverlay');
    if (previewOverlay) {
        previewOverlay.addEventListener('click', e => {
            if (e.target === previewOverlay) closePreview();
        });
    }

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
    window.addEventListener('drop', async (e) => {
        e.preventDefault();
        document.getElementById('dragOverlay').classList.remove('active');
        const items = e.dataTransfer && e.dataTransfer.items;
        if (items && items.length > 0 && items[0].webkitGetAsEntry) {
            try {
                const collected = await collectDroppedFiles(items);
                if (collected.length > 0) {
                    handleFilesUpload(collected);
                    return;
                }
            } catch (err) {
                console.error('Drop entries error', err);
            }
        }
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
    const btnToggleView = document.getElementById('btnToggleView');
    if (btnToggleView) {
        btnToggleView.addEventListener('click', () => {
            currentView = currentView === 'list' ? 'grid' : 'list';
            localStorage.setItem('cs_view', currentView);
            reloadDirectory();
        });
    }
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

/** 递归收集拖入的文件夹（保留相对路径，上传后目录结构一致） */
async function collectDroppedFiles(items) {
    const collected = [];
    const walk = (entry, basePath) => new Promise(resolve => {
        if (!entry) return resolve();
        if (entry.isFile) {
            entry.file(file => {
                file.relativePath = basePath ? basePath + '/' + file.name : file.name;
                collected.push(file);
                resolve();
            }, () => resolve());
            return;
        }
        if (entry.isDirectory) {
            const reader = entry.createReader();
            const dirPath = basePath ? basePath + '/' + entry.name : entry.name;
            const readBatch = () => reader.readEntries(async batch => {
                if (!batch || batch.length === 0) return resolve();
                for (const child of batch) {
                    await walk(child, dirPath);
                }
                readBatch();
            }, () => resolve());
            readBatch();
            return;
        }
        resolve();
    });
    for (let i = 0; i < items.length; i++) {
        const entry = items[i].webkitGetAsEntry ? items[i].webkitGetAsEntry() : null;
        await walk(entry, '');
    }
    return collected;
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
    // 文件夹上传：只把“目录部分”传给服务端（文件名本身走 multipart filename）
    const relative = file.relativePath || file.webkitRelativePath || '';
    if (relative) {
        const slash = relative.lastIndexOf('/');
        const dirPart = slash > 0 ? relative.slice(0, slash) : '';
        if (dirPart) formData.append('relativePath', dirPart);
    }

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/files/upload?path=${encodeURIComponent(currentDir || '')}&token=${encodeURIComponent(token)}`);

    const startedAt = Date.now();
    xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        const percent = Math.round((e.loaded / e.total) * 100);
        fill.style.width = percent + '%';
        const seconds = (Date.now() - startedAt) / 1000;
        const speed = seconds > 0.4 ? e.loaded / seconds : 0;
        if (speed > 0) {
            const remain = Math.max(0, (e.total - e.loaded) / speed);
            percentText.innerText = `${percent}% · ${formatBytes(speed)}/s · ${t('eta')} ${Math.ceil(remain)}s`;
        } else {
            percentText.innerText = percent + '%';
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
