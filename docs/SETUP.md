# 部署与凭据获取

## 一、环境要求

**开发机**

| 项 | 要求 | 已验证版本 |
|---|---|---|
| JDK | 17+ | Temurin 21.0.12 |
| Android SDK | platform 36 + build-tools 36 | compileSdk 36 |
| Gradle | 8.11+ | 9.3.1 |
| AGP | 8.13.x | 8.13.1 |
| Kotlin | 2.2.x | 2.2.21 |
| Python | 3.10+ | 3.13.12 |

**手机**

- Android 8.0（API 26）以上，实测 Android 16
- 需要能持续供电或用得动一天（约 2.5%/小时）

**账号**

- 阿里云百炼（有免费额度）
- 百度网盘（免费账号即可，容量要够：约 350MB/天）

---

## 二、阿里云 API Key

1. 打开 [百炼控制台](https://bailian.console.aliyun.com/)
2. 开通「模型服务」，确认 `qwen3-asr-flash` 和 `qwen-plus` 可用
3. 右上角个人中心 → API-KEY → 创建
4. 得到形如 `sk-xxxxxxxx` 的字符串

**费用参考**：qwen3-asr-flash 按音频时长计费，
一天 4 小时有效人声在个位数人民币。qwen-plus 生成概要的成本可忽略。

**同一个 key 同时用于转写和摘要**，不需要申请两个。

---

## 三、百度网盘 Cookie

这是最麻烦的一步。不需要申请 appKey，只要登录后的 cookie。

### 3.1 抓取

1. Chrome 登录 [pan.baidu.com](https://pan.baidu.com)
2. F12 打开开发者工具 → **Application** → **Cookies** → `https://pan.baidu.com`
3. 复制两个值：
   - `BDUSS` —— 很长一串，通常以 `9i` 开头
   - `STOKEN` —— 64 位十六进制
4. 再拿 `bdstoken`：在 Console 里执行

   ```js
   // 页面加载后，bdstoken 在全局变量里
   window.yunData ? window.yunData.MYBDSTOKEN : '没找到，试试刷新页面'
   ```

   或者看任意一个 `/api/list` 请求的 query 参数里的 `bdstoken`。

5. 存成 JSON：

   ```json
   {
     "logged_in": true,
     "bdstoken": "复制到的值",
     "cookies": {
       "BDUSS": "复制到的值",
       "STOKEN": "复制到的值"
     }
   }
   ```

   保存为 `baidu_cookies.json`，**放在仓库目录之外**（比如 `~/secrets/`）。

### 3.2 有效期

- `BDUSS` 通常几个月有效，改密码会立即失效
- `bdstoken` 会随会话变化，失效了重新抓一次
- **失效表现**：上传返回 `errno: -6`，页面显示设备离线

### 3.3 安全提醒

`BDUSS` 等价于你的网盘登录态。一旦泄露，对方可以读写你**整个网盘**。

- 不要提交到 Git（`.gitignore` 已排除 `baidu_cookies.json`）
- 不要放在手机 APK 里分发（当前实现是编译进 `LocalConfig.kt`，这是已知妥协）
- 建议单独注册一个只用于录音归档的网盘账号

---

## 四、编译与安装

### 4.1 配置凭据

后端：

```bash
cp backend/config.example.json backend/config.json
# 编辑 config.json，填 api_key 和 cookie_file 的绝对路径
```

App 侧不要改源码，写在根目录 `local.properties`（已被 `.gitignore` 忽略）：

```properties
sdk.dir=D:/tools/android-sdk
baidu.bduss=9ibTAyZ05...        # 同 baidu_cookies.json 的 cookies.BDUSS
baidu.stoken=1892ae98...        # cookies.STOKEN
baidu.bdstoken=674863b6...      # baidu_cookies.json 顶层的 bdstoken
dashscope.key=sk-...
```

`app/build.gradle.kts` 会在构建时把这些值注入成 `BuildConfig.BAIDU_BDUSS` 等常量，
`LocalConfig` 只负责读取。这样仓库里可以公开，凭据只留在本机。

有网盘凭据时 `UPLOAD_MODE` 自动为 `baidu`，不需要手改。

### 4.1.1 给设备起名字（多台手机时必做）

网盘目录按设备隔离：`/录音记录/<设备名>/<日期>/`。默认设备名是「机型-哈希」，
想改成「备用机」「主力机」这种，装完 App 后执行：

```bash
adb shell "run-as com.daluo.alwaysrec sh -c 'echo 备用机 > files/device_name.txt'"
```

改完重启 App 生效，界面第二行会显示当前设备名。

### 4.2 编译

```bash
# 首次需要生成 wrapper
gradle wrapper --gradle-version 8.11

./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 4.3 安装（小米 / MIUI 特别注意）

`adb install` 在 MIUI 上大概率失败：

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

即使开了「USB 安装」也可能被拦。可靠做法是绕过 adb 安装器：

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app.apk
adb shell "pm install -r -g /data/local/tmp/app.apk"
```

`-g` 会顺带授予麦克风和通知权限。

**注意**：重装后 MIUI 可能回收麦克风权限，需要 `pm grant` 或手动点授权。

---

## 五、国产 ROM 配置（必做）

不做这一步，录音会在几十分钟后被系统静默杀掉。

| 品牌 | 需要做的事 |
|---|---|
| 小米 / Redmi | 自启动、电池优化「无限制」、最近任务加锁 |
| 华为 / 荣耀 | 应用启动管理改为手动、允许后台活动 |
| OPPO / realme | 允许自启动、允许后台运行、关闭省电优化 |
| vivo / iQOO | 自启动、后台高耗电、后台运行 |

App 内置了跳转按钮（主页「打开自启动设置」「关闭电池优化」）。

**验证方法**：跑一天后看电脑看板的状态条。
如果心跳断超过 60 分钟，说明保活没过关。

---

## 六、后端部署

### 6.1 本机

```bash
cd backend
pip install -r requirements.txt
python server.py                    # http://127.0.0.1:8770
python server.py --port 9000        # 换端口
python server.py --host 0.0.0.0     # 局域网可访问（注意安全）
```

首次启动会立刻同步一次网盘，之后每 60 秒同步。

### 6.2 NAS / 服务器（可选）

```bash
# systemd 示例
[Unit]
Description=AlwaysRec Dashboard
After=network.target

[Service]
WorkingDirectory=/opt/always-rec/backend
ExecStart=/usr/bin/python3 server.py --host 0.0.0.0
Restart=always

[Install]
WantedBy=multi-user.target
```

如果暴露到公网，**必须加反向代理的 Basic Auth**，页面没有任何鉴权。

---

## 七、常见问题

**Q：页面显示「设备未上报」**
手机还没跑过带心跳的版本，或者心跳上传失败。
在手机上点「开始录音」会立刻触发一次心跳。

**Q：网盘里有 aac 但页面没有**
看「上次同步」时间。同步是 60 秒一次，也可以重启服务强制同步一次。

**Q：转写全是「Hmm.」「Cough.」**
麦克风被占用（比如在通话），或者环境噪声被当成人声。
检查是否在通话中。

**Q：小米手机录音几分钟就停**
保活没配好。见第五节。

**Q：`av.open() got an unexpected keyword argument 'metadata_errors'`**
PyAV 19 与 faster-whisper 1.2.1 不兼容。`avcompat.py` 已处理，
**但任何使用音频解码的模块必须在 import faster_whisper 之前 import 它**。

**Q：百度网盘上传失败 errno=-6**
BDUSS 失效，重新抓 cookie。
