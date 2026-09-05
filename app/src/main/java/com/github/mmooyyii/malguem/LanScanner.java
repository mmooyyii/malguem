package com.github.mmooyyii.malguem;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// 局域网嗅探: 对本机所在 /24 网段逐个 IP 探测指定 TCP 端口 (alist 5244 / SMB 445).
// 端口能连上即视为候选数据源, 是不是真服务留给添加后的实际访问去校验
public class LanScanner {

    public static final int PORT_ALIST = 5244;
    public static final int PORT_SMB = 445;

    private static final int CONNECT_TIMEOUT_MS = 400;
    private static final int THREADS = 64;

    public static class Hit {
        public final String ip;
        public final int port;

        public Hit(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    // 本机 site-local IPv4; 电视可能走以太网而不是 WiFi, 所以枚举 NetworkInterface 而不是查 WifiManager
    public static String localIp() {
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    // 阻塞扫描整个 /24 网段, 必须在后台线程调用; 返回按 IP、端口排序的命中列表
    public static List<Hit> scan(String localIp, int[] ports) {
        var prefix = localIp.substring(0, localIp.lastIndexOf('.') + 1);
        var hits = Collections.synchronizedList(new ArrayList<Hit>());
        var pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 1; i <= 254; i++) {
            final var ip = prefix + i;
            for (var port : ports) {
                pool.execute(() -> {
                    try (var s = new Socket()) {
                        s.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
                        hits.add(new Hit(ip, port));
                    } catch (Exception ignore) {
                    }
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var out = new ArrayList<>(hits);
        out.sort(Comparator
                .comparingInt((Hit h) -> Integer.parseInt(h.ip.substring(h.ip.lastIndexOf('.') + 1)))
                .thenComparingInt(h -> h.port));
        return out;
    }
}
