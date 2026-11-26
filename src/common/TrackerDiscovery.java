package common;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tìm Tracker Server tự động trong mạng LAN bằng UDP Broadcast.
 * Client gửi gói {@link Protocol#DISCOVERY_REQUEST} tới địa chỉ broadcast của mọi card mạng đang hoạt động
 * (và 255.255.255.255, 127.0.0.1); Tracker trả lời kèm cổng TCP đang lắng nghe.
 */
public final class TrackerDiscovery {
    private TrackerDiscovery() { }

    /** Địa chỉ một Tracker tìm thấy trong LAN. */
    public static final class TrackerEndpoint {
        private final String ip;
        private final int port;

        public TrackerEndpoint(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() { return ip; }
        public int getPort() { return port; }

        @Override
        public String toString() { return ip + ":" + port; }
    }

    /** Quét mạng LAN với cổng và thời gian chờ mặc định trong {@link Protocol}. */
    public static List<TrackerEndpoint> discover() throws IOException {
        return discover(Protocol.DISCOVERY_PORT, Protocol.DISCOVERY_TIMEOUT_MS);
    }

    /**
     * Gửi gói UDP broadcast tới cổng {@code discoveryPort} và gom phản hồi trong {@code timeoutMs} mili-giây.
     * Ưu tiên IP LAN thực (không phải loopback) để có thể dùng chung cho demo nhiều máy.
     */
    public static List<TrackerEndpoint> discover(int discoveryPort, int timeoutMs) throws IOException {
        byte[] reqBytes = Protocol.DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
        Set<InetAddress> targets = collectBroadcastTargets();

        Map<String, TrackerEndpoint> found = new LinkedHashMap<>();
        Set<Integer> nonLoopbackPorts = new HashSet<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (InetAddress target : targets) {
                try {
                    socket.send(new DatagramPacket(reqBytes, reqBytes.length, target, discoveryPort));
                } catch (IOException ignored) {
                    // Một card mạng không gửi được broadcast thì bỏ qua, vẫn thử các đích còn lại
                }
            }

            long deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
            byte[] buf = new byte[256];
            while (true) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) break;
                socket.setSoTimeout(remaining);
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(resp);
                } catch (SocketTimeoutException e) {
                    break;
                }
                int tcpPort = parseResponsePort(resp);
                if (tcpPort < 1 || tcpPort > 65535) continue;

                InetAddress addr = resp.getAddress();
                String ip = addr.getHostAddress();
                if (!addr.isLoopbackAddress()) nonLoopbackPorts.add(tcpPort);
                found.putIfAbsent(ip + ":" + tcpPort, new TrackerEndpoint(ip, tcpPort));
            }
        }

        // Nếu Tracker trên chính máy này trả lời qua CẢ IP LAN (192.168.x.x) và 127.0.0.1 ở cùng cổng TCP,
        // ưu tiên giữ IP LAN để demo nhiều máy không bị dính loopback.
        List<TrackerEndpoint> lan = new ArrayList<>();
        List<TrackerEndpoint> loopback = new ArrayList<>();
        for (TrackerEndpoint ep : found.values()) {
            if (isLoopbackIp(ep.getIp())) {
                if (!nonLoopbackPorts.contains(ep.getPort())) loopback.add(ep);
            } else {
                lan.add(ep);
            }
        }
        lan.addAll(loopback);
        return lan;
    }

    private static Set<InetAddress> collectBroadcastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics != null) {
                for (NetworkInterface nic : Collections.list(nics)) {
                    if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                    for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                        InetAddress bcast = ia.getBroadcast();
                        if (bcast != null) targets.add(bcast);
                    }
                }
            }
        } catch (IOException ignored) {
            // Không liệt kê được NIC thì vẫn gửi tới 255.255.255.255 và 127.0.0.1
        }
        try { targets.add(InetAddress.getByName("255.255.255.255")); } catch (IOException ignored) { }
        try { targets.add(InetAddress.getByName("127.0.0.1")); } catch (IOException ignored) { }
        return targets;
    }

    private static int parseResponsePort(DatagramPacket resp) {
        String msg = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8).trim();
        if (!msg.startsWith(Protocol.DISCOVERY_RESPONSE_PREFIX)) return -1;
        String portStr = msg.substring(Protocol.DISCOVERY_RESPONSE_PREFIX.length()).trim();
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isLoopbackIp(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
