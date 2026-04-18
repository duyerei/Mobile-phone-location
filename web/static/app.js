// ─── 全局状态 ─────────────────────────────────────────────────────────────────
const API = '';
let map, ws;
let devices = {};
let fences = {};
let trackLayer = null;
let selectedDeviceId = null;
let drawingFence = false;
let unreadAlerts = 0;

// ─── Token 认证 ───────────────────────────────────────────────────────────────
function getToken() { return localStorage.getItem('token') || ''; }

function authHeaders() {
  return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getToken() };
}

// 保存原生 fetch 引用，避免被替换
const _nativeFetch = window.fetch.bind(window);

// 统一 fetch，自动带 token，401 时跳转登录
async function apiFetch(url, options = {}) {
  options.headers = { ...(options.headers || {}), 'Authorization': 'Bearer ' + getToken() };
  const res = await _nativeFetch(url, options);
  if (res.status === 401) {
    localStorage.removeItem('token');
    location.href = '/login';
    throw new Error('未授权');
  }
  return res;
}

function logout() {
  localStorage.removeItem('token');
  localStorage.removeItem('username');
  location.href = '/login';
}

// ─── 地图初始化 ───────────────────────────────────────────────────────────────
function initMap() {
  map = L.map('map', { zoomControl: true }).setView([39.9042, 116.4074], 15);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap',
    maxZoom: 19
  }).addTo(map);

  map.on('click', onMapClick);
}

function onMapClick(e) {
  if (!drawingFence) return;
  const name = document.getElementById('fence-name').value.trim();
  const radius = parseFloat(document.getElementById('fence-radius').value) || 100;
  if (!name) { alert('请先填写围栏名称'); return; }
  createFence(name, e.latlng.lat, e.latlng.lng, radius);
  drawingFence = false;
  map.getContainer().style.cursor = '';
}

// ─── WebSocket ────────────────────────────────────────────────────────────────
function connectWS() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws/web`);

  ws.onopen = () => {
    document.getElementById('ws-status').textContent = '● 已连接';
    document.getElementById('ws-status').className = 'badge badge-online';
  };

  ws.onclose = () => {
    document.getElementById('ws-status').textContent = '● 未连接';
    document.getElementById('ws-status').className = 'badge badge-offline';
    setTimeout(connectWS, 3000);
  };

  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'location_update') handleLocationUpdate(msg);
    else if (msg.type === 'alert') handleAlert(msg);
    else if (msg.type === 'audio_ready') handleAudioReady(msg);
    else if (msg.type === 'photo_ready') handlePhotoReady(msg);
  };
}

// ─── 定位更新 ─────────────────────────────────────────────────────────────────
function handleLocationUpdate(msg) {
  const latlng = [msg.lat, msg.lng];
  if (devices[msg.device_id]) {
    devices[msg.device_id].marker.setLatLng(latlng);
    devices[msg.device_id].data = { ...devices[msg.device_id].data, ...msg };
  } else {
    const icon = L.divIcon({
      className: '',
      html: `<div class="device-marker"></div>`,
      iconSize: [14, 14], iconAnchor: [7, 7]
    });
    const marker = L.marker(latlng, { icon }).addTo(map);
    marker.on('click', () => selectDevice(msg.device_id));
    devices[msg.device_id] = { marker, data: msg };
  }
  // 更新设备列表中的最后位置
  updateDeviceListItem(msg.device_id, msg);
  // 如果当前选中该设备，更新详情
  if (selectedDeviceId === msg.device_id) updateDetailInfo();
}

// ─── 告警处理 ─────────────────────────────────────────────────────────────────
function handleAlert(msg) {
  unreadAlerts++;
  const badge = document.getElementById('alert-badge');
  badge.textContent = `${unreadAlerts} 条告警`;
  badge.classList.remove('hidden');
  prependAlertItem(msg);
}

function prependAlertItem(alert) {
  const list = document.getElementById('alert-list');
  const div = document.createElement('div');
  div.className = 'alert-item';
  div.dataset.id = alert.id || '';
  div.innerHTML = `
    <div class="a-msg">${alert.message}</div>
    <div class="a-time">${formatTime(alert.timestamp)}</div>
  `;
  div.onclick = () => {
    div.classList.add('read');
    if (alert.id) apiFetch(`/api/alerts/${alert.id}/read`, { method: 'POST' });
    unreadAlerts = Math.max(0, unreadAlerts - 1);
    const badge = document.getElementById('alert-badge');
    if (unreadAlerts === 0) badge.classList.add('hidden');
    else badge.textContent = `${unreadAlerts} 条告警`;
  };
  list.prepend(div);
}

// ─── 音频就绪 ─────────────────────────────────────────────────────────────────
function handleAudioReady(msg) {
  if (selectedDeviceId === msg.device_id) loadAudioClips();
}

// ─── 照片就绪 ─────────────────────────────────────────────────────────────────
function handlePhotoReady(msg) {
  if (selectedDeviceId === msg.device_id) loadPhotos();
  // 无论是否选中，都弹出通知
  const toast = document.createElement('div');
  toast.className = 'photo-toast';
  toast.innerHTML = `📷 设备 ${msg.device_id.substring(0,8)}... 照片已上传`;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

// ─── 设备列表 ─────────────────────────────────────────────────────────────────
const ONLINE_THRESHOLD_SEC = 360; // 6分钟（配合默认5分钟上报间隔，留1分钟余量）

function isDeviceOnline(deviceId, lastSeen, reportInterval) {
  // 优先看内存里是否有实时位置（WebSocket推送过来的）
  if (devices[deviceId] && devices[deviceId].data._fromWs) return true;
  if (!lastSeen) return false;
  const now = Math.floor(Date.now() / 1000);
  // 阈值 = 上报间隔 + 60秒余量
  const threshold = (reportInterval || 300) + 60;
  return (now - lastSeen) <= threshold;
}

async function loadDevices() {
  const res = await apiFetch('/api/devices');
  const list = await res.json();
  const now = Math.floor(Date.now() / 1000);
  const container = document.getElementById('device-list');
  container.innerHTML = '';
  list.forEach(d => {
    // 先把历史位置放到地图上（填充 devices 对象）
    if (d.lat && d.lng && !devices[d.id]) {
      handleLocationUpdate({ device_id: d.id, lat: d.lat, lng: d.lng, accuracy: d.accuracy, speed: d.speed, timestamp: d.last_seen, report_interval: d.report_interval, name: d.name, prisoner_name: d.prisoner_name });
    } else if (devices[d.id]) {
      devices[d.id].data = { ...devices[d.id].data, report_interval: d.report_interval, name: d.name, prisoner_name: d.prisoner_name, last_seen: d.last_seen };
    }
    // 在线状态：上报间隔+60秒余量内有上报则视为在线
    const online = isDeviceOnline(d.id, d.last_seen, d.report_interval);
    const div = document.createElement('div');
    div.className = 'device-item' + (selectedDeviceId === d.id ? ' active' : '');
    div.id = `dev-${d.id}`;
    div.innerHTML = `
      <div class="d-status ${online ? 'status-online' : 'status-offline'}"></div>
      <div class="d-name">${d.prisoner_name || d.name}</div>
      <div class="d-meta">ID: ${d.id.substring(0,8)}... ${d.last_seen ? '· ' + formatTime(d.last_seen) : '· 从未上报'}</div>
      <button class="btn-del" title="删除设备" onclick="event.stopPropagation();deleteDevice('${d.id}','${d.prisoner_name || d.name}')">×</button>
    `;
    div.onclick = () => selectDevice(d.id);
    container.appendChild(div);
  });
}

function updateDeviceListItem(deviceId, data) {
  const el = document.getElementById(`dev-${deviceId}`);
  if (!el) { loadDevices(); return; }
  // 实时收到位置更新，直接把绿点和时间更新掉，不用等30秒轮询
  el.querySelector('.d-status').className = 'd-status status-online';
  el.querySelector('.d-meta').textContent = `ID: ${deviceId.substring(0,8)}... · ${formatTime(data.timestamp)}`;
}

async function deleteDevice(deviceId, name) {
  if (!confirm(`确认删除设备「${name}」？\n相关定位记录和告警也会一并删除。`)) return;
  await apiFetch(`/api/devices/${deviceId}`, { method: 'DELETE' });
  // 清除地图上的 marker
  if (devices[deviceId]) {
    map.removeLayer(devices[deviceId].marker);
    delete devices[deviceId];
  }
  if (selectedDeviceId === deviceId) closeDetail();
  loadDevices();
}

// ─── 刷新位置 & 间隔设置 ──────────────────────────────────────────────────────
async function refreshDeviceLocation() {
  if (!selectedDeviceId) return;
  const btn = event.target;
  btn.textContent = '刷新中...';
  btn.disabled = true;
  try {
    // 先让设备立即上报一次（需要 WebSocket 在线）
    const cmdRes = await apiFetch(`/api/devices/${selectedDeviceId}/report_now`, { method: 'POST' });
    if (cmdRes.ok) {
      // 等2秒让设备上报完成，再拉取最新数据
      await new Promise(r => setTimeout(r, 2000));
    }
    // 不管设备是否在线，都重新拉取数据库里的最新位置
    await loadDevices();
    // 更新详情面板
    const d = devices[selectedDeviceId];
    if (d) {
      updateDetailInfo();
      map.setView(d.marker.getLatLng(), map.getZoom());
    }
  } finally {
    btn.textContent = '🔄 刷新位置';
    btn.disabled = false;
  }
}

async function setDeviceInterval() {
  if (!selectedDeviceId) return;
  const seconds = parseInt(document.getElementById('interval-select').value);
  const res = await apiFetch(`/api/devices/${selectedDeviceId}/set_interval?seconds=${seconds}`, { method: 'POST' });
  const data = await res.json();
  const status = document.getElementById('interval-status');
  if (res.ok) {
    status.textContent = `✓ 已设置为每 ${formatInterval(seconds)} 上报一次`;
    status.style.color = '#4caf50';
  } else {
    status.textContent = '✗ 设置失败：' + (data.detail || '未知错误');
    status.style.color = '#f44336';
  }
  setTimeout(() => status.textContent = '', 4000);
}

function formatInterval(seconds) {
  if (seconds < 60) return `${seconds}秒`;
  if (seconds < 3600) return `${seconds/60}分钟`;
  return `${seconds/3600}小时`;
}

// ─── 设备详情 ─────────────────────────────────────────────────────────────────
function selectDevice(deviceId) {
  selectedDeviceId = deviceId;
  document.querySelectorAll('.device-item').forEach(el => el.classList.remove('active'));
  const el = document.getElementById(`dev-${deviceId}`);
  if (el) el.classList.add('active');

  document.getElementById('detail-panel').style.display = 'block';
  updateDetailInfo();
  loadAudioClips();
  loadPhotos();

  // 地图定位到该设备
  const d = devices[deviceId];
  if (d) map.setView(d.marker.getLatLng(), 17);
}

function updateDetailInfo() {
  const d = devices[selectedDeviceId];
  if (!d) return;
  // 从设备列表数据里取 report_interval
  const intervalSec = d.data.report_interval || 300;
  // 同步下拉框
  const sel = document.getElementById('interval-select');
  if (sel) {
    const opt = [...sel.options].find(o => parseInt(o.value) === intervalSec);
    if (opt) sel.value = String(intervalSec);
  }
  document.getElementById('detail-name').textContent = (d.data.prisoner_name || d.data.name || selectedDeviceId.substring(0, 12) + '...');
  document.getElementById('detail-info').innerHTML = `
    <div class="detail-row"><span class="label">纬度</span><span class="value">${d.data.lat?.toFixed(6) ?? '-'}</span></div>
    <div class="detail-row"><span class="label">经度</span><span class="value">${d.data.lng?.toFixed(6) ?? '-'}</span></div>
    <div class="detail-row"><span class="label">精度</span><span class="value">${d.data.accuracy ? d.data.accuracy.toFixed(1) + 'm' : '-'}</span></div>
    <div class="detail-row"><span class="label">速度</span><span class="value">${d.data.speed ? (d.data.speed * 3.6).toFixed(1) + 'km/h' : '-'}</span></div>
    <div class="detail-row"><span class="label">上报间隔</span><span class="value">${formatInterval(intervalSec)}</span></div>
    <div class="detail-row"><span class="label">最后上报</span><span class="value">${formatTime(d.data.timestamp)}</span></div>
  `;
}

function closeDetail() {
  document.getElementById('detail-panel').style.display = 'none';
  selectedDeviceId = null;
  if (trackLayer) { map.removeLayer(trackLayer); trackLayer = null; }
}

// ─── 轨迹回放 ─────────────────────────────────────────────────────────────────
async function loadTrack() {
  if (!selectedDeviceId) return;
  const res = await apiFetch(`/api/location/history/${selectedDeviceId}?limit=200`);
  const points = await res.json();
  if (trackLayer) map.removeLayer(trackLayer);
  if (!points.length) { alert('暂无轨迹数据'); return; }
  const latlngs = points.map(p => [p.lat, p.lng]);
  trackLayer = L.polyline(latlngs, { color: '#4a8fd8', weight: 3, opacity: 0.8 }).addTo(map);
  map.fitBounds(trackLayer.getBounds(), { padding: [30, 30] });
}

// ─── 围栏管理 ─────────────────────────────────────────────────────────────────
function startDrawFence() {
  drawingFence = true;
  map.getContainer().style.cursor = 'crosshair';
}

async function createFence(name, lat, lng, radius) {
  await apiFetch('/api/geofences', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, lat, lng, radius })
  });
  document.getElementById('fence-name').value = '';
  loadFences();
}

async function loadFences() {
  const res = await apiFetch('/api/geofences');
  const list = await res.json();
  // 清除旧圆圈
  Object.values(fences).forEach(f => map.removeLayer(f.circle));
  fences = {};
  const container = document.getElementById('fence-list');
  container.innerHTML = '';
  list.forEach(f => {
    const circle = L.circle([f.lat, f.lng], {
      radius: f.radius, color: '#f0a030', fillColor: '#f0a030', fillOpacity: 0.1, weight: 2
    }).addTo(map);
    fences[f.id] = { circle, data: f };
    const div = document.createElement('div');
    div.className = 'fence-item';
    div.innerHTML = `
      <div><div class="f-name">${f.name}</div><div class="f-meta">半径 ${f.radius}m</div></div>
      <button class="btn-sm" onclick="deleteFence(${f.id})">删除</button>
    `;
    container.appendChild(div);
  });
}

async function deleteFence(id) {
  await apiFetch(`/api/geofences/${id}`, { method: 'DELETE' });
  loadFences();
}

// ─── 音频监听 ─────────────────────────────────────────────────────────────────
async function sendAudioCmd() {
  if (!selectedDeviceId) return;
  const res = await apiFetch(`/api/audio/command/${selectedDeviceId}`, { method: 'POST' });
  const data = await res.json();
  if (res.ok) alert('录音指令已发送，请等待设备上传（约60秒）');
  else alert('发送失败：' + data.detail);
}

// ─── 拍照 ─────────────────────────────────────────────────────────────────────
async function sendPhotoCmd(facing = 'back') {
  if (!selectedDeviceId) return;
  // 切换按钮高亮
  document.getElementById('btn-back').classList.toggle('active', facing === 'back');
  document.getElementById('btn-front').classList.toggle('active', facing === 'front');
  const res = await apiFetch(`/api/photo/command/${selectedDeviceId}?facing=${facing}`, { method: 'POST' });
  const data = await res.json();
  if (!res.ok) alert('发送失败：' + data.detail);
}

async function loadPhotos() {
  if (!selectedDeviceId) return;
  const res = await apiFetch(`/api/photo/list/${selectedDeviceId}`);
  const photos = await res.json();
  const container = document.getElementById('photo-list');
  if (!container) return;
  container.innerHTML = '';
  if (!photos.length) {
    container.innerHTML = '<div class="no-data">暂无照片</div>';
    return;
  }
  photos.forEach(p => {
    const filename = p.file_path.replace(/\\/g, '/').split('/').pop();
    const div = document.createElement('div');
    div.className = 'photo-item';
    div.innerHTML = `
      <div class="p-time">${formatTime(p.timestamp)}</div>
      <img src="/api/photo/file/${filename}" class="photo-thumb" onclick="viewPhoto('/api/photo/file/${filename}')" />
    `;
    container.appendChild(div);
  });
}

function viewPhoto(url) {
  const overlay = document.createElement('div');
  overlay.className = 'photo-overlay';
  overlay.innerHTML = `<img src="${url}" /><div class="close-btn" onclick="this.parentElement.remove()">✕</div>`;
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  document.body.appendChild(overlay);
}

async function loadAudioClips() {
  if (!selectedDeviceId) return;
  const res = await apiFetch(`/api/audio/clips/${selectedDeviceId}`);
  const clips = await res.json();
  const container = document.getElementById('audio-list');
  container.innerHTML = '';
  clips.forEach(c => {
    const filename = c.file_path.split('/').pop().split('\\').pop();
    const div = document.createElement('div');
    div.className = 'audio-item';
    div.innerHTML = `
      <div class="a-time">${formatTime(c.timestamp)}</div>
      <audio controls src="/api/audio/file/${filename}"></audio>
    `;
    container.appendChild(div);
  });
}

// ─── 告警列表加载 ─────────────────────────────────────────────────────────────
async function loadAlerts() {
  const res = await apiFetch('/api/alerts?limit=30');
  const list = await res.json();
  const container = document.getElementById('alert-list');
  container.innerHTML = '';
  list.forEach(a => {
    const div = document.createElement('div');
    div.className = 'alert-item' + (a.is_read ? ' read' : '');
    div.dataset.id = a.id;
    div.innerHTML = `
      <div class="a-msg">${a.message}</div>
      <div class="a-time">${formatTime(a.timestamp)}</div>
    `;
    div.onclick = () => {
      div.classList.add('read');
      apiFetch(`/api/alerts/${a.id}/read`, { method: 'POST' });
    };
    container.appendChild(div);
  });
  unreadAlerts = list.filter(a => !a.is_read).length;
  const badge = document.getElementById('alert-badge');
  if (unreadAlerts > 0) {
    badge.textContent = `${unreadAlerts} 条告警`;
    badge.classList.remove('hidden');
  }
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────
function formatTime(ts) {
  if (!ts) return '-';
  const d = new Date(ts * 1000);
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

// ─── 初始化 ───────────────────────────────────────────────────────────────────
window.onload = async () => {
  // 未登录跳转
  if (!getToken()) { location.href = '/login'; return; }
  // 验证 token 有效性
  try {
    const me = await apiFetch('/api/auth/me');
    if (!me.ok) { location.href = '/login'; return; }
    const user = await me.json();
    document.getElementById('user-info').textContent = '👤 ' + user.username;
  } catch { location.href = '/login'; return; }

  initMap();
  connectWS();
  loadDevices();
  loadFences();
  loadAlerts();
  setInterval(loadDevices, 30000);
};

// ─── 用户管理 ─────────────────────────────────────────────────────────────────
function showUserMgr() {
  document.getElementById('user-modal').classList.remove('hidden');
  loadUserList();
}
function closeUserMgr() {
  document.getElementById('user-modal').classList.add('hidden');
}

async function loadUserList() {
  const res = await apiFetch('/api/users');
  const users = await res.json();
  const container = document.getElementById('user-list-container');
  container.innerHTML = '';
  users.forEach(u => {
    const div = document.createElement('div');
    div.className = 'user-item';
    div.innerHTML = `
      <span class="u-name">👤 ${u.username}</span>
      <span class="u-role">${u.role}</span>
      ${u.username !== 'admin' ? `<button class="btn-sm" onclick="deleteUser(${u.id})">删除</button>` : '<span class="u-default">默认</span>'}
    `;
    container.appendChild(div);
  });
}

async function addUser(e) {
  if (e) e.preventDefault();
  const username = document.getElementById('new-username').value.trim();
  const password = document.getElementById('new-password').value;
  const msg = document.getElementById('user-msg');
  if (!username || !password) { msg.style.color='#f44336'; msg.textContent='请填写用户名和密码'; return; }
  const res = await apiFetch('/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const data = await res.json();
  if (res.ok) {
    msg.style.color = '#4caf50'; msg.textContent = '✓ 用户已添加';
    document.getElementById('new-username').value = '';
    document.getElementById('new-password').value = '';
    loadUserList();
  } else {
    msg.style.color = '#f44336'; msg.textContent = '✗ ' + (data.detail || '添加失败');
  }
  setTimeout(() => msg.textContent = '', 3000);
}

async function deleteUser(id) {
  if (!confirm('确认删除该用户？')) return;
  const res = await apiFetch(`/api/users/${id}`, { method: 'DELETE' });
  const data = await res.json();
  if (res.ok) loadUserList();
  else alert(data.detail || '删除失败');
}

async function changePassword(e) {
  if (e) e.preventDefault();
  const oldPwd = document.getElementById('old-password').value;
  const newPwd = document.getElementById('new-pwd').value;
  const msg = document.getElementById('user-msg');
  const res = await apiFetch('/api/auth/change_password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ old_password: oldPwd, new_password: newPwd })
  });
  const data = await res.json();
  if (res.ok) {
    msg.style.color = '#4caf50'; msg.textContent = '✓ 密码已修改，请重新登录';
    setTimeout(() => logout(), 1500);
  } else {
    msg.style.color = '#f44336'; msg.textContent = '✗ ' + (data.detail || '修改失败');
  }
  setTimeout(() => msg.textContent = '', 3000);
}
