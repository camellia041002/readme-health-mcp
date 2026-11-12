# 端到端安装与故障排查

[English](SETUP.md)

## 1. 让数据进入 Health Connect

安装手环或手表的官方健康 App，并确认其中已经有最新记录。配置 Health Sync（或其他兼容导出工具），把需要的数据类型写入 Android Health Connect。排查 ReadMe 之前，先在 Health Connect 中确认记录确实存在。

华为手环 10 的参考链路是：`华为手环 10 → 华为运动健康 → Health Sync → Health Connect`。

不要卸载华为运动健康、提取账号 Cookie、重置手环，也不要让 ReadMe 直接配对手环。

## 2. 部署服务器

```bash
cd server
cp .env.example .env
# 将 health.example.com 全部替换为自己的 HTTPS 域名。
./scripts/generate-secrets.sh
cp docker-compose.example.yml docker-compose.yml
docker compose up -d --build
```

使用 Caddy、nginx 或 Traefik 终止 TLS，并反向代理到 `server/README.md` 中说明的本机端口。不要直接把容器端口暴露到公网。

## 3. 连接 ReadMe

自行构建或安装 APK。在服务端看板中生成设备接入二维码，再用 ReadMe 扫码。只授权你愿意共享的 Health Connect 数据类型。先执行一次“立即同步”，再选择后台上传间隔。

## 4. 连接 MCP 客户端

客户端支持 OAuth 时优先使用 OAuth。否则，在“MCP Access”中为每一只读取者创建单独命名的 PAT，并连接到 `https://health.example.com/mcp`。每个凭据都只能读取、可以单独撤销，它的显示名称会出现在“谁看过”中。

## 5. 可选的屏幕使用时间

“应用使用情况访问权限”只提供各 App 在前台使用了多久，不包含截图、输入文字、通知或屏幕内容。不需要这个功能时不要授权即可。

## 自动同步与故障排查

健康数据实际经过两段独立的定时同步：

1. 官方健康 App 先同步手环，Health Sync 再把支持的记录写入 Health Connect。
2. ReadMe 读取 Health Connect 中已经存在的记录，再上传到自己的服务器。

Health Sync 显示成功，只能证明第一段完成，不代表 ReadMe 已经执行了第二段。反过来，ReadMe 刚刚上传成功，也可能只上传了 Health Connect 中较早的测量记录。

ReadMe 默认每 15 分钟安排一次后台上传。这是 Android 周期任务允许的最短间隔，但系统可能推迟执行，所以它是定时兜底，不是精确的 15 分钟实时计时器。

请在手机上逐项检查：

- 为官方健康 App、Health Sync 和 ReadMe 同时开启自启动和后台运行。部分手机把“自启动”和“电池不限制”做成了两个独立开关。
- 如果希望数据及时更新，为这三个 App 取消严格的电池优化。
- 如果 Health Sync 报告计划同步或前台服务失败，检查它的通知权限是否开启。
- 不要“强行停止”链路中的任何 App。App 被强行停止后，Android 会禁止它的后台计划任务，直到用户再次手动打开该 App。
- 确认 Health Sync 中需要的数据类型仍然开启。没有写入 Health Connect 的类别，ReadMe 无法补出来。
- 第一次安装或修复后，分别打开一次 Health Sync 和 ReadMe。先手动执行一次 Health Sync，在 Health Connect 中确认记录，再在 ReadMe 中执行“立即同步”。

不同时间的含义也不同：

- 健康卡片显示的是测量或睡眠实际发生的时间。
- ReadMe 的设备状态显示的是数据到达服务器的时间。
- MCP 只能返回已经走完两段同步的最新测量，所以服务器刚收到上传，也可能仍返回昨天的健康记录。

数据缺失时，按下面的顺序检查：

1. 官方健康 App 中是否已经出现最新值。
2. Health Sync 是否执行成功，并且包含缺失的数据类别。
3. Health Connect 中是否存在该记录，数据来源是谁。
4. 打开 ReadMe 执行“立即同步”，设备上传时间是否前进。
5. 服务端是否收到新的设备批次。
6. 再通过 MCP 查询，并检查“谁看过”记录。

## 最终验证

至少验证一次完整链路：Health Connect 中存在记录 → ReadMe 上传成功 → 服务端设备时间更新 → MCP 返回同一条数据 → “谁看过”出现对应查询。
