# GGBot

[![Download APK](https://img.shields.io/badge/下载-最新APK-35D07F?logo=android&logoColor=white&style=for-the-badge)](https://github.com/kenmrx/ggbot/releases/latest/download/GGBot.apk)
[![Release](https://img.shields.io/badge/Release-v1.9.3-blue?style=for-the-badge)](https://github.com/kenmrx/ggbot/releases/latest)
[![License](https://img.shields.io/badge/License-Apache--2.0-green?style=for-the-badge)](LICENSE)

GGBot 是一款 Android 本机 VPN 型广告过滤应用：基于 `VpnService` 建立本地 TUN 隧道，
在用户态完成 **DNS 拦截 + TCP 转发 + HTTPS MITM 解密 + 规则引擎匹配**。

![首页](docs/img/home-on.png)

## 特性

- **VPN 型 DNS 拦截**：用户态解析/构造 DNS 报文，域名与 CNAME 链双重判定，带 TTL 缓存与并发查询合并
- **用户态 TCP 栈**：握手、序号/ACK、滑动窗口、半关闭、上传方向背压，全部自实现
- **HTTPS 解密（MITM）**：动态 CA 签发，证书钉扎（pinning）失败站点自动放行；支持内容改写
- **规则引擎**：兼容 Adblock 风格语法（`||`、`^`、通配符、修饰符、`badfilter`），支持订阅自动更新
- **DoH 加密上游**：RFC 8484，加密优先、明文回落（阿里/腾讯/Cloudflare/Google 四端点）
- **快速设置磁贴**、开机自启、tun 自愈守卫、Shizuku 无障碍写回（跳过类规则）
- **宝宝回家（公益）**：接民政部全国救助寻亲网公开数据，按省份筛选在站儿童，照片实时加载不本地缓存
- **赞赏码**随 APK 签名分发并做防拆包校验

## 安装（直接下载即用）

👉 **[点此下载最新 APK](https://github.com/kenmrx/ggbot/releases/latest/download/GGBot.apk)**
（约 1.2 MB，Android 7.0+，无需 Root；安装时允许"未知来源"即可）

> 历史版本见 [Releases](https://github.com/kenmrx/ggbot/releases)。
> 每次发版 APK 与 tag 绑定，SHA-256 见 Release 说明，**请只从本仓库下载**。

## 系统要求

- Android 7.0+（minSdk 24，targetSdk 34）
- 过滤 HTTPS 需安装并信任应用内生成的 CA 证书（应用内有安装指引）

## 构建

macOS 或 Linux，需 JDK 21+ 与 Android SDK（platform-34、build-tools 34.0.0）。
`ANDROID_HOME` 已设置时自动使用，否则回退到脚本内默认路径（可自行修改脚本头部的 `SDK=`）。

```bash
# 一键回归：编译 src+test 并跑门闸（纯 JVM，无需真机，约 40 秒）
./test.sh

# 全量门闸（更多套件）后出包
./gate.sh && ./build.sh
```

产物为根目录 `GGBot.apk`，用 `adb install -r GGBot.apk` 安装。
签名密钥不随仓库分发：本地无 `ggbot.keystore` 时构建脚本会自动生成调试密钥；
正式发布请通过 `GGBOT_KS_PASS` 环境变量提供自己的口令与密钥。

## 项目结构

| 目录/文件 | 说明 |
|---|---|
| `src/com/ggbot/app/` | 全部应用源码（70 个 Java 文件，约 1.2 万行） |
| `src/moe/shizuku/api/` | Shizuku BinderContainer（自包含，45 行 Parcelable） |
| `test/` | 20 个纯 JVM 测试类（800+ 断言），`./test.sh` 或 `./gate.sh` 运行 |
| `assets/blocklist.txt` | 内置默认拦截规则 |
| `.github/workflows/ci.yml` | CI：macOS 上跑回归 + 出包 |

核心模块速览：

| 层 | 关键文件 | 职责 |
|---|---|---|
| 隧道主循环 | `DnsVpnService` | VpnService + TUN 读写、UDP DNS 收包/回包、分派 TCP |
| DNS | `DnsPacket` `DnsCache` `RealResolver` `Coalescer` `FakeIp` `DoH` | 报文解析构造、TTL 缓存、上游查询、并发合并、加密上游 |
| TCP | `TcpProxy` `TcpPacket` `Icmp` | 用户态 TCP 栈与 ICMP 回执 |
| MITM | `Mitm` `MitmBypass` `CaStore` `Der` `RespGate` | TLS 拦截、动态 CA、pinning 放行、响应门闸 |
| HTTP | `HttpGate` `UrlRule` `TokenIndex` `UrlIndex` `Rewrite` | 明文 HTTP 分帧、请求体状态机、URL 规则、内容改写 |
| 规则引擎 | `Rules` `RuleSet` `Decision` `ListParser` `Modifiers` `Domains` `Wild` | 规则匹配、adblock 列表解析、修饰符语义 |
| 订阅与配置 | `Feeds` `FeedJob` `Config` `Stats` `Diag` `Events` | 订阅下载、每日更新、配置持久化 |
| UI 与运维 | `MainActivity` `SettingsActivity` `Ui` `Notif` `GgTileService` `A11y*` `Shizuku*` `TunGuard` `Integrity` | 界面、通知、磁贴、无障碍跳过、自愈、完整性校验 |

## 贡献

欢迎 Issue 与 PR。提交前请跑 `./test.sh`，改动核心协议层（`TcpProxy`/`HttpGate`/`RespGate`）时
建议再跑 `./gate.sh`——这两套门闸覆盖了大量协议帧边界与静默失效场景，最容易抓到回归。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。

内置规则数据与 `test/samples/` 下的样本来自公开的广告拦截社区列表（EasyList 系等），
其使用遵循各自原始发布渠道的惯例；如需商用分发请自行复核数据来源。

## 免责声明

**1. 软件性质**
本软件（GGBot，下称"本软件"）是一款运行于用户自有 Android 设备上的本地网络过滤工具，
基于 Apache License 2.0 开源，按"现状"（AS IS）提供，**不附带任何形式的明示或默示担保**，
包括但不限于对适销性、特定用途适用性及不侵权的担保。

**2. 使用风险**
使用本软件产生的一切直接或间接后果（包括但不限于数据丢失、网络中断、账户异常、
设备功耗变化、与第三方应用的兼容性问题）由使用者自行承担。作者不对使用本软件
导致的其他应用行为变化（如支付、登录、内容加载异常）负责；遇到此类情况请先
关闭本软件的过滤功能再行验证。

**3. 合法使用**
使用者承诺在遵守所在国家/地区法律法规的前提下使用本软件，**不得**将本软件用于：
- 绕过付费内容、数字版权保护（DRM）或访问控制措施；
- 窃取、篡改他人网络通信数据或干扰他人设备的正常网络使用；
- 任何违法违规或损害他人合法权益的活动。
MITM（中间人解密）功能仅作用于**使用者本人设备上、且使用者显式添加到解密名单**
的域名，使用者应确保自己对相关网络行为拥有合法权限。

**4. 数据与隐私**
本软件不设服务器、不注册账号、不上传用户数据。规则订阅数据来自使用者主动配置的
公开地址；"宝宝回家"功能仅实时展示中华人民共和国民政部全国救助寻亲网公开发布的
信息，不缓存、不转发、不二次分发任何儿童个人信息。拆除、重打包或篡改本软件
（包括替换收款码）后的构建不属于官方构建，由此产生的风险与损失与作者无关。

**5. 资助与收款**
应用内"赞赏"为自愿性质的金额赞助，不构成任何商品或服务交易；赞赏前请核对应用
签名与收款码校验机制，**非官方构建一律不展示收款码**。已赞赏金额恕不退还。

**6. 商标与第三方**
"GGBot"名称及标识仅供标识本项目之用。项目中提及的 Android、GitHub 及各规则
订阅源名称等均为其各自所有者的商标或作品，本项目与其无隶属或背书关系。

**7. 唯一发布渠道**
本软件（含源码与编译产物）**仅在 GitHub 本仓库发布**。作者未在任何其他平台、
应用市场、论坛或群组发布或授权转载。任何其他渠道分发的副本均为未经授权的
第三方行为，作者不为其安全性、完整性或时效性负责；请始终以本仓库 Releases
与源码为准。

**8. 许可证**
除另有声明外，本软件源码依 [Apache License 2.0](LICENSE) 授权；内置规则数据与
文档依正文相应章节所述惯例与声明执行。安装、使用、复制或分发本软件即表示你已
阅读、理解并同意接受本免责声明的全部条款。
