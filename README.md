# ReadMe Health MCP

[简体中文](README.md) | [English](README.en.md)

ReadMe 是一套自托管、只读的健康数据桥，让支持 MCP 的 Chat 端和 Agent 端能够查询 Android Health Connect 中已有的数据。

当前 Android 安装包可从 [v1.0.1 发布页](https://github.com/camellia041002/readme-health-mcp/releases/tag/v1.0.1)下载。

```text
手环或手表 → 官方健康 App → Health Sync → Health Connect
                                               │
                                               ▼
                                      ReadMe Android App
                                               │
                                             HTTPS
                                               │
                                               ▼
                                    自托管 HealthQuery 服务
                                               │
                                               ▼
                                         只读 MCP 客户端
```

这套方案不会替换设备原有的官方链路。手环仍然由官方健康 App 管理和同步，ReadMe 只是在 Health Connect 旁边增加一条供小机使用的只读数据通道。

Android App 同时提供本地健康看板，也可以选择读取各应用的前台使用时长。屏幕使用时间权限完全独立，不授权就不会读取或上传这部分数据。

## 可以读取什么

- 睡眠记录与睡眠阶段
- 心率
- 血氧饱和度
- 生理周期记录（前提是上游写入 Health Connect）
- 步数和活动汇总
- Health Connect 提供的运动记录
- 可选的 Android 应用前台使用时长

实际能读到哪些数据，取决于设备的官方健康 App 或 Health Sync 向 Health Connect 写入了什么。当前完整验证的组合是华为手环 10、华为运动健康和 Health Sync。其他设备只要能写入兼容的 Health Connect 记录，原则上也可以沿用后面的 ReadMe、服务器和 MCP。

## 隐私边界

- 健康数据只读，项目不会修改 Health Connect 中的记录。
- 服务端由使用者自行部署，每个 MCP 凭据都可以单独撤销。
- 「谁看过」会记录凭据名称、查询时间、查询类别与时间范围，以及经过限制的返回摘要。
- 读取者名称由服务器所有者自行配置，项目没有内置私人助理名称。
- 屏幕使用时间是可选功能，只统计应用在前台使用了多久，不读取屏幕内容。

## 仓库结构

- [`android/`](android/)：ReadMe Android 客户端和本地看板，基于 Gadgetbridge，使用 AGPL-3.0。
- [`server/`](server/)：HealthQuery 服务端、Web 看板、设备接入、审计记录和 MCP 端点，使用 MIT。
- [`docs/SETUP.zh-CN.md`](docs/SETUP.zh-CN.md)：完整安装、自动同步与故障排查说明。
- [`docs/PRIVACY.md`](docs/PRIVACY.md)：权限和信任边界。
- [`LICENSE.md`](LICENSE.md)：本仓库的混合许可证说明。
- [`THIRD_PARTY.md`](THIRD_PARTY.md)：第三方来源和许可证清单。

## 版本关系

本仓库是由手机端和服务端两个私人开发主线抽取、脱敏并组合而成的公开发布快照。私库始终保存
最新完整源码，公开版可以在两次正式发布之间暂时晚于私库。公开仓库只保留自托管需要的通用
接口和示例，不包含真实生产域名、凭据、私人读取者名称、数据库、私人截图或部署日志。

## 快速开始

1. 使用 `server/docker-compose.example.yml` 和自己的 HTTPS 域名部署服务端。
2. 从 Releases 安装 APK，或者自行构建 `android/`。
3. 在服务端看板生成设备接入二维码，然后使用 ReadMe 扫描。
4. 只授权你愿意共享的 Health Connect 数据类型。
5. 为每一只小机创建独立、只读的 MCP 凭据。

将服务端暴露到互联网前，请先阅读[完整安装说明](docs/SETUP.zh-CN.md)。

## 自动同步

自动更新包含两段互相独立的同步：Health Sync 先把官方健康 App 中的数据写入 Health Connect，随后 ReadMe 再把 Health Connect 中已有的数据上传到自己的服务器。前一段完成，不代表后一段已经完成。

ReadMe 默认每 15 分钟安排一次后台上传，这是 Android 后台任务允许的最短周期，但并不是精确的实时计时器。手机的省电、后台限制、网络状态或“强行停止”都可能推迟或停止任务。请为官方健康 App、Health Sync 和 ReadMe 开启自启动与后台运行，并取消严格的电池限制；若 Health Sync 报告自动同步失败，也要检查它的通知权限。

卡片中的日期和时间是健康数据的实际测量时间，不是 ReadMe 最近一次上传到服务器的时间。完整检查清单和排查顺序见[安装与故障排查](docs/SETUP.zh-CN.md#自动同步与故障排查)。

## 重要限制

ReadMe 不会通过蓝牙直接连接华为手环。手环连接仍由华为运动健康负责，Health Sync 将支持的数据转入 Health Connect。Android 的后台调度属于尽力执行；手机省电策略过于严格时，同步可能延迟。

## 许可证

`android/` 使用 AGPL-3.0，并保留 Gadgetbridge 的来源和署名；`server/` 使用 MIT。详细边界见仓库根目录的[许可证说明](LICENSE.md)、[第三方清单](THIRD_PARTY.md)以及两个子目录中的完整许可证文件。
