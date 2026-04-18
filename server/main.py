from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, HTTPException, Depends, status
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse, FileResponse, JSONResponse
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel
from typing import Optional, List
import aiosqlite
import json
import os
import secrets as _secrets
import time
import asyncio
from datetime import datetime, timedelta
from jose import JWTError, jwt
from passlib.context import CryptContext

app = FastAPI(title="监所人员定位管理系统 MVP")

# ─── JWT / 密码配置 ───────────────────────────────────────────────────────────
# 从环境变量读取，未设置时使用随机生成的密钥（每次重启会使已有 token 失效）
SECRET_KEY = os.environ.get("SECRET_KEY") or _secrets.token_hex(32)
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_HOURS = 8  # 8小时有效期

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
bearer_scheme = HTTPBearer(auto_error=False)

def hash_password(password: str) -> str:
    return pwd_context.hash(password)

def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)

def create_token(username: str) -> str:
    expire = datetime.utcnow() + timedelta(hours=ACCESS_TOKEN_EXPIRE_HOURS)
    return jwt.encode({"sub": username, "exp": expire}, SECRET_KEY, algorithm=ALGORITHM)

async def get_current_user(credentials: HTTPAuthorizationCredentials = Depends(bearer_scheme)):
    if not credentials:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录")
    try:
        payload = jwt.decode(credentials.credentials, SECRET_KEY, algorithms=[ALGORITHM])
        username = payload.get("sub")
        if not username:
            raise HTTPException(status_code=401, detail="Token无效")
        return username
    except JWTError:
        raise HTTPException(status_code=401, detail="Token已过期或无效")

DB_PATH = "prison.db"
AUDIO_DIR = "audio_clips"
os.makedirs(AUDIO_DIR, exist_ok=True)
PHOTO_DIR = "photos"
os.makedirs(PHOTO_DIR, exist_ok=True)

# ─── WebSocket 连接管理 ───────────────────────────────────────────────────────

class ConnectionManager:
    def __init__(self):
        self.web_clients: List[WebSocket] = []       # 管理端浏览器
        self.device_clients: dict[str, WebSocket] = {}  # 设备端 key=device_id

    async def connect_web(self, ws: WebSocket):
        await ws.accept()
        self.web_clients.append(ws)

    async def connect_device(self, ws: WebSocket, device_id: str):
        await ws.accept()
        self.device_clients[device_id] = ws

    def disconnect_web(self, ws: WebSocket):
        if ws in self.web_clients:
            self.web_clients.remove(ws)

    def disconnect_device(self, device_id: str):
        self.device_clients.pop(device_id, None)

    async def broadcast_to_web(self, message: dict):
        """向所有管理端推送消息"""
        dead = []
        for ws in self.web_clients:
            try:
                await ws.send_json(message)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.web_clients.remove(ws)

    async def send_to_device(self, device_id: str, message: dict) -> bool:
        """向指定设备发送指令"""
        ws = self.device_clients.get(device_id)
        if ws:
            try:
                await ws.send_json(message)
                return True
            except Exception:
                self.device_clients.pop(device_id, None)
        return False

manager = ConnectionManager()

# ─── 数据库初始化 ─────────────────────────────────────────────────────────────

async def init_db():
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("""
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                prisoner_name TEXT,
                report_interval INTEGER DEFAULT 300,
                created_at INTEGER DEFAULT (strftime('%s','now'))
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                accuracy REAL,
                speed REAL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(device_id) REFERENCES devices(id)
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS geofences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                radius REAL NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s','now'))
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                type TEXT NOT NULL,
                message TEXT,
                timestamp INTEGER NOT NULL,
                is_read INTEGER DEFAULT 0
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS audio_clips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                duration INTEGER,
                timestamp INTEGER NOT NULL
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                role TEXT DEFAULT 'admin',
                created_at INTEGER DEFAULT (strftime('%s','now'))
            )
        """)
        await db.commit()

@app.on_event("startup")
async def startup():
    await init_db()
    # 迁移：为旧表补充 report_interval 列
    async with aiosqlite.connect(DB_PATH) as db:
        try:
            await db.execute("ALTER TABLE devices ADD COLUMN report_interval INTEGER DEFAULT 300")
            await db.commit()
        except Exception:
            pass  # 列已存在，忽略
    # 初始化默认管理员账号（首次启动）
    async with aiosqlite.connect(DB_PATH) as db:
        async with db.execute("SELECT id FROM users WHERE username='admin'") as cur:
            exists = await cur.fetchone()
        if not exists:
            await db.execute(
                "INSERT INTO users (username, password_hash, role) VALUES (?,?,?)",
                ("admin", hash_password("admin123"), "admin")
            )
            await db.commit()
            print("✅ 默认管理员账号已创建: admin / admin123")

# ─── Pydantic 模型 ────────────────────────────────────────────────────────────

class DeviceRegister(BaseModel):
    device_id: str
    name: str
    prisoner_name: Optional[str] = None

class LocationReport(BaseModel):
    device_id: str
    lat: float
    lng: float
    accuracy: Optional[float] = None
    speed: Optional[float] = None
    timestamp: Optional[int] = None

class GeofenceCreate(BaseModel):
    name: str
    lat: float
    lng: float
    radius: float  # 单位：米

# ─── 认证 API ─────────────────────────────────────────────────────────────────

class LoginRequest(BaseModel):
    username: str
    password: str

class UserCreate(BaseModel):
    username: str
    password: str
    role: str = "admin"

@app.post("/api/auth/login")
async def login(data: LoginRequest):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT * FROM users WHERE username=?", (data.username,)) as cur:
            user = await cur.fetchone()
    if not user or not verify_password(data.password, user["password_hash"]):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token = create_token(user["username"])
    return {"token": token, "username": user["username"], "role": user["role"]}

@app.get("/api/auth/me")
async def get_me(username: str = Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT id, username, role, created_at FROM users WHERE username=?", (username,)) as cur:
            user = await cur.fetchone()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    return dict(user)

@app.get("/api/users")
async def list_users(username: str = Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT id, username, role, created_at FROM users") as cur:
            rows = await cur.fetchall()
    return [dict(r) for r in rows]

@app.post("/api/users")
async def create_user(data: UserCreate, username: str = Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        try:
            await db.execute(
                "INSERT INTO users (username, password_hash, role) VALUES (?,?,?)",
                (data.username, hash_password(data.password), data.role)
            )
            await db.commit()
        except Exception:
            raise HTTPException(status_code=400, detail="用户名已存在")
    return {"status": "ok"}

@app.delete("/api/users/{user_id}")
async def delete_user(user_id: int, username: str = Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        async with db.execute("SELECT username FROM users WHERE id=?", (user_id,)) as cur:
            user = await cur.fetchone()
        if user and user[0] == "admin":
            raise HTTPException(status_code=400, detail="不能删除默认管理员")
        await db.execute("DELETE FROM users WHERE id=?", (user_id,))
        await db.commit()
    return {"status": "ok"}

@app.post("/api/auth/change_password")
async def change_password(data: dict, username: str = Depends(get_current_user)):
    old_pwd = data.get("old_password", "")
    new_pwd = data.get("new_password", "")
    if len(new_pwd) < 6:
        raise HTTPException(status_code=400, detail="新密码至少6位")
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT password_hash FROM users WHERE username=?", (username,)) as cur:
            user = await cur.fetchone()
        if not user or not verify_password(old_pwd, user["password_hash"]):
            raise HTTPException(status_code=401, detail="原密码错误")
        await db.execute("UPDATE users SET password_hash=? WHERE username=?",
                         (hash_password(new_pwd), username))
        await db.commit()
    return {"status": "ok"}

# ─── 设备管理 API ─────────────────────────────────────────────────────────────

@app.post("/api/devices/register")
async def register_device(data: DeviceRegister):
    # 设备注册不需要认证（设备端调用）
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT OR REPLACE INTO devices (id, name, prisoner_name) VALUES (?, ?, ?)",
            (data.device_id, data.name, data.prisoner_name)
        )
        await db.commit()
    return {"status": "ok", "device_id": data.device_id}

@app.delete("/api/devices/{device_id}")
async def delete_device(device_id: str, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("DELETE FROM locations WHERE device_id=?", (device_id,))
        await db.execute("DELETE FROM alerts WHERE device_id=?", (device_id,))
        await db.execute("DELETE FROM audio_clips WHERE device_id=?", (device_id,))
        await db.execute("DELETE FROM devices WHERE id=?", (device_id,))
        await db.commit()
    return {"status": "ok"}

@app.get("/api/devices")
async def list_devices(_=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("""
            SELECT d.id, d.name, d.prisoner_name, d.report_interval,
                   l.lat, l.lng, l.accuracy, l.speed, l.timestamp as last_seen
            FROM devices d
            LEFT JOIN locations l ON l.id = (
                SELECT id FROM locations WHERE device_id = d.id ORDER BY timestamp DESC LIMIT 1
            )
        """) as cursor:
            rows = await cursor.fetchall()
    return [dict(r) for r in rows]

# ─── 定位 API ─────────────────────────────────────────────────────────────────

@app.post("/api/location")
async def report_location(data: LocationReport):
    ts = data.timestamp or int(time.time())
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO locations (device_id, lat, lng, accuracy, speed, timestamp) VALUES (?,?,?,?,?,?)",
            (data.device_id, data.lat, data.lng, data.accuracy, data.speed, ts)
        )
        await db.commit()

    # 检查围栏
    await check_geofences(data.device_id, data.lat, data.lng)

    # 推送到管理端
    await manager.broadcast_to_web({
        "type": "location_update",
        "device_id": data.device_id,
        "lat": data.lat,
        "lng": data.lng,
        "accuracy": data.accuracy,
        "speed": data.speed,
        "timestamp": ts
    })
    return {"status": "ok"}

@app.get("/api/location/history/{device_id}")
async def location_history(device_id: str, limit: int = 200, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT lat, lng, accuracy, speed, timestamp FROM locations WHERE device_id=? ORDER BY timestamp DESC LIMIT ?",
            (device_id, limit)
        ) as cursor:
            rows = await cursor.fetchall()
    return [dict(r) for r in rows]

# ─── 围栏 API ─────────────────────────────────────────────────────────────────

@app.post("/api/geofences")
async def create_geofence(data: GeofenceCreate, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        cursor = await db.execute(
            "INSERT INTO geofences (name, lat, lng, radius) VALUES (?,?,?,?)",
            (data.name, data.lat, data.lng, data.radius)
        )
        await db.commit()
        fence_id = cursor.lastrowid
    return {"status": "ok", "id": fence_id}

@app.get("/api/geofences")
async def list_geofences(_=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT * FROM geofences") as cursor:
            rows = await cursor.fetchall()
    return [dict(r) for r in rows]

@app.delete("/api/geofences/{fence_id}")
async def delete_geofence(fence_id: int, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("DELETE FROM geofences WHERE id=?", (fence_id,))
        await db.commit()
    return {"status": "ok"}

async def check_geofences(device_id: str, lat: float, lng: float):
    """检查是否越出围栏，触发告警"""
    import math
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute("SELECT * FROM geofences") as cursor:
            fences = await cursor.fetchall()

    for fence in fences:
        # Haversine 距离计算
        R = 6371000
        phi1 = math.radians(lat)
        phi2 = math.radians(fence["lat"])
        dphi = math.radians(fence["lat"] - lat)
        dlambda = math.radians(fence["lng"] - lng)
        a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlambda/2)**2
        distance = R * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))

        if distance > fence["radius"]:
            msg = f"设备 {device_id} 越出围栏【{fence['name']}】，距离 {distance:.0f}m"
            ts = int(time.time())
            async with aiosqlite.connect(DB_PATH) as db:
                await db.execute(
                    "INSERT INTO alerts (device_id, type, message, timestamp) VALUES (?,?,?,?)",
                    (device_id, "geofence_breach", msg, ts)
                )
                await db.commit()
            await manager.broadcast_to_web({
                "type": "alert",
                "alert_type": "geofence_breach",
                "device_id": device_id,
                "message": msg,
                "timestamp": ts
            })

# ─── 告警 API ─────────────────────────────────────────────────────────────────

@app.get("/api/alerts")
async def list_alerts(limit: int = 50, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT * FROM alerts ORDER BY timestamp DESC LIMIT ?", (limit,)
        ) as cursor:
            rows = await cursor.fetchall()
    return [dict(r) for r in rows]

@app.post("/api/alerts/{alert_id}/read")
async def mark_alert_read(alert_id: int, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("UPDATE alerts SET is_read=1 WHERE id=?", (alert_id,))
        await db.commit()
    return {"status": "ok"}

# ─── 音频 API ─────────────────────────────────────────────────────────────────

@app.post("/api/audio/command/{device_id}")
async def send_audio_command(device_id: str, _=Depends(get_current_user)):
    """向设备发送录音指令"""
    sent = await manager.send_to_device(device_id, {"cmd": "start_audio", "duration": 60})
    if not sent:
        raise HTTPException(status_code=404, detail="设备不在线")
    return {"status": "ok", "message": "录音指令已发送"}

@app.post("/api/devices/{device_id}/report_now")
async def request_report_now(device_id: str, _=Depends(get_current_user)):
    """要求设备立即上报一次位置"""
    sent = await manager.send_to_device(device_id, {"cmd": "report_now"})
    if not sent:
        raise HTTPException(status_code=404, detail="设备不在线（WebSocket未连接）")
    return {"status": "ok", "message": "已发送立即上报指令"}

@app.post("/api/devices/{device_id}/set_interval")
async def set_report_interval(device_id: str, seconds: int = 300, _=Depends(get_current_user)):
    """设置设备定位上报间隔（秒），最小30秒"""
    seconds = max(30, seconds)
    # 持久化到数据库
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "UPDATE devices SET report_interval=? WHERE id=?",
            (seconds, device_id)
        )
        await db.commit()
    # 如果设备在线，实时下发
    await manager.send_to_device(device_id, {"cmd": "set_interval", "seconds": seconds})
    return {"status": "ok", "seconds": seconds}

# ─── 照片 API ─────────────────────────────────────────────────────────────────

@app.post("/api/photo/command/{device_id}")
async def send_photo_command(device_id: str, facing: str = "back", _=Depends(get_current_user)):
    """向设备发送拍照指令，facing: back=后置, front=前置"""
    if facing not in ("back", "front"):
        facing = "back"
    sent = await manager.send_to_device(device_id, {"cmd": "take_photo", "facing": facing})
    if not sent:
        raise HTTPException(status_code=404, detail="设备不在线")
    return {"status": "ok", "message": f"拍照指令已发送（{'后置' if facing=='back' else '前置'}摄像头）"}

@app.post("/api/photo/upload/{device_id}")
async def upload_photo(device_id: str, file: UploadFile = File(...)):
    """设备上传照片"""
    ts = int(time.time())
    filename = f"{device_id}_{ts}.jpg"
    filepath = os.path.join(PHOTO_DIR, filename)
    content = await file.read()
    with open(filepath, "wb") as f:
        f.write(content)
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO photos (device_id, file_path, timestamp) VALUES (?,?,?)",
            (device_id, filepath, ts)
        )
        await db.commit()
    await manager.broadcast_to_web({
        "type": "photo_ready",
        "device_id": device_id,
        "filename": filename,
        "timestamp": ts
    })
    return {"status": "ok", "filename": filename}

@app.get("/api/photo/list/{device_id}")
async def list_photos(device_id: str, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT * FROM photos WHERE device_id=? ORDER BY timestamp DESC LIMIT 20",
            (device_id,)
        ) as cursor:
            rows = await cursor.fetchall()
    return [dict(r) for r in rows]

@app.get("/api/photo/file/{filename}")
async def get_photo_file(filename: str, _=Depends(get_current_user)):
    filepath = os.path.join(PHOTO_DIR, os.path.basename(filename))
    if not os.path.exists(filepath):
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(filepath, media_type="image/jpeg")

@app.post("/api/audio/upload/{device_id}")
async def upload_audio(device_id: str, file: UploadFile = File(...)):
    """设备上传录音文件"""
    ts = int(time.time())
    filename = f"{device_id}_{ts}.aac"
    filepath = os.path.join(AUDIO_DIR, filename)
    content = await file.read()
    with open(filepath, "wb") as f:
        f.write(content)
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO audio_clips (device_id, file_path, timestamp) VALUES (?,?,?)",
            (device_id, filepath, ts)
        )
        await db.commit()
    await manager.broadcast_to_web({
        "type": "audio_ready",
        "device_id": device_id,
        "filename": filename,
        "timestamp": ts
    })
    return {"status": "ok", "filename": filename}

@app.get("/api/audio/clips/{device_id}")
async def list_audio_clips(device_id: str, _=Depends(get_current_user)):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT * FROM audio_clips WHERE device_id=? ORDER BY timestamp DESC LIMIT 20",
            (device_id,)
        ) as cursor:
            rows = await cursor.fetchall()
    return [dict(r) for r in rows]

@app.get("/api/audio/file/{filename}")
async def get_audio_file(filename: str, _=Depends(get_current_user)):
    filepath = os.path.join(AUDIO_DIR, filename)
    if not os.path.exists(filepath):
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(filepath, media_type="audio/aac")

# ─── WebSocket ────────────────────────────────────────────────────────────────

@app.websocket("/ws/web")
async def websocket_web(ws: WebSocket):
    """管理端 WebSocket 连接"""
    await manager.connect_web(ws)
    try:
        while True:
            await ws.receive_text()  # 保持连接
    except WebSocketDisconnect:
        manager.disconnect_web(ws)

@app.websocket("/ws/device/{device_id}")
async def websocket_device(ws: WebSocket, device_id: str):
    """设备端 WebSocket 连接，用于接收指令"""
    await manager.connect_device(ws, device_id)
    try:
        while True:
            await ws.receive_text()  # 保持连接
    except WebSocketDisconnect:
        manager.disconnect_device(device_id)

# ─── 静态文件（管理端 Web）────────────────────────────────────────────────────

app.mount("/static", StaticFiles(directory="../web/static"), name="static")

@app.get("/login", response_class=HTMLResponse)
async def login_page():
    with open("../web/login.html", encoding="utf-8") as f:
        return f.read()

@app.get("/", response_class=HTMLResponse)
async def index():
    with open("../web/index.html", encoding="utf-8") as f:
        return f.read()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
