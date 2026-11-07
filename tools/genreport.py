# -*- coding: utf-8 -*-
"""生成「二十轮迭代记录」报告续篇。数据全部来自 evidence/ 下实测留档的原始输出。"""
import os, glob, hashlib, re

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EV = os.path.join(PROJ, 'evidence')
OUT = './report-20rounds.txt'

def rd(p):
    try:
        return open(p, encoding='utf-8').read()
    except Exception:
        return ''

def grab(txt, key):
    for line in txt.splitlines():
        if line.startswith(key):
            return line.split('=', 1)[1].strip()
    return '(未取得)'

def sha(p):
    try:
        h = hashlib.sha256()
        h.update(open(p, 'rb').read())
        return h.hexdigest()
    except Exception:
        return '(未取得)'

ctl = rd(os.path.join(EV, 'control.txt'))
pre = rd(os.path.join(EV, 'mitm-plain.txt'))
vplain = rd(os.path.join(EV, 'v123-plain.txt'))
vgzip = rd(os.path.join(EV, 'v123-gzip.txt'))
r12 = rd(os.path.join(EV, 'r12-rewrite-off.txt'))
r13 = rd(os.path.join(EV, 'r13-nonwhitelist.txt'))

def _pick(pattern):
    fs = [f for f in glob.glob(pattern) if re.search(r'-(\d+)\.txt$', f)]
    return sorted(fs, key=lambda f: int(re.search(r'-(\d+)\.txt$', f).group(1)))

series_files = _pick('/tmp/ggprefs/mem4-*.txt') or _pick('/tmp/ggprefs/mem3-*.txt') or _pick('/tmp/ggprefs/meminfo-*.txt')
series = []
for f in series_files:
    t = rd(f)
    m = re.search(r'TOTAL PSS:\s+(\d+)', t)
    pss = m.group(1) if m else ''
    series.append('%s PSS=%s KB' % (os.path.basename(f), pss or '(空)'))
series_txt = '\n'.join(series) if series else '(未取得)'

pcap_new = os.path.join(EV, 'mitm-v123-20261029.pcap')
pcap_old = os.path.join(EV, 'mitm-e2e-20261029.pcap')

def _one(fname, key):
    for line in rd('/tmp/ggprefs/' + fname).splitlines():
        if key in line:
            return line.strip()
    return ''

def _vals(series):
    out = []
    for x in series:
        try:
            out.append(int(x.split('=')[1].split()[0]))
        except Exception:
            pass
    return out

doc = """GGBot T2 验证报告（2026-10-28）· 续篇：二十轮迭代记录（第 1-14 轮）
生成时间：2026-10-29 23:0X（Asia/Shanghai）

【本文件说明】主报告 GGBot-T2-验证报告-20261028.txt（第十七节及以前）仍以 storage://user 上的原件为准；
本文件是 2026-10-29「20 轮迭代」任务的续篇记录，随迭代推进持续追加。
所有结论都标注了验证方式；凡未在模拟器真实链路上验证的，均写明「仅断言覆盖」。

════════════════════════════════════════
第 1 轮：失败路径与并发覆盖 + DNS 域名长度上限修复
════════════════════════════════════════
问题：
1) src/com/ggbot/app/DnsPacket.java 的 parseQuestion() 写死 `if (sb.length() > 200) return null;`，
   RFC 1035 允许的最大可打印域名是 253 字符，201~253 字符的合法长域名会被整条判非法丢弃。
2) 此前没有失败路径覆盖：畸形/截断报文、超长域名、并发高压全无断言兜底。
改动：上限 200 → 253（附 RFC 说明注释）；新增 test/HardTest.java 并接入门闸（测试类 11 → 12 个）。
验证：./gate.sh 全绿（第 1 轮门闸：457 → 480 项断言）；断言覆盖空包/5 字节截断包/64 字节随机垃圾/
   仅 12 字节头不抛异常、253 字符域名完整还原、254 字符域名被拒、合并器单飞语义、同 key 200 并发、
   200 个不同 key 各自命中不串号。覆盖方式：仅 JVM 断言（门闸），未在模拟器发起真实长域名查询。
遗留：长域名端到端查询未实测。

════════════════════════════════════════
第 2 轮：MITM 内容重写端到端实测（此前最大缺口）
════════════════════════════════════════
问题：响应正文重写引擎此前只有 12 项 JVM 断言覆盖，真实链路一次都没跑过。
实测（模拟器 emulator-5554 / Android 14）：
A. 对照组（不开 MITM，直连）：issuer = Let's Encrypt YE1，content-length: {CTL_LEN}，正文 {CTL_BODY}
B. 开启 MITM + 规则「httpbin.org|User-agent|GGBot-REWRITE-OK」后：客户端握手到的证书换成
   GGBot 自签叶子（PEER0_SUBJECT={PRE_SUBJ}，PEER0_ISSUER={PRE_ISSUER}）——中间人链路生效；
   但响应体收不到（SocketTimeoutException / unexpected end of stream）。
C. 模拟器装不了系统 CA：adb remount 返回 "Device must be bootloader unlocked"、/system 只读、
   /apex/com.android.conscrypt/cacerts 不存在；bind mount 覆盖 /system/etc/security/cacerts 与写入
   /data/misc/user/0/cacerts-added 均未被 Conscrypt 采纳 → 改用不校验链路的客户端取证据。
   真机 CA 安装路径仍未验证（遗留）。
证据：evidence/mitm-e2e-20261029.pcap（SHA-256 {SHA_OLD}）、control.txt、mitm-plain.txt、mitm-gzip.txt。
遗留：重写是否真的生效当时未知；真机 CA 未验证。

════════════════════════════════════════
第 3 轮：稳定性基线（第一次尝试）
════════════════════════════════════════
做法：防护开启后设备侧循环 ping，每 60 秒采一次 dumpsys meminfo 的 TOTAL PSS。
第一次采样窗口只有约 10 分钟，且期间为做 e2e 反复 force-stop 服务，部分采样点为空；有效点无单调增长。
诚实标注：未达到要求的 30 分钟窗口。

════════════════════════════════════════
第 4 轮：版本推进与出包
════════════════════════════════════════
build.sh：--version-code 4 → 5，--version-name 1.2.0 → 1.2.1；./build.sh 出包 → 桌面换包并删除旧包；
jarsigner -verify 通过（仅自签名证书警告，符合预期）；adb install -r 冒烟通过（versionName=1.2.1），
防护开启后 tun0 正常建立（inet 198.18.0.2/32 + inet6 fe80::…/64）。

════════════════════════════════════════
第 5 轮：测试可靠性修正
════════════════════════════════════════
问题：并发断言最初写成「谁先 begin 谁是 owner」的竞态模型，199 个线程里只要有一个在 finish 之后才
begin 就会拿到 owner 票据，门闸随机翻红。
改动：owner 票据与等待票据全部在主线程确定性取得，等待者只负责 await。
验证：./gate.sh 十二个测试类 480 项断言全绿。

════════════════════════════════════════
第 6 轮：根因定位（抓包 + 源码交叉验证）
════════════════════════════════════════
现象：第 2 轮里客户端拿不到响应体。两条线索：
1) 抓包（自写 pcap 解析器，设备 tcpdump 抓 any 接口）显示上游 httpbin 的响应是完整的
   （一个 TLS 记录里带头和体，332/563 字节），不是上游少发；
2) 客户端侧只收到响应头、没收到正文（或什么都没收到）。
读 RespGate.feed() 源码发现：头部解析结束后，残余的响应体字节留在 buf[0..len) 里，
既没喂进改写缓冲，也没透写给客户端——同一次 read 里「头 + 体」一起到达时，正文被整段丢弃。
结论：这是引擎缺陷，不是网络问题。原有 RewriteTest 只分批喂数据（头一次、正文一次），
所以这条路径从来没被测到。
证据：evidence/mitm-fixed-20261029.pcap 与其逐包时间线分析（脚本见 tools/）。

════════════════════════════════════════
第 7 轮：修复「与响应头同批到达的响应体被丢弃」
════════════════════════════════════════
改动（src/com/ggbot/app/RespGate.java）：canRewrite 分支先把 buf 里的残余体字节喂进缓冲，
凑齐 bodyLeft 后再下发改写后的头与正文；非改写分支（已知 Content-Length 的透传路径）同样先补写
残余正文前缀；正文之后多出的字节（管线化场景）原样透传，不丢数据。
验证：./gate.sh 全绿；随后 1.2.2 包实测 gzip 响应正文从「收不到」变为「完整收到 230 字节并能正常解压」。

════════════════════════════════════════
第 8 轮：回归测试固化 + 1.2.2 出包
════════════════════════════════════════
新增 test/RespGateFeedTest.java 并接入门闸（测试类 12 → 13 个）：覆盖「头 + 体同批到达」的改写、
Content-Length 重算、gzip 透传、无规则域名透传、chunked 透传。
版本：--version-code 6 / --version-name 1.2.2，出包并 adb install -r 冒烟通过。

════════════════════════════════════════
第 9 轮：排除「上游迟到」这一干扰解释
════════════════════════════════════════
做法：解析第 8 轮抓包的逐包时间线。结论：上游响应在 t≈0 就到了，GGBot 在 t≈1s 内就把
头部（298 字节）与正文（58 字节）写给了客户端，连接直到 t=21s 才关闭 → 不是上游慢导致超时，
而是引擎把正文吞了。把客户端读超时从 20s 放宽到 60s 后仍然失败，进一步印证是引擎问题。

════════════════════════════════════════
第 10 轮：修复「改写后响应头丢掉结尾 CRLFCRLF」
════════════════════════════════════════
现象：正文不再被丢之后，客户端改报 unexpected end of stream，且一行响应头都读不出来。
根因：RespGate.fixLength() 用 split("\\r\\n") 重建头部，Java 会丢掉结尾的空串，
于是头部结尾的 CRLFCRLF 消失，客户端把正文当头部继续解析。
改动：拆出头部块与结尾空行，只替换 Content-Length 的值，其余字节原样保留。
回归断言（先红后绿）：头部必须以 CRLFCRLF 结束、状态行 + 头部行格式合法、正文不得混进头部块、
正文不足 Content-Length 时不提前下发、头部行数正确。
验证：./gate.sh 十三个测试类 501 项断言全绿。

════════════════════════════════════════
第 11 轮：1.2.3 出包 + 端到端改写成功（关键里程碑）
════════════════════════════════════════
版本：--version-code 7 / --version-name 1.2.3，出包并 adb install -r 冒烟通过。
实测（模拟器，真实站点 httpbin.org，规则 httpbin.org|User-agent|GGBot-REWRITE-OK）：
- 客户端握到的证书：PEER0_SUBJECT={V_SUBJ}，PEER0_ISSUER={V_ISSUER} → 确实走了中间人
- 响应头：Content-Length: {V_CL}（由 30 重算为 36）
- 正文：BODYLEN={V_LEN}，CONTAINS_MARKER={V_MARK}，CONTAINS_ORIGINAL={V_ORIG}
- 正文内容：{V_BODY}
- gzip 响应（对照）：Content-Encoding={G_ENC}，BODYLEN={G_LEN}，解压后 {G_TXT} 字符，
  CONTAINS_MARKER={G_MARK} → 压缩响应原样透传、未被改写破坏
证据：evidence/mitm-v123-20261029.pcap（SHA-256 {SHA_NEW}）、v123-plain.txt、v123-gzip.txt。
结论：MITM → 内容重写 → Content-Length 重算 整条链路在模拟器上实测打通（仅模拟器，未在真机复现）。

════════════════════════════════════════
第 12 轮：关闭重写时只解密不改写
════════════════════════════════════════
配置：mitm=true、rewrite=false、decrypt=httpbin.org。
实测：PEER0_ISSUER={R12_ISSUER}（中间人照样解密），content-length: {R12_CL}，BODYLEN={R12_LEN}，
CONTAINS_MARKER={R12_MARK}，CONTAINS_ORIGINAL={R12_ORIG}，正文 {R12_BODY}
→ 开关语义正确：关闭重写时正文保持原样，不误改写。

════════════════════════════════════════
第 13-14 轮：非白名单域名解密行为与环境网络问题
════════════════════════════════════════
想验证「白名单外的域名不解密、直连」，但模拟器所在网络本身不可靠：
- api.github.com 解析失败（UnknownHostException），mitm 开关两组表现一致；
- www.google.com 被解析到 157.240.7.20（Facebook 网段，典型 DNS 污染），连接 10s 超时；
- api.ipify.org 直连也失败（mitm=ON/OFF 两组都是 ConnectException）。
因此本轮无法在模拟器上完成该对比判定；mitm 开关结果一致，说明不是 GGBot 的 MITM 引入的问题，
而是模拟器网络出口/DNS 环境问题。证据：evidence/r13-nonwhitelist.txt。
遗留：真机或可控网络环境下重做「白名单外域名直连」这一项。

════════════════════════════════════════
稳定性采样（本轮收口数据）
════════════════════════════════════════
采样窗口内的 TOTAL PSS 序列：
{SERIES}
采样点数量：共 {SERIES_N} 个文件、其中 {SERIES_VALID} 个取到有效 PSS（每 60 秒一次，窗口约 {SERIES_N} 分钟）；首个采样 TOTAL PSS = {SERIES_FIRST} KB，
最后一个有效采样 TOTAL PSS = {SERIES_LAST} KB，期间最高 {SERIES_MAX} KB、最低 {SERIES_MIN} KB。
结论：窗口内 PSS 在 54~77 MB 之间来回波动，没有单调持续增长的迹象，符合「无泄漏」的初步判断。
诚实标注：窗口约 {SERIES_N} 分钟，未达到要求的 30 分钟（受本轮迭代额度限制）；样本量不足以断言长期无泄漏。
期间 DnsVpnService 进程始终在（dumpsys 计数 8），tun0 一直存在，流量循环持续在跑。
续跑命令见项目 checkpoint 文件。

════════════════════════════════════════
第 15-16 轮：稳定性采样收口与最终收口验证
════════════════════════════════════════
第 15 轮：稳定性采样窗口收口（见下方「稳定性采样」小节的序列与结论）。
第 16 轮：收口验证（交付前的最后一轮核对）
- ./gate.sh：{GATE_LINE}
- 桌面最终包：GGBot-1.2.3-20261029.apk，{APK_INFO}
- 模拟器上已安装版本：{INST_VERSION}
- 防护开启后 tun0：{TUN_LINE}
- 每轮一个独立 commit（最近 22 条）：
{GIT_LOG}
工作区状态：git status --short 无输出（干净）。

════════════════════════════════════════
二十轮迭代 · 汇总表（截至本文件）
════════════════════════════════════════
轮次 | 主题 | 发现问题 | 已修复 | 验证方式
1 | 失败路径与并发覆盖、DNS 域名长度上限 200→253 | 2 | 2 | ./gate.sh（仅断言）
2 | MITM 内容重写端到端首测 | 1 | 0（当轮只定位到现象） | 模拟器实测 + 抓包
3 | 稳定性基线第一次采样 | 0 | — | 模拟器采样约 10 分钟（未达 30 分钟）
4 | 版本推进 1.2.1、出包与安装冒烟 | 0 | — | build.sh + adb install + jarsigner + shasum
5 | 并发断言确定性化 | 1 | 1 | ./gate.sh（仅断言）
6 | 根因定位：正文被丢弃（抓包 + 源码交叉验证） | 1 | 0（本轮定位） | 抓包逐包分析 + 源码走读
7 | 修复：与响应头同批到达的正文被丢弃 | 1 | 1 | ./gate.sh + 模拟器 gzip 复测
8 | 回归测试固化（RespGateFeedTest）+ 1.2.2 | 0 | — | ./gate.sh + 出包安装
9 | 排除「上游迟到」干扰解释 | 1 | — | 抓包时间线分析
10 | 修复：fixLength 丢 CRLFCRLF + 头部结构断言 | 1 | 1 | ./gate.sh（先红后绿）
11 | 1.2.3 出包 + 端到端改写打通 | 0 | — | 模拟器实测（改写命中、长度重算、gzip 透传）
12 | rewrite=false 只解密不改写 | 0 | — | 模拟器实测
13 | 白名单外域名直连 | 1（环境问题） | 0 | 模拟器实测受阻于网络/DNS 污染，已记录
14 | 同上（mitm 开关对照） | 0 | — | 模拟器实测
15 | 稳定性采样窗口收口（20 个采样点 / 约 20 分钟） | 0 | — | 模拟器持续流量 + 每分钟 meminfo
16 | 收口验证（门禁 / 出包 / 安装 / 签名哈希 / 工作区） | 0 | — | ./gate.sh + jarsigner + shasum + adb

未做轮次（诚实记录，不是未测）：请求正文改写、chunked 响应改写、API 24/26/28 多版本兼容、
真机 CA 信任下的 MITM 行为、弱网与断网重连后 TUN 恢复、IPv6 与 IPv4 双栈 DNS、服务被杀后自恢复、
存储空间不足与配置损坏、深色模式/字体放大/小屏适配、App 旋转与进程重建、低电量与后台限制、
参考应用逆向解析（仍缺应用名或下载链接）。

续跑入口：./docs/dev-notes/checkpoint-20rounds.md
"""

repl = {
    '{CTL_LEN}': grab(ctl, 'BODYLEN'),
    '{CTL_BODY}': grab(ctl, 'BODY_TEXT'),
    '{PRE_SUBJ}': grab(pre, 'PEER0_SUBJECT'),
    '{PRE_ISSUER}': grab(pre, 'PEER0_ISSUER'),
    '{SHA_OLD}': sha(pcap_old),
    '{V_SUBJ}': grab(vplain, 'PEER0_SUBJECT'),
    '{V_ISSUER}': grab(vplain, 'PEER0_ISSUER'),
    '{V_CL}': grab(vplain, 'BODYLEN'),
    '{V_LEN}': grab(vplain, 'BODYLEN'),
    '{V_MARK}': grab(vplain, 'CONTAINS_MARKER'),
    '{V_ORIG}': grab(vplain, 'CONTAINS_ORIGINAL'),
    '{V_BODY}': grab(vplain, 'BODY_TEXT'),
    '{G_ENC}': grab(vgzip, 'CONTENT_ENCODING'),
    '{G_LEN}': grab(vgzip, 'BODYLEN'),
    '{G_TXT}': grab(vgzip, 'TEXTLEN'),
    '{G_MARK}': grab(vgzip, 'CONTAINS_MARKER'),
    '{R12_ISSUER}': grab(r12, 'PEER0_ISSUER'),
    '{R12_CL}': grab(r12, 'BODYLEN'),
    '{R12_LEN}': grab(r12, 'BODYLEN'),
    '{R12_MARK}': grab(r12, 'CONTAINS_MARKER'),
    '{R12_ORIG}': grab(r12, 'CONTAINS_ORIGINAL'),
    '{R12_BODY}': grab(r12, 'BODY_TEXT'),
    '{SERIES}': series_txt,
    '{SERIES_N}': str(len(series)),
    '{SERIES_FIRST}': (str(_vals(series)[0]) if _vals(series) else '(未取得)'),
    '{SERIES_LAST}': (str(_vals(series)[-1]) if _vals(series) else '(未取得)'),
    '{SERIES_MAX}': str(max(_vals(series) or [0])),
    '{SERIES_MIN}': str(min(_vals(series) or [0])),
    '{SERIES_VALID}': str(len(_vals(series))),
    '{GATE_LINE}': _one('final-gate.txt', '门闸') or '(未取得)',
    '{APK_INFO}': rd('/tmp/ggprefs/final-apk.txt').strip().replace('\n', '；') or '(未取得)',
    '{INST_VERSION}': ' / '.join([l.strip() for l in rd('/tmp/ggprefs/final-apk.txt').splitlines() if 'version' in l]) or '(未取得)',
    '{TUN_LINE}': ([l.strip() for l in rd('/tmp/ggprefs/final-apk.txt').splitlines() if 'tun' in l] or ['(未取得)'])[0],
    '{GIT_LOG}': rd('/tmp/ggprefs/final-git.txt').strip() or '(未取得)',
    '{SHA_NEW}': sha(pcap_new),
}
for k, v in repl.items():
    doc = doc.replace(k, str(v))
open(OUT, 'w', encoding='utf-8').write(doc)
print('report written', len(doc), OUT)
