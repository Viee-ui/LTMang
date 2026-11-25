package client;

import client.ui.ChatUIListener;
import client.ui.FileOfferResponder;
import client.ui.PeerService;
import common.Packet;
import common.PacketIO;
import common.PacketType;
import common.PeerInfo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lõi mạng của Peer: vừa là TCP Server (nhận kết nối từ Peer khác) vừa là TCP Client
 * (kết nối Tracker và kết nối thẳng tới Peer khác).
 *
 * Các luồng:
 *  - "acceptLoop":   chờ Peer khác kết nối vào ServerSocket, mỗi kết nối giao cho một luồng handleIncoming.
 *  - "trackerLoop":  đọc danh sách online do Tracker gửi.
 *  - handleIncoming: đọc tin nhắn / đề nghị gửi file từ một Peer.
 * Không có dòng nào đụng tới Swing: mọi thứ báo lên giao diện đều qua ChatUIListener.
 */
public class PeerNode implements PeerService {

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int REGISTER_TIMEOUT_MS = 5_000;
    private static final int ACK_TIMEOUT_MS = 5_000;
    private static final int FILE_DECISION_TIMEOUT_S = 60;       // thời gian người nhận được phép suy nghĩ
    private static final int FILE_IO_TIMEOUT_MS = 30_000;        // im lặng quá lâu khi đang truyền = coi như đứt
    private static final int CHUNK_SIZE = 16 * 1024;

    private volatile ChatUIListener ui;
    private volatile String username;
    private volatile String localIp = "?";
    private volatile int p2pPort;
    private volatile boolean running;

    private ServerSocket serverSocket;
    private Socket trackerSocket;
    private ObjectOutputStream trackerOut;
    private ExecutorService pool;

    /** username -> địa chỉ P2P (cập nhật mỗi khi Tracker gửi danh sách mới). */
    private final Map<String, PeerInfo> onlinePeers = new ConcurrentHashMap<>();
    /** Kết nối tin nhắn bền vững tới từng Peer (tái sử dụng, không mở lại cho mỗi tin). */
    private final Map<String, PeerConnection> connections = new ConcurrentHashMap<>();

    // =====================================================================
    //  PeerService
    // =====================================================================

    @Override
    public void setListener(ChatUIListener listener) { this.ui = listener; }

    @Override public String getUsername() { return username; }
    @Override public String getLocalIp() { return localIp; }
    @Override public int getP2pPort() { return p2pPort; }

    @Override
    public synchronized void start(String username, String trackerHost, int trackerPort, int requestedPort)
            throws IOException {
        if (running) throw new IllegalStateException("PeerNode đã chạy rồi.");
        this.username = username;
        try {
            // 1. Mở ServerSocket để các Peer khác kết nối vào (cổng 0 = hệ điều hành chọn cổng trống)
            serverSocket = new ServerSocket(requestedPort);
            this.p2pPort = serverSocket.getLocalPort();

            // 2. Kết nối Tracker (có timeout để không treo giao diện khi IP sai)
            trackerSocket = new Socket();
            trackerSocket.setKeepAlive(true);
            trackerSocket.connect(new InetSocketAddress(trackerHost, trackerPort), CONNECT_TIMEOUT_MS);
            trackerSocket.setSoTimeout(REGISTER_TIMEOUT_MS);
            trackerOut = PacketIO.openOutput(trackerSocket);
            ObjectInputStream trackerIn = PacketIO.openInput(trackerSocket);

            // 3. Đăng ký và chờ Tracker trả lời
            PacketIO.send(trackerOut, Packet.register(username, p2pPort));
            Packet reply = PacketIO.receive(trackerIn);
            if (reply.getType() == PacketType.REGISTER_FAIL) throw new IOException(reply.getContent());
            if (reply.getType() != PacketType.REGISTER_OK) throw new IOException("Tracker trả lời không hợp lệ.");
            trackerSocket.setSoTimeout(0);
            localIp = reply.getContent();

            // 4. Chạy các luồng nền
            running = true;
            pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "peer-net");
                t.setDaemon(true);
                return t;
            });
            pool.execute(this::acceptLoop);
            pool.execute(() -> trackerLoop(trackerIn));
            handleOnlineList(reply.getPeers());
        } catch (IOException | RuntimeException e) {
            closeAll();            // thất bại giữa chừng: dọn sạch để lần đăng nhập sau không bị "port đang bận"
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;           // đặt TRƯỚC khi đóng socket để các luồng nền biết đây là thoát chủ động
        if (wasRunning && trackerOut != null) {
            try {
                PacketIO.send(trackerOut, Packet.unregister(username));
            } catch (IOException ignored) {
                // Tracker đã đứt thì thôi
            }
        }
        closeAll();
    }

    private void closeAll() {
        running = false;
        closeQuietly(trackerSocket);
        closeQuietly(serverSocket);
        for (PeerConnection c : connections.values()) c.close();
        connections.clear();
        onlinePeers.clear();
        if (pool != null) pool.shutdownNow();
        trackerSocket = null;
        serverSocket = null;
        trackerOut = null;
    }

    // =====================================================================
    //  TRACKER
    // =====================================================================

    private void trackerLoop(ObjectInputStream in) {
        try {
            while (running) {
                Packet p = PacketIO.receive(in);
                if (p.getType() == PacketType.ONLINE_LIST) handleOnlineList(p.getPeers());
            }
        } catch (IOException e) {
            if (running) {         // chỉ báo khi KHÔNG phải do mình chủ động thoát
                ChatUIListener l = ui;
                if (l != null) l.onTrackerDisconnected("Tracker đã đóng kết nối (" + explain(e) + ").");
            }
        }
    }

    private void handleOnlineList(List<PeerInfo> list) {
        Map<String, PeerInfo> fresh = new HashMap<>();
        for (PeerInfo p : list) fresh.put(p.getUsername(), p);

        onlinePeers.keySet().retainAll(fresh.keySet());
        onlinePeers.putAll(fresh);

        // Đóng kết nối cũ tới Peer đã offline hoặc đã đổi địa chỉ (đăng nhập lại ở cổng khác)
        for (Map.Entry<String, PeerConnection> e : connections.entrySet()) {
            PeerInfo now = fresh.get(e.getKey());
            if (now == null || !now.getAddress().equals(e.getValue().address)) {
                connections.remove(e.getKey(), e.getValue());
                e.getValue().close();
            }
        }
        ChatUIListener l = ui;
        if (l != null) l.onOnlineListUpdated(new ArrayList<>(list));
    }

    // =====================================================================
    //  PHÍA NHẬN (TCP Server)
    // =====================================================================

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                pool.execute(() -> handleIncoming(s));
            } catch (SocketException e) {
                break;                         // ServerSocket đã đóng
            } catch (IOException e) {
                // lỗi accept lẻ tẻ: bỏ qua, tiếp tục nghe
            } catch (RuntimeException e) {
                break;                         // pool đã đóng
            }
        }
    }

    /** Xử lý một kết nối đến: lặp đọc gói tin cho tới khi đối phương đóng kết nối. */
    private void handleIncoming(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5_000);             // kết nối "câm" không gửi header thì bỏ
            ObjectOutputStream out = PacketIO.openOutput(s);
            ObjectInputStream in = PacketIO.openInput(s);
            s.setSoTimeout(0);

            while (running) {
                Packet p = PacketIO.receive(in);
                switch (p.getType()) {
                    case MESSAGE:
                        if (p.getSender() != null && p.getContent() != null) {
                            ChatUIListener l = ui;
                            if (l != null) l.onMessageReceived(p.getSender(), p.getContent());
                        }
                        PacketIO.send(out, Packet.messageAck());
                        break;
                    case FILE_OFFER:
                        receiveFile(p, s, in, out);
                        return;                // mỗi file dùng một kết nối riêng, xong thì đóng
                    default:
                        break;                 // gói lạ: bỏ qua
                }
            }
        } catch (EOFException | SocketException e) {
            // đối phương đóng kết nối hoặc tắt đột ngột: bình thường
        } catch (IOException e) {
            // gói tin sai / bị bộ lọc chặn: đóng kết nối này, các kết nối khác không bị ảnh hưởng
        }
    }

    private void receiveFile(Packet offer, Socket socket, ObjectInputStream in, ObjectOutputStream out)
            throws IOException {
        final String from = offer.getSender() == null ? "?" : offer.getSender();
        final String name = new File(offer.getFileName() == null ? "" : offer.getFileName()).getName();
        final long size = offer.getFileSize();
        ChatUIListener l = ui;
        if (name.isEmpty() || size < 0 || l == null) {
            PacketIO.send(out, Packet.fileReject("Yêu cầu gửi file không hợp lệ."));
            return;
        }

        // Hỏi giao diện và CHỜ câu trả lời (luồng này chặn, nhưng EDT thì không)
        CompletableFuture<File> decision = new CompletableFuture<>();
        l.onFileOffered(from, name, size, new FileOfferResponder() {
            @Override public void accept(File saveTo) { decision.complete(saveTo); }
            @Override public void reject() { decision.complete(null); }
        });
        File target;
        try {
            target = decision.get(FILE_DECISION_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            PacketIO.send(out, Packet.fileReject("Hết thời gian chờ đối phương trả lời."));
            return;
        } catch (Exception e) {
            PacketIO.send(out, Packet.fileReject("Đối phương không thể nhận file."));
            return;
        }
        if (target == null) {
            PacketIO.send(out, Packet.fileReject("Đối phương đã từ chối nhận file."));
            return;
        }

        PacketIO.send(out, Packet.fileAccept());
        socket.setSoTimeout(FILE_IO_TIMEOUT_MS);

        // Ghi vào file tạm .part, đủ dữ liệu mới đổi tên -> không bao giờ để lại file hỏng với tên thật
        File tmp = new File(target.getPath() + ".part");
        long received = 0;
        int lastPct = -1;
        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp))) {
            if (size == 0) report(from, name, 100, false);
            while (true) {
                Packet p = PacketIO.receive(in);
                if (p.getType() == PacketType.FILE_END) break;
                if (p.getType() != PacketType.FILE_CHUNK || p.getData() == null) {
                    throw new IOException("Nhận được gói tin không hợp lệ giữa chừng.");
                }
                received += p.getData().length;
                if (received > size) throw new IOException("Dữ liệu nhiều hơn dung lượng đã khai báo.");
                fos.write(p.getData());
                int pct = size == 0 ? 100 : (int) (received * 100 / size);
                if (pct != lastPct) {
                    lastPct = pct;
                    report(from, name, pct, false);
                }
            }
            if (received != size) throw new IOException("Thiếu dữ liệu (" + received + "/" + size + " byte).");
        } catch (IOException e) {
            tmp.delete();
            String reason = (e instanceof EOFException || e instanceof SocketException)
                    ? "Đối phương ngắt kết nối giữa chừng." : explain(e);
            l.onFileFailed(from, name, reason);
            try { PacketIO.send(out, Packet.fileError(reason)); } catch (IOException ignored) { }
            return;
        }

        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmp.delete();
            l.onFileFailed(from, name, "Không lưu được file: " + explain(e));
            try { PacketIO.send(out, Packet.fileError("Người nhận không lưu được file.")); } catch (IOException ignored) { }
            return;
        }
        PacketIO.send(out, Packet.fileDone());
        l.onFileReceived(from, target);
    }

    // =====================================================================
    //  PHÍA GỬI (TCP Client)
    // =====================================================================

    @Override
    public void sendMessage(String toUser, String content) throws IOException {
        PeerInfo target = requireOnline(toUser);
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            PeerConnection c = connections.get(toUser);
            boolean reused = (c != null);
            try {
                if (c == null) {
                    c = new PeerConnection(target);
                    connections.put(toUser, c);
                }
                c.sendMessage(content);
                return;
            } catch (SocketTimeoutException e) {
                drop(toUser, c);
                throw new IOException("Đối phương không phản hồi (hết thời gian chờ).", e);
            } catch (IOException e) {
                drop(toUser, c);
                last = e;
                if (!reused) break;      // kết nối MỚI mà vẫn lỗi -> bỏ cuộc; kết nối CŨ đã chết -> thử lại 1 lần
            }
        }
        throw new IOException("Không gửi được tới " + toUser + " (" + target.getAddress() + "): " + explain(last), last);
    }

    @Override
    public void sendFile(String toUser, File file) throws IOException {
        PeerInfo target = requireOnline(toUser);
        if (!file.isFile() || !file.canRead()) throw new IOException("Không đọc được file \"" + file.getName() + "\".");
        final String name = file.getName();
        final long size = file.length();

        try (Socket s = new Socket()) {                       // kết nối RIÊNG cho file
            try {
                s.connect(new InetSocketAddress(target.getIp(), target.getPort()), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                throw new IOException("Không kết nối được tới " + toUser + " (" + target.getAddress() + "): " + explain(e), e);
            }
            s.setSoTimeout((FILE_DECISION_TIMEOUT_S + 10) * 1000);   // chờ người nhận bấm Có/Không
            ObjectOutputStream out = PacketIO.openOutput(s);
            ObjectInputStream in = PacketIO.openInput(s);

            PacketIO.send(out, Packet.fileOffer(username, name, size));
            Packet reply = PacketIO.receive(in);
            if (reply.getType() == PacketType.FILE_REJECT) {
                throw new IOException(reply.getContent() != null ? reply.getContent() : "Đối phương đã từ chối nhận file.");
            }
            if (reply.getType() != PacketType.FILE_ACCEPT) throw new IOException("Đối phương trả lời không hợp lệ.");

            s.setSoTimeout(FILE_IO_TIMEOUT_MS);
            report(toUser, name, 0, true);
            try (InputStream fis = new BufferedInputStream(new FileInputStream(file), CHUNK_SIZE)) {
                byte[] buf = new byte[CHUNK_SIZE];
                long sent = 0;
                int lastPct = 0;
                int n;
                while ((n = fis.read(buf)) != -1) {
                    PacketIO.send(out, Packet.fileChunk(n == buf.length ? buf : Arrays.copyOf(buf, n)));
                    sent += n;
                    int pct = size == 0 ? 100 : (int) (sent * 100 / size);
                    if (pct != lastPct) {
                        lastPct = pct;
                        report(toUser, name, pct, true);
                    }
                }
            }
            PacketIO.send(out, Packet.fileEnd());

            Packet done = PacketIO.receive(in);               // chờ người nhận xác nhận ĐÃ LƯU XONG
            if (done.getType() == PacketType.FILE_ERROR) {
                throw new IOException("Đối phương báo lỗi: " + done.getContent());
            }
            if (done.getType() != PacketType.FILE_DONE) throw new IOException("Đối phương trả lời không hợp lệ.");
            report(toUser, name, 100, true);
        } catch (SocketTimeoutException e) {
            throw new IOException("Đối phương không phản hồi (hết thời gian chờ).", e);
        } catch (EOFException | SocketException e) {
            throw new IOException("Kết nối bị ngắt giữa chừng: " + explain(e), e);
        }
    }

    /** Kết nối tin nhắn bền vững tới một Peer. */
    private final class PeerConnection {
        final String address;
        final Socket socket;
        final ObjectOutputStream out;
        final ObjectInputStream in;

        PeerConnection(PeerInfo peer) throws IOException {
            address = peer.getAddress();
            socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);                   // tin nhắn nhỏ: gửi đi ngay, không gom gói
                socket.setSoTimeout(ACK_TIMEOUT_MS);
                out = PacketIO.openOutput(socket);
                in = PacketIO.openInput(socket);
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        /** Gửi và chờ ACK ở tầng ứng dụng: TCP ACK chỉ đảm bảo dữ liệu tới hệ điều hành bên kia, chưa chắc tới ứng dụng. */
        synchronized void sendMessage(String text) throws IOException {
            PacketIO.send(out, Packet.message(username, text));
            Packet ack = PacketIO.receive(in);
            if (ack.getType() != PacketType.MESSAGE_ACK) throw new IOException("Đối phương trả lời không hợp lệ.");
        }

        void close() { closeQuietly(socket); }
    }

    // =====================================================================
    //  TIỆN ÍCH
    // =====================================================================

    private PeerInfo requireOnline(String user) throws IOException {
        if (!running) throw new IOException("Chưa đăng nhập hoặc đã ngắt kết nối.");
        PeerInfo p = onlinePeers.get(user);
        if (p == null) throw new IOException(user + " không còn online.");
        return p;
    }

    private void drop(String user, PeerConnection c) {
        if (c != null) {
            connections.remove(user, c);
            c.close();
        }
    }

    private void report(String peer, String file, int pct, boolean sending) {
        ChatUIListener l = ui;
        if (l != null) l.onFileProgress(peer, file, pct, sending);
    }

    /** Đổi ngoại lệ kỹ thuật thành mô tả ngắn gọn dễ hiểu. */
    private static String explain(Throwable e) {
        if (e == null) return "lỗi không xác định";
        if (e instanceof ConnectException) return "kết nối bị từ chối hoặc máy kia không phản hồi (kiểm tra tường lửa)";
        if (e instanceof SocketTimeoutException) return "hết thời gian chờ";
        if (e instanceof EOFException) return "đối phương đã đóng kết nối";
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }
}
