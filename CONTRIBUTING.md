# 贡献指南

欢迎 Issue 与 PR。这个项目的门闸文化很简单：**改完必须跑绿，跑不绿不合并。**

## 快速开始

```bash
git clone https://github.com/<you>/ggbot.git && cd ggbot
./test.sh    # 9 套件，约 40 秒
./gate.sh    # 全量 20+ 测试类，约 2 分钟
```

两个脚本都是纯 JVM 自检，不需要 Android 设备。

## 提交前检查

1. `./gate.sh` 全绿（当前 850 断言）
2. 改协议层（`TcpProxy` / `HttpGate` / `RespGate` / `DnsPacket`）时，对应测试类要加断言钉住行为
3. 不引入第三方依赖——本项目除 Android SDK 外零依赖，刻意保持可审计
4. 用户可见文案不用「绝对」「百分百」类承诺

## 代码纪律（踩过坑的）

- TUN 写入是全局锁：`sink.write` 绝不留在线程锁内
- 解析网络字节流一律按不可信输入：长度上限、配平扫描、越界即弃
- 判定顺序改动必须同步 `Decision` 顶部注释与测试
- `Config`/`Stats` 等持久化字段变更保持向后兼容（升级无感）

## 提交信息

格式：`模块: 一句话说清改动与验证`，参考 `git log`。
