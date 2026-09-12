#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检测 malguem 应用内更新源在当前网络下的可达性。

电视端 (AppUpdater.java) 按顺序尝试每个源, 取 <源>version.json 与 <源>malguem-tv.apk,
连接超时 5s / 读超时 20s。本脚本复刻这套行为, 并把失败拆成 DNS → TCP → TLS → HTTP
四段, 便于区分「域名被污染」「连接被阻断」「握手被 RST」「路径 404」。

源列表直接从 AppUpdater.java 里解析, 改了 java 不用改脚本。

用法:
    python3 tools/check_update_sources.py              # 只测 version.json
    python3 tools/check_update_sources.py --apk        # 顺带测 apk 前 64KB (验 PK 头)
    python3 tools/check_update_sources.py -j 1         # 串行, 更接近电视端逐个试的行为
    python3 tools/check_update_sources.py --proxy      # 走系统代理做对照

默认忽略系统与环境变量里的代理 —— 要测的就是裸连。
"""

import argparse
import concurrent.futures
import ipaddress
import os
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

CONNECT_TIMEOUT = 5     # 和 AppUpdater 的 connectTimeout 一致
READ_TIMEOUT = 20       # 和 AppUpdater 的 readTimeout 一致

JAVA = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "..", "app", "src", "main", "java", "com", "github",
                    "mmooyyii", "malguem", "AppUpdater.java")

# 解析不到 java 时的兜底 (与 e9b94dc 时的列表一致)
FALLBACK = [
    "https://mmooyyii.github.io/malguem/",
    "https://gcore.jsdelivr.net/gh/mmooyyii/malguem@release/",
    "https://testingcf.jsdelivr.net/gh/mmooyyii/malguem@release/",
    "https://fastly.jsdelivr.net/gh/mmooyyii/malguem@release/",
    "https://github.com/mmooyyii/malguem/releases/latest/download/",
    "https://gh-proxy.com/https://github.com/mmooyyii/malguem/releases/latest/download/",
    "https://ghproxy.net/https://github.com/mmooyyii/malguem/releases/latest/download/",
]

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
if not sys.stdout.isatty():
    GREEN = RED = YELLOW = DIM = RESET = ""


def load_sources():
    """从 AppUpdater.java 的 SOURCES 数组里抠出源列表。"""
    try:
        with open(JAVA, encoding="utf-8") as f:
            text = f.read()
        body = re.search(r"SOURCES\s*=\s*\{(.*?)\}\s*;", text, re.S).group(1)
        urls = re.findall(r'"(https?://[^"]+)"', body)
        return urls or FALLBACK
    except Exception as e:
        print(f"{YELLOW}读不到 {JAVA} ({e}), 用内置列表{RESET}")
        return FALLBACK


# Clash/Surge 等本地代理的 fake-ip 默认网段: 看到它就说明流量还在走代理, 测的不是裸连
FAKE_IP = ipaddress.ip_network("198.18.0.0/15")


def ip_warning(ip):
    """按解析结果给出警告: 代理 fake-ip, 或 DNS 污染的典型保留地址。"""
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return ""
    if a in FAKE_IP:
        return "⚠ 代理 fake-ip 网段 — 代理还开着, 这不是裸连结果"
    if a.is_loopback or a.is_unspecified:
        return "⚠ 解析到回环/0.0.0.0 — DNS 被污染或被 hosts 屏蔽"
    if a.is_private or a.is_reserved:
        return "⚠ 解析到私有/保留地址 — 疑似 DNS 污染或本地劫持"
    return ""


def probe(base, want_apk):
    """对单个源做分段探测, 返回 (base, 各阶段结果 dict)。"""
    r = {"dns": None, "tcp": None, "tls": None, "http": None,
         "ips": [], "ms": {}, "note": "", "warn": ""}
    host = urlsplit(base).hostname
    port = urlsplit(base).port or 443

    # 1) DNS
    t = time.time()
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
        r["ips"] = sorted({i[4][0] for i in infos})
        r["ms"]["dns"] = (time.time() - t) * 1000
        r["dns"] = True
        # 警告单独存, 不能被后面的 note 覆盖掉 —— 它往往才是真正的线索
        for ip in r["ips"]:
            w = ip_warning(ip)
            if w:
                r["warn"] = w
                break
    except Exception as e:
        r["dns"] = False
        r["note"] = f"DNS 失败: {e}"
        return base, r

    ip = r["ips"][0]

    # 2) TCP —— 用 5s 连接超时, 和客户端一致
    t = time.time()
    try:
        sock = socket.create_connection((ip, port), timeout=CONNECT_TIMEOUT)
        r["ms"]["tcp"] = (time.time() - t) * 1000
        r["tcp"] = True
    except Exception as e:
        r["tcp"] = False
        r["note"] = f"TCP 连不上 {ip}:{port} ({type(e).__name__})"
        return base, r

    # 3) TLS —— 带 SNI, SNI 阻断会在这里被 RST
    t = time.time()
    try:
        ctx = ssl.create_default_context()
        sock.settimeout(CONNECT_TIMEOUT)
        with ctx.wrap_socket(sock, server_hostname=host):
            r["ms"]["tls"] = (time.time() - t) * 1000
            r["tls"] = True
    except Exception as e:
        r["tls"] = False
        r["note"] = f"TLS 握手失败 ({type(e).__name__}: {e}), 疑似 SNI 阻断"
        try:
            sock.close()
        except Exception:
            pass
        return base, r

    # 4) HTTP: version.json
    t = time.time()
    try:
        body, code, final = http_get(base + "version.json")
        r["ms"]["http"] = (time.time() - t) * 1000
        r["http"] = code
        text = body.decode("utf-8", "replace").strip()
        if code == 200 and '"tag"' in text:
            r["note"] = text[:60]
        elif code == 200:
            r["note"] = f"200 但不像 version.json: {text[:40]!r}"
            r["http"] = "200?"
        else:
            r["note"] = f"HTTP {code}"
        if final and urlsplit(final).hostname != host:
            r["note"] += f" (重定向到 {urlsplit(final).hostname})"
    except urllib.error.HTTPError as e:
        r["ms"]["http"] = (time.time() - t) * 1000
        r["http"] = e.code
        r["note"] = f"HTTP {e.code}" + (" — release 分支还没这个文件?" if e.code == 404 else "")
        return base, r
    except Exception as e:
        r["ms"]["http"] = (time.time() - t) * 1000
        r["http"] = False
        r["note"] = f"HTTP 失败 ({type(e).__name__}: {e})"
        return base, r

    # 5) 可选: apk 前 64KB, 验 PK 魔数 (电视端也做同样校验, 防镜像返回 HTML 错误页)
    if want_apk and r["http"] == 200:
        t = time.time()
        try:
            head, code, _ = http_get(base + "malguem-tv.apk", limit=64 * 1024)
            ok = head[:4] == b"PK\x03\x04"
            r["ms"]["apk"] = (time.time() - t) * 1000
            r["note"] += f" | apk {code} " + ("PK ✓" if ok else f"非 apk: {head[:20]!r}")
        except urllib.error.HTTPError as e:
            r["note"] += f" | apk HTTP {e.code}"
        except Exception as e:
            r["note"] += f" | apk 失败 ({type(e).__name__})"

    return base, r


def http_get(url, limit=None):
    """GET, 返回 (body, status, final_url)。limit 非空时只读前 limit 字节就断开。"""
    req = urllib.request.Request(url, headers={
        # 尽量贴近电视端 okhttp 的请求, 有些镜像会按 UA 拦
        "User-Agent": "okhttp/4.12.0",
        "Accept": "*/*",
    })
    with urllib.request.urlopen(req, timeout=READ_TIMEOUT) as resp:
        data = resp.read(limit) if limit else resp.read()
        return data, resp.status, resp.geturl()


def main():
    ap = argparse.ArgumentParser(description="测 malguem 更新源可达性")
    ap.add_argument("--apk", action="store_true", help="顺带测 malguem-tv.apk 前 64KB")
    ap.add_argument("-j", "--jobs", type=int, default=4, help="并发数, 1 为串行 (默认 4)")
    ap.add_argument("--proxy", action="store_true", help="走系统/环境代理做对照 (默认不走)")
    args = ap.parse_args()

    if args.proxy:
        opener = urllib.request.build_opener()
        print(f"{YELLOW}走代理模式 (对照用){RESET}")
    else:
        # 彻底绕开环境变量与 macOS 系统代理
        for k in list(os.environ):
            if k.lower().endswith("_proxy"):
                os.environ.pop(k)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    urllib.request.install_opener(opener)

    sources = load_sources()
    print(f"{DIM}共 {len(sources)} 个源, connect {CONNECT_TIMEOUT}s / read {READ_TIMEOUT}s"
          f"{' , 含 apk 测试' if args.apk else ''}{RESET}\n")

    results = []
    if args.jobs <= 1:
        for s in sources:
            results.append(probe(s, args.apk))
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as ex:
            futs = [ex.submit(probe, s, args.apk) for s in sources]
            done = {f.result()[0]: f.result()[1] for f in concurrent.futures.as_completed(futs)}
        results = [(s, done[s]) for s in sources]  # 保持 java 里的顺序

    ok = []
    for i, (base, r) in enumerate(results):
        good = r["http"] == 200
        if good:
            ok.append(base)
        mark = f"{GREEN}✓{RESET}" if good else f"{RED}✗{RESET}"
        stages = []
        for key, label in (("dns", "DNS"), ("tcp", "TCP"), ("tls", "TLS"), ("http", "HTTP")):
            v = r[key]
            if v is None:
                stages.append(f"{DIM}{label}-{RESET}")
            elif v is False:
                stages.append(f"{RED}{label}✗{RESET}")
            else:
                ms = r["ms"].get(key)
                stages.append(f"{GREEN}{label}{RESET}"
                              + (f"{DIM}{ms:.0f}ms{RESET}" if ms else ""))
        print(f"{mark} [{i}] {base}")
        print(f"    {' '.join(stages)}")
        if r["ips"]:
            print(f"    {DIM}ip: {', '.join(r['ips'][:3])}{RESET}")
        if r["warn"]:
            print(f"    {YELLOW}{r['warn']}{RESET}")
        if r["note"]:
            color = GREEN if good else YELLOW
            print(f"    {color}{r['note']}{RESET}")
        print()

    print("=" * 70)
    if not args.proxy and any(r["warn"].startswith("⚠ 代理") for _, r in results):
        print(f"{YELLOW}注意: 解析到代理 fake-ip, 说明流量仍走代理 —— "
              f"这一轮结果不代表裸连。请彻底关掉 Clash/Surge 的系统代理与 TUN/增强模式后重测。{RESET}")
    if ok:
        print(f"{GREEN}可用 {len(ok)}/{len(results)} 个:{RESET}")
        for s in ok:
            print(f"  {s}")
        print(f"\n{DIM}电视端从上往下试, 只要有一个通就能更新。"
              f"若电视仍失败, 多半是电视的 DNS/网络和这台机器不同。{RESET}")
    else:
        print(f"{RED}全军覆没 —— 这个网络下电视一定更新不了。{RESET}")
        print(f"{DIM}长按首页「检查更新」可填自定义源 (支持 http://user:pass@host/path/)。{RESET}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
