package server;

import common.Packet;
import common.PacketIO;
import common.PacketType;
import common.Protocol;
import common.PeerInfo;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Tracker Server: CHỈ quản lý "ai đang online ở IP:Port nào" (trên RAM) và broadcast danh sách khi có thay đổi.
 * KHÔNG trung chuyển tin nhắn hay file - các thứ đó đi thẳng giữa hai Peer.
 *
 * Mỗi Peer giữ MỘT kết nối TCP bền vững tới Tracker. Kết nối đóng (thoát bình thường hay tắt đột ngột)
 * = Peer offline, nên Tracker biết ngay mà không cần Peer báo.
 */
public class TrackerServer {

    public static final int DEFAULT_PORT = 9000;
    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_. -]{1,20}$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int port;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();   // username -> phiên
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public TrackerServer(int port) { this.port = port; }

    /** Mở cổng và bắt đầu nhận kết nối ở luồng riêng (không chặn). */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        log("Tracker đang chạy, lắng nghe cổng " + serverSocket.getLocalPort());
        Thread acceptThread = new Thread(this::acceptLoop, "tracker-accept");
        acceptThread.start();
    }

    public int getPort() { return serverSocket == null ? port : serverSocket.getLocalPort(); }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        // Lấy ra rồi xóa danh sách TRƯỚC khi đóng socket: nếu không, handler của Peer bị đóng trước sẽ
        // broadcast "danh sách đã đổi" cho các Peer còn lại, làm họ tưởng bạn bè đã offline.
        List<Session> all = new ArrayList<>(sessions.values());
        sessions.clear();
        for (Session s : all) s.close();
        pool.shutdownNow();
        log("Tracker đã dừng.");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(() -> handleClient(client));   // mỗi Peer một luồng
            } catch (SocketException e) {
                break;                                      // serverSocket đã đóng -> thoát vòng lặp
            } catch (IOException e) {
                log("Lỗi accept: " + e.getMessage());
            } catch (RuntimeException e) {
                break;                                      // pool đã đóng
            }
        }
    }

    private void handleClient(Socket socket) {
        Session session = null;
        String name = null;
        String nameKey = null;
        try {
            socket.setKeepAlive(true);
            socket.setSoTimeout(10_000);                    // phải đăng ký xong trong 10 giây, tránh kết nối "treo"
            ObjectOutputStream out = PacketIO.openOutput(socket);
            ObjectInputStream in = PacketIO.openInput(socket);

            Packet first = PacketIO.receive(in);
            String failReason = validate(first);
            if (failReason != null) {
                PacketIO.send(out, Packet.registerFail(failReason));
                return;
            }
            name = first.getSender().trim();
            nameKey = name.toLowerCase(Locale.ROOT);                // không cho trùng tên chỉ khác hoa/thường
            String ip = socket.getInetAddress().getHostAddress();   // IP lấy từ kết nối thật, không tin client tự khai
            session = new Session(socket, out, new PeerInfo(name, ip, first.getPort()));

            // Giữ khóa trên session trong lúc thêm vào map + gửi REGISTER_OK để broadcastOnlineList()
            // từ luồng khác không thể chen gói ONLINE_LIST lên trước REGISTER_OK.
            synchronized (session) {
                if (sessions.putIfAbsent(nameKey, session) != null) { // kiểm tra + thêm là một thao tác nguyên tử
                    session = null;                                   // không phải phiên của mình -> không xóa ở finally
                    PacketIO.send(out, Packet.registerFail("Username \"" + name + "\" đã có người dùng. Hãy chọn tên khác."));
                    return;
                }
                socket.setSoTimeout(Protocol.HEARTBEAT_TIMEOUT_MS);   // đăng ký xong: im lặng quá lâu = Peer đã chết
                session.send(Packet.registerOk(ip, snapshot()));
            }
            log("+ " + name + " online tại " + ip + ":" + first.getPort() + "  (đang online: " + sessions.size() + ")");
            broadcastOnlineList();

            while (running) {                                       // chờ cho tới khi Peer thoát / mất kết nối
                Packet p = PacketIO.receive(in);
                if (p.getType() == PacketType.UNREGISTER) break;
                if (p.getType() == PacketType.PING) session.send(Packet.pong());   // heartbeat: trả lời để Peer biết Tracker còn sống
            }
        } catch (EOFException | SocketException e) {
            // Peer tắt đột ngột / đóng kết nối: bình thường
        } catch (SocketTimeoutException e) {
            // mất heartbeat: máy Peer mất mạng / treo mà không đóng kết nối
            log("! " + name + " không gửi heartbeat trong " + Protocol.HEARTBEAT_TIMEOUT_MS / 1000.0 + " giây, coi như offline");
        } catch (IOException e) {
            log("Lỗi với " + (name != null ? name : socket.getRemoteSocketAddress()) + ": " + e.getMessage());
        } finally {
            if (session != null && sessions.remove(nameKey, session)) { // chỉ xóa (và thông báo) nếu vẫn là phiên của mình
                log("- " + name + " offline  (đang online: " + sessions.size() + ")");
                broadcastOnlineList();
            }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Trả về lý do lỗi, hoặc null nếu gói đăng ký hợp lệ. */
    private static String validate(Packet p) {
        if (p.getType() != PacketType.REGISTER) return "Gói đăng ký không hợp lệ.";
        String name = p.getSender() == null ? "" : p.getSender().trim();
        if (!USERNAME.matcher(name).matches()) return "Username phải gồm 1-20 ký tự (chữ, số, khoảng trắng, _ . -).";
        if (p.getPort() < 1 || p.getPort() > 65535) return "Cổng P2P không hợp lệ.";
        return null;
    }

    private List<PeerInfo> snapshot() {
        List<PeerInfo> list = new ArrayList<>();
        for (Session s : sessions.values()) list.add(s.info);
        list.sort((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()));
        return list;
    }

    /** Gửi danh sách mới cho TẤT CẢ Peer. Peer nào gửi lỗi thì đóng kết nối, luồng của nó sẽ tự dọn dẹp. */
    private void broadcastOnlineList() {
        Packet p = Packet.onlineList(snapshot());
        for (Session s : sessions.values()) {
            try {
                s.send(p);
            } catch (IOException e) {
                s.close();
            }
        }
    }

    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }

    /** Một Peer đang kết nối. send() có synchronized vì broadcast và luồng riêng có thể ghi cùng lúc. */
    private static final class Session {
        final Socket socket;
        final ObjectOutputStream out;
        final PeerInfo info;

        Session(Socket socket, ObjectOutputStream out, PeerInfo info) {
            this.socket = socket;
            this.out = out;
            this.info = info;
        }

        synchronized void send(Packet p) throws IOException { PacketIO.send(out, p); }

        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }

    // ------------------------------------------------------------------
    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        if (args.length > 0) port = Integer.parseInt(args[0]);
        new TrackerServer(port).start();

        // In các IP trong mạng LAN để dễ điền vào ô "Tracker IP" khi demo hai máy
        List<String> ips = new ArrayList<>();
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface nic : Collections.list(nics)) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address) ips.add(a.getHostAddress() + "  (" + nic.getDisplayName() + ")");
            }
        }
        log("IP của máy này để Peer nhập vào ô Tracker IP: " + (ips.isEmpty() ? "(không tìm thấy)" : ips));
    }
}
