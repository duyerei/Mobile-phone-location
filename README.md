# 监所人员定位管理系统 - MVP

## 项目结构

```
prison-guard-mvp/
├── server/                  # Python FastAPI 后端
│   ├── main.py              # 主服务（API + WebSocket）
│   └── requirements.txt
├── web/                     # 管理端 Web
│   ├── index.html           # 主页面
│   └── static/
│       ├── style.css
│       └── app.js
└── android/                 # 设备端 Android App
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/prisonguard/device/
        │   ├── Config.kt            # 服务器地址配置
        │   ├── MainActivity.kt      # 权限申请 + 启动服务
        │   ├── service/
        │   │   ├── GuardService.kt  # 核心前台服务（定位+录音）
        │   │   └── WebSocketClient.kt
        │   ├── network/
        │   │   └── ApiClient.kt     # HTTP 上报
        │   └── receiver/
        │       └── BootReceiver.kt  # 开机自启
        └── res/layout/activity_main.xml
```

## 快速启动

### 1. 启动后端

```bash
cd server
pip install -r requirements.txt
# 生产环境：设置 SECRET_KEY 环境变量（必须）
# Linux/macOS: export SECRET_KEY=$(openssl rand -hex 32)
# Windows:     set SECRET_KEY=your-secret-key-here
python main.py
# 服务运行在 http://0.0.0.0:8000
```

### 2. 打开管理端

浏览器访问 `http://<服务器IP>:8000`

### 3. 配置并安装 Android App

1. 用 Android Studio 打开 `android/` 目录
2. 修改 `Config.kt` 中的 `SERVER_URL` 为服务器局域网 IP
   ```kotlin
   const val SERVER_URL = "http://192.168.x.x:8000"
   ```
3. 编译并安装到测试手机
4. 首次启动：授予定位（始终允许）、录音、通知权限
5. 在电池优化设置中将 App 设为「不限制」

## 功能说明

### 设备端（Android）
- 每 10 秒上报一次 GPS 定位
- 开机自动启动，服务被杀后自动重启（START_STICKY）
- 通过 WebSocket 接收管理端指令
- 收到录音指令后录制 60 秒音频并上传

### 管理端（Web）
- 实时地图显示所有设备位置（WebSocket 推送）
- 点击设备查看详情、历史轨迹
- 在地图上点击设置电子围栏，越界自动告警
- 向设备发送录音指令，录音完成后在线播放

### 后端（FastAPI）
- REST API：设备注册、定位上报、围栏管理、告警、音频
- WebSocket：管理端实时推送 + 设备指令下发
- SQLite 存储（MVP 阶段，生产环境换 PostgreSQL）

## 注意事项

- Android 系统强制要求前台服务必须显示通知（无法绕过），通知已设为最低优先级、最简文字
- 测试时确保手机和服务器在同一局域网
- 生产部署时需将 `usesCleartextTraffic` 改为 HTTPS
