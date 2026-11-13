# 架构选择与替代方案

[English](ARCHITECTURE.md)

这篇文档解释 ReadMe 为什么采用较长的 Health Connect 链路，以及几种更短路线分别适合什么
场景。列出的第三方项目均未由 ReadMe 实际测试；链接仅用于中立指路，不代表兼容性、安全性
或长期可用性保证。

## ReadMe 当前采用的链路

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

这条链路的目标不是最短，而是把三件事分开：官方 App 继续管理设备，Health Connect 提供相对
统一的健康记录，ReadMe、服务器和 MCP 负责只读上传、查询和审计。这样不会改变原有配对和历史
记录；更换设备后，只要新的官方健康 App 能把所需数据写入 Health Connect，后面的组件通常可以
继续使用。

代价是手机端链路更长。官方健康 App、Health Sync 和 ReadMe 都需要允许后台运行，Android 的
省电策略也可能造成同步延迟。

## 方案对比

| 路线 | 主要优势 | 主要边界 | 当前结论 |
| --- | --- | --- | --- |
| 华为家庭空间 | 官方维护，不影响手环连接，适合家人查看 | 主要在官方 App 内查看；共享字段被简化；不会自动产生 MCP 工具 | 适合官方 App 内共享，不替代 ReadMe |
| 华为手环直连 APK | 链路短，代码和服务器可以自托管 | APK 需要接管手环，可能与官方 App 争用配对和同步 | 有公开实现，ReadMe 未采用 |
| 华为 Health Kit MCP | 使用官方开发者接口 | 需要申请健康服务权限和 OAuth；公开项目仍有真实接口验证未完成 | 可继续关注，当前不是现成替代品 |
| 华为网页登录 MCP | 可以把本人网页数据包装成 MCP | 依赖浏览器登录凭据，凭据会过期；未确认支持家庭空间共享数据 | 有公开实现，ReadMe 未测试 |
| 华为个人数据导出 MCP | 本地优先，不需要持续保存账号登录态 | 需要手动导出，不能持续获取最新数据 | 适合离线复盘，不适合自动同步 |
| 小米亲友小号＋第三方 MCP | 链路短，不需要 Health Connect 桥接 App | 依赖小号凭据、非公开云接口和厂商字段；共享明细可能减少 | 有公开实现，ReadMe 未测试 |
| ReadMe＋Health Connect | 保留官方 App、跨设备复用、每个读取者可撤销并有查询审计 | 链路较长，依赖三个手机 App 的后台同步 | 当前正式支持方案 |

## 华为家庭空间

华为官方提供[家庭空间](https://consumer.huawei.com/cn/support/content/zh-cn15903791/)，可以把部分
运动健康数据共享给另一个华为账号，并在华为运动健康 App 中查看。它由官方维护，不需要第三方
插件，也不影响原有手环连接。如果目的只是让家人或另一个账号在官方 App 中查看数据，这条路线
更短、更合适。

官方同时说明了共享边界：心率和血氧只显示范围，压力只显示日均值，睡眠不包含零星小睡，而且
只能查看建立共享后产生的数据。家庭空间本身提供的是官方 App 内的查看能力，不会直接产生小机
可调用的 MCP 工具，也没有 ReadMe 的独立读取凭据和“谁看过”查询审计。

理论上可以再为接收共享的华为账号连接一个 MCP 适配器。但目前找到的公开华为 MCP 分别使用
Health Kit、网页登录凭据或个人数据导出；没有一个明确说明并实际验证过“家庭空间共享账号 →
MCP”。因此这里不把这条组合路线描述为已经跑通的方案。

相关参考：

- [华为运动健康 App 家庭空间（华为官方说明）](https://consumer.huawei.com/cn/support/content/zh-cn15903791/)
- [`SabaTech-dev/huawei-health-mcp`](https://github.com/SabaTech-dev/huawei-health-mcp)：使用 Health Kit
  和 OAuth；其 README 标明华为开发者验证与真实 API 测试仍待完成，未声明家庭空间支持。
- [`yinxu-xiong/huawei-training-camp-mcp-connector`](https://github.com/yinxu-xiong/huawei-training-camp-mcp-connector)：
  使用本人浏览器登录凭据读取华为运动健康网页数据；未声明家庭空间支持。
- [`LiPeee1/sport-health-mcp`](https://github.com/LiPeee1/sport-health-mcp)：读取用户手动导出的华为
  运动健康数据包，适合离线分析，不是持续同步方案。

## 华为手环直连 APK

直连路线让一个 APK 通过蓝牙直接管理手环，再把数据写入 Health Connect 或上传到自托管服务器。
它的链路更短，也不依赖厂商云接口；公开实现的代码可以自行托管和修改。

它的代价是新 APK 必须接管或与官方健康 App 分时管理同一只设备。配对或初始化失败时可能需要
处理重连、解绑或恢复，官方 App 的图表、云同步、表盘、固件和部分数据类型也可能受到影响。
ReadMe 选择继续让华为运动健康作为唯一设备管理者，因此没有采用直连路线。

相关参考：

- [`xiaoyou5602/band-health-sync`](https://github.com/xiaoyou5602/band-health-sync)，作者
  [xiaoyou5602](https://github.com/xiaoyou5602)：基于 Gadgetbridge 直接管理支持的华为手环，并把
  步数、心率和睡眠等数据同步到自托管服务或 Health Connect。

## 小米亲友小号与厂商云 MCP

典型链路是：

`小米手环 → 小米运动健康主账号 → 亲友共享给小号 → 第三方插件或 MCP 使用小号凭据访问小米云`

这条路线不需要手机端再运行 Health Connect 桥接 App，链路更短。小号也可以把第三方程序使用的
登录凭据与主账号隔开；但实际权限仍取决于小米的账号、共享和接口行为，不能把这种隔离描述成
未经审计的绝对安全保证。

实际查询能力来自第三方程序对非公开云接口的适配。厂商调整接口、认证流程或返回字段后，程序
可能需要跟随修复；登录凭据过期时也可能需要重新授权。亲友共享的数据通常也少于主账号直接
读取，压力、睡眠分期或高频测量等明细可能缺失。

这条路线适合愿意绑定小米生态、优先追求短链路的人。ReadMe 没有采用它，是因为项目希望减少
厂商账号和非公开接口依赖，并让不同设备共用同一套后端查询与审计能力。

相关参考：

- [`wx10160330-oss/xavier_mi_health`](https://github.com/wx10160330-oss/xavier_mi_health)，作者
  [wx10160330-oss](https://github.com/wx10160330-oss)：使用小米亲友共享和小号凭据读取数据，面向
  AstrBot 插件场景。
- [`Zhou-Ruichen/mi-health-mcp`](https://github.com/Zhou-Ruichen/mi-health-mcp)，作者
  [Zhou-Ruichen](https://github.com/Zhou-Ruichen)：通过小米云端接口读取本人或已授权亲友数据，并
  以 MCP 工具提供查询。
- [`ridd1ot/xiaomi-health-sync`](https://github.com/ridd1ot/xiaomi-health-sync)，作者
  [ridd1ot](https://github.com/ridd1ot)：比较本人账号与亲友账号两种读取模式，并把小米云数据同步
  到本地数据库。
- [`Misty02600/mi-fitness-python`](https://github.com/Misty02600/mi-fitness-python)，作者
  [Misty02600](https://github.com/Misty02600)：为部分小米亲友共享实现提供底层接口封装。

## 为什么最终选择 Health Connect

ReadMe 需要满足的不只是“另一个账号能看到数据”，还包括：

- 小机能够通过标准 MCP 主动查询，不需要操作官方 App 界面。
- 每只小机使用独立、只读、可撤销的凭据。
- “谁看过”能记录读取者、查询时间、数据类别、时间范围和受限结果摘要。
- 官方健康 App 继续负责设备，不需要解绑、重置或迁移历史记录。
- 更换设备时，后端和 MCP 尽量不随厂商一起重做。

因此，Health Connect 路线不是最短路线，而是当前约束下更符合 ReadMe 目标、并且已经完成端到端
验证的路线。
