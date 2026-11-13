# 端到端安装与故障排查

[English](SETUP.md)

## 1. 让数据进入 Health Connect

安装手环或手表的官方健康 App，并确认其中已经有最新记录。配置 Health Sync（或其他兼容导出工具），把需要的数据类型写入 Android Health Connect。排查 ReadMe 之前，先在 Health Connect 中确认记录确实存在。

华为手环 10 的参考链路是：`华为手环 10 → 华为运动健康 → Health Sync → Health Connect`。

不要卸载华为运动健康、提取账号 Cookie、重置手环，也不要让 ReadMe 直接配对手环。

### 为什么 ReadMe 不直接连接手环或读取厂商云

直连方案确实存在，也做过真机候选验证。它的链路更短，但手机端必须接管或与官方健康 App
分时管理同一只手环，配对和初始化失败时还要处理重连、解绑或恢复问题；官方 App 的图表、
云同步、表盘、固件和部分数据类型也可能受到影响。

当前方案保留官方 App 的正常使用，只在原有健康链路旁边增加一条只读通道。它不修改手环数据，
也不改变原有配对和历史记录。代价是链路更长，三个手机 App 都需要允许后台运行。

华为官方也提供[家庭空间](https://consumer.huawei.com/cn/support/content/zh-cn15903791/)，可以把
运动健康数据共享给另一个华为账号，并在华为运动健康 App 中查看。这条路线由官方维护，不需要
第三方插件，也不影响原有手环连接；如果目标只是让家人或另一个账号在官方 App 中查看，它更短、
更合适。它的边界也很明确：官方说明中心率和血氧只显示范围，压力只显示日均值，睡眠不包含零星
小睡，而且只能查看建立共享后产生的数据。家庭空间提供的是官方 App 内的查看能力，本身不会把
数据变成小机可调用的 MCP 工具，也没有 ReadMe 的独立凭据和“谁看过”查询审计。

另一类较短的方案是使用账号凭据或亲友共享，通过非公开的厂商云接口读取健康数据。这种方式
通常绑定单一品牌；接口、登录方式和可读字段可能随厂商服务变化，亲友共享还可能缺少压力、
睡眠分期或高频测量等明细。ReadMe 因此不保存厂商账号凭据，也不依赖这类非公开接口。

以小米亲友共享路线为例，数据通常经过：

`小米手环 → 小米运动健康主账号 → 亲友共享给小号 → 第三方插件或 MCP 使用小号凭据访问小米云`

它不需要手机端再运行一个 Health Connect 桥接 App，链路更短；但实际查询能力来自第三方插件
对非公开云接口的适配。厂商调整接口、认证流程或返回字段后，插件可能需要跟随修复，登录凭据
过期时也可能需要重新授权。这个维护边界不符合 ReadMe 希望减少厂商账号依赖、让不同设备共用
同一后端查询方式的目标，因此没有采用。

选择 Health Connect 是为了把设备适配和小机查询分开。更换手环或手表后，只要新的官方健康
App 能把所需记录写入 Health Connect，后面的 ReadMe、服务器和 MCP 通常都可以继续沿用。

#### 其他实现参考（未测试）

下面是采用不同取舍的官方能力和公开项目。ReadMe 没有实际测试这些路线，也不对第三方项目的
兼容性、稳定性或安全性作保证；列在这里只是方便需要更短链路或特定品牌方案的人继续了解：

- [华为运动健康 App 家庭空间（华为官方说明）](https://consumer.huawei.com/cn/support/content/zh-cn15903791/)：
  适合在官方 App 中向家人或另一个华为账号共享部分健康数据，不是 MCP 实现。

- [`xiaoyou5602/band-health-sync`](https://github.com/xiaoyou5602/band-health-sync)，作者
  [xiaoyou5602](https://github.com/xiaoyou5602)：基于 Gadgetbridge 直接管理支持的华为手环，并把
  步数、心率和睡眠等数据同步到自托管服务。
- [`wx10160330-oss/xavier_mi_health`](https://github.com/wx10160330-oss/xavier_mi_health)，作者
  [wx10160330-oss](https://github.com/wx10160330-oss)：使用小米亲友共享和小号凭据读取数据，
  面向 AstrBot 插件场景。
- [`Zhou-Ruichen/mi-health-mcp`](https://github.com/Zhou-Ruichen/mi-health-mcp)，作者
  [Zhou-Ruichen](https://github.com/Zhou-Ruichen)：通过小米云端接口读取本人或已授权亲友数据，
  并以 MCP 工具提供查询。
- [`ridd1ot/xiaomi-health-sync`](https://github.com/ridd1ot/xiaomi-health-sync)，作者
  [ridd1ot](https://github.com/ridd1ot)：比较本人账号与亲友账号两种读取模式，并把小米云数据
  同步到本地数据库。
- [`Misty02600/mi-fitness-python`](https://github.com/Misty02600/mi-fitness-python)，作者
  [Misty02600](https://github.com/Misty02600)：为部分小米亲友共享实现提供底层接口封装。

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
