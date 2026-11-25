import client.PeerNode;
import client.ui.*;
import common.PeerInfo;
import server.TrackerServer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class NetTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + name); if (ok) pass++; else fail++; }

    /** Listener ghi lại mọi callback để kiểm tra. */
    static class Rec implements ChatUIListener {
        volatile List<PeerInfo> online = new ArrayList<>();
        final BlockingQueue<String> msgs = new LinkedBlockingQueue<>();
        final BlockingQueue<File> received = new LinkedBlockingQueue<>();
        final BlockingQueue<String> failed = new LinkedBlockingQueue<>();
        final AtomicInteger progressCalls = new AtomicInteger();
        final AtomicInteger lastSendPct = new AtomicInteger(-1), lastRecvPct = new AtomicInteger(-1);
        final AtomicBoolean trackerLost = new AtomicBoolean();
        volatile File acceptTo;                      // null => từ chối
        volatile String offeredName; volatile long offeredSize;
        public void onOnlineListUpdated(List<PeerInfo> l) { online = l; }
        public void onMessageReceived(String f, String c) { msgs.add(f + ":" + c); }
        public void onFileOffered(String f, String n, long s, FileOfferResponder r) {
            offeredName = n; offeredSize = s;
            if (acceptTo != null) r.accept(acceptTo); else r.reject();
        }
        public void onFileProgress(String p, String n, int pct, boolean sending) {
            progressCalls.incrementAndGet(); (sending ? lastSendPct : lastRecvPct).set(pct);
        }
        public void onFileReceived(String f, File saved) { received.add(saved); }
        public void onFileFailed(String p, String n, String r) { failed.add(r); }
        public void onTrackerDisconnected(String r) { trackerLost.set(true); }
        boolean seen(String u) { for (PeerInfo p : online) if (p.getUsername().equals(u)) return true; return false; }
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(20); }
        return c.getAsBoolean();
    }
    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return HexFormat.of().formatHex(md.digest());
    }

    public static void main(String[] a) throws Exception {
        TrackerServer tracker = new TrackerServer(0);   // cổng trống
        tracker.start();
        int tp = tracker.getPort();

        // ---------- Đăng nhập ----------
        Rec ra = new Rec(), rb = new Rec();
        PeerNode A = new PeerNode(), B = new PeerNode();
        A.setListener(ra); B.setListener(rb);
        A.start("Alice", "127.0.0.1", tp, 0);
        check("A đăng nhập, có cổng P2P thật: " + A.getP2pPort(), A.getP2pPort() > 0);
        check("A nhận IP từ Tracker: " + A.getLocalIp(), A.getLocalIp().equals("127.0.0.1"));
        B.start("Bob", "127.0.0.1", tp, 0);
        check("A thấy B sau khi B đăng nhập (broadcast)", waitFor(() -> ra.seen("Bob"), 2000));
        check("B thấy A ngay khi đăng nhập", rb.seen("Alice"));

        // ---------- Trùng username ----------
        PeerNode dup = new PeerNode(); dup.setListener(new Rec());
        try { dup.start("Alice", "127.0.0.1", tp, 0); check("trùng username phải bị từ chối", false); }
        catch (IOException e) { check("trùng username bị từ chối: \"" + e.getMessage() + "\"", e.getMessage().contains("đã có người dùng")); }
        try { dup.start("alice", "127.0.0.1", tp, 0); check("trùng username khác hoa/thường phải bị từ chối", false); }
        catch (IOException e) { check("trùng username khác hoa/thường bị từ chối", e.getMessage().contains("đã có người dùng")); }
        try { dup.start("a<b>", "127.0.0.1", tp, 0); check("username sai định dạng phải bị từ chối", false); }
        catch (IOException e) { check("username sai định dạng bị Tracker từ chối", true); }
        // dup đã dọn sạch -> đăng nhập lại với tên khác được (không bị 'port bận')
        dup.start("Carol", "127.0.0.1", tp, 0);
        check("sau khi lỗi vẫn đăng nhập lại được", waitFor(() -> ra.seen("Carol"), 2000));
        dup.stop();
        check("Carol thoát -> A thấy Carol biến mất", waitFor(() -> !ra.seen("Carol"), 2000));

        // ---------- Đăng nhập đồng thời (kiểm tra race condition REGISTER_OK vs ONLINE_LIST) ----------
        int burst = 10;
        ExecutorService burstPool = Executors.newFixedThreadPool(burst);
        CountDownLatch ready = new CountDownLatch(burst);
        CountDownLatch go = new CountDownLatch(1);
        List<PeerNode> burstNodes = Collections.synchronizedList(new ArrayList<>());
        List<Future<Boolean>> burstFutures = new ArrayList<>();
        for (int idx = 0; idx < burst; idx++) {
            final String uname = "Burst" + idx;
            burstFutures.add(burstPool.submit(() -> {
                PeerNode node = new PeerNode();
                node.setListener(new Rec());
                ready.countDown();
                go.await();
                node.start(uname, "127.0.0.1", tp, 0);
                burstNodes.add(node);
                return true;
            }));
        }
        ready.await(3, TimeUnit.SECONDS);
        go.countDown();
        boolean allBurstOk = true;
        for (Future<Boolean> f : burstFutures) {
            try { if (!Boolean.TRUE.equals(f.get(5, TimeUnit.SECONDS))) allBurstOk = false; }
            catch (Exception e) { allBurstOk = false; }
        }
        check(burst + " peer đăng nhập cùng lúc đều thành công", allBurstOk && burstNodes.size() == burst);
        for (PeerNode n : burstNodes) n.stop();
        burstPool.shutdownNow();
        check("sau khi " + burst + " peer thoát, danh sách trở lại bình thường", waitFor(() -> !ra.seen("Burst0"), 3000));

        // ---------- Sai Tracker ----------
        PeerNode bad = new PeerNode(); bad.setListener(new Rec());
        try { bad.start("X", "127.0.0.1", 1, 0); check("Tracker không chạy phải ném lỗi", false); }
        catch (ConnectException e) { check("Tracker không chạy -> ConnectException", true); }
        catch (IOException e) { check("Tracker không chạy -> IOException (" + e.getClass().getSimpleName() + ")", true); }

        // ---------- Tin nhắn ----------
        A.sendMessage("Bob", "Xin chào Bob! Tiếng Việt có dấu ăâêô");
        check("B nhận đúng tin nhắn", "Alice:Xin chào Bob! Tiếng Việt có dấu ăâêô".equals(rb.msgs.poll(2, TimeUnit.SECONDS)));
        B.sendMessage("Alice", "chào lại");
        check("A nhận tin trả lời (hai chiều)", "Bob:chào lại".equals(ra.msgs.poll(2, TimeUnit.SECONDS)));
        for (int i = 0; i < 300; i++) A.sendMessage("Bob", "m" + i);
        boolean ordered = true;
        for (int i = 0; i < 300; i++) if (!("Alice:m" + i).equals(rb.msgs.poll(2, TimeUnit.SECONDS))) { ordered = false; break; }
        check("300 tin liên tiếp đến đủ và đúng thứ tự", ordered);
        // HTML/ký tự lạ đi nguyên vẹn
        A.sendMessage("Bob", "<b>x</b>\n& \"q\"");
        check("nội dung đặc biệt đi nguyên vẹn", "Alice:<b>x</b>\n& \"q\"".equals(rb.msgs.poll(2, TimeUnit.SECONDS)));

        // ---------- Gửi file: đồng ý ----------
        File src = File.createTempFile("src", ".bin");
        byte[] big = new byte[5 * 1024 * 1024 + 123];           // không chia hết cho 16KB
        new Random(7).nextBytes(big);
        Files.write(src.toPath(), big);
        File dst = new File(src.getParentFile(), "nhan_duoc.bin"); dst.delete();
        rb.acceptTo = dst;
        long t0 = System.currentTimeMillis();
        A.sendFile("Bob", src);
        long ms = System.currentTimeMillis() - t0;
        File got = rb.received.poll(3, TimeUnit.SECONDS);
        check("B nhận được file (" + ms + " ms cho 5MB)", got != null && got.equals(dst));
        check("file nhận về GIỐNG HỆT bản gốc (SHA-256)", got != null && sha(got).equals(sha(src)));
        check("không còn file .part", !new File(dst.getPath() + ".part").exists());
        check("B thấy đúng tên và dung lượng đề nghị", rb.offeredSize == big.length);
        check("tiến trình gửi chạy tới 100%", ra.lastSendPct.get() == 100);
        check("tiến trình nhận chạy tới 100%", rb.lastRecvPct.get() == 100);
        check("không bắn callback quá nhiều (" + ra.progressCalls.get() + "+" + rb.progressCalls.get() + ")", ra.progressCalls.get() + rb.progressCalls.get() < 300);

        // ---------- File rỗng ----------
        File empty = File.createTempFile("empty", ".txt");
        File emptyDst = new File(empty.getParentFile(), "rong_nhan.txt"); emptyDst.delete();
        rb.acceptTo = emptyDst;
        A.sendFile("Bob", empty);
        check("file rỗng gửi/nhận được", rb.received.poll(3, TimeUnit.SECONDS) != null && emptyDst.length() == 0);

        // ---------- Từ chối ----------
        rb.acceptTo = null;
        try { A.sendFile("Bob", src); check("từ chối phải ném lỗi", false); }
        catch (IOException e) { check("B từ chối -> A nhận lỗi: \"" + e.getMessage() + "\"", e.getMessage().contains("từ chối")); }

        // ---------- Nhận file nhưng gửi giữa chừng đứt ----------
        rb.acceptTo = new File(src.getParentFile(), "dut_giua_chung.bin");
        try (Socket s = new Socket("127.0.0.1", B.getP2pPort())) {
            ObjectOutputStream o = common.PacketIO.openOutput(s); ObjectInputStream i = common.PacketIO.openInput(s);
            common.PacketIO.send(o, common.Packet.fileOffer("Evil", "x.bin", 1_000_000));
            common.PacketIO.receive(i);                                           // FILE_ACCEPT
            common.PacketIO.send(o, common.Packet.fileChunk(new byte[1000]));
        }   // đóng socket đột ngột
        check("sender đứt giữa chừng -> B báo onFileFailed", rb.failed.poll(3, TimeUnit.SECONDS) != null);
        check("file dở dang bị xóa", !new File(rb.acceptTo.getPath() + ".part").exists() && !rb.acceptTo.exists());

        // ---------- Tên file độc hại ----------
        rb.acceptTo = null;
        try (Socket s = new Socket("127.0.0.1", B.getP2pPort())) {
            ObjectOutputStream o = common.PacketIO.openOutput(s); ObjectInputStream i = common.PacketIO.openInput(s);
            common.PacketIO.send(o, common.Packet.fileOffer("Evil", "../../etc/passwd", 10));
            common.PacketIO.receive(i);
        }
        check("tên file '../../etc/passwd' được rút gọn còn 'passwd'", "passwd".equals(rb.offeredName));
        try (Socket s = new Socket("127.0.0.1", B.getP2pPort())) {
            ObjectOutputStream o = common.PacketIO.openOutput(s); ObjectInputStream i = common.PacketIO.openInput(s);
            common.PacketIO.send(o, common.Packet.fileOffer("Evil", "..", 10));
            common.Packet reply = common.PacketIO.receive(i);
            check("tên file '..' bị từ chối ngay", reply.getType() == common.PacketType.FILE_REJECT);
        }

        // ---------- Bộ lọc deserialization ----------
        boolean filterClosed = false;
        try (Socket s = new Socket("127.0.0.1", B.getP2pPort())) {
            s.setSoTimeout(2000);
            ObjectOutputStream o = common.PacketIO.openOutput(s);
            ObjectInputStream i = common.PacketIO.openInput(s);
            o.writeObject(new java.util.concurrent.atomic.AtomicInteger(5)); o.flush();   // lớp ngoài danh sách trắng
            i.readObject();
        } catch (EOFException | SocketException e) {
            filterClosed = true;
        }
        check("đối tượng lạ bị chặn, kết nối bị đóng", filterClosed);
        A.sendMessage("Bob", "vẫn sống");
        check("sau tấn công B vẫn hoạt động bình thường", "Alice:vẫn sống".equals(rb.msgs.poll(2, TimeUnit.SECONDS)));

        // ---------- Peer offline ----------
        B.stop();
        check("B thoát -> A thấy B offline", waitFor(() -> !ra.seen("Bob"), 2000));
        try { A.sendMessage("Bob", "còn đó không"); check("gửi cho peer offline phải lỗi", false); }
        catch (IOException e) { check("gửi cho peer offline -> lỗi: \"" + e.getMessage() + "\"", true); }

        // ---------- B quay lại với cổng khác, A dùng kết nối cũ? ----------
        PeerNode B2 = new PeerNode(); Rec rb2 = new Rec(); B2.setListener(rb2);
        B2.start("Bob", "127.0.0.1", tp, 0);
        check("Bob đăng nhập lại được", waitFor(() -> ra.seen("Bob"), 2000));
        A.sendMessage("Bob", "chào Bob mới");
        check("A gửi được cho Bob (cổng mới), không dùng nhầm kết nối cũ", "Alice:chào Bob mới".equals(rb2.msgs.poll(2, TimeUnit.SECONDS)));

        // ---------- Heartbeat timeout (chỉ chạy nhanh khi cấu hình -Dp2p.heartbeat.timeout<=2000) ----------
        if (common.Protocol.HEARTBEAT_TIMEOUT_MS <= 2000) {
            try (Socket silent = new Socket("127.0.0.1", tp)) {
                ObjectOutputStream o = common.PacketIO.openOutput(silent);
                ObjectInputStream i = common.PacketIO.openInput(silent);
                common.PacketIO.send(o, common.Packet.register("Ghost", 55555));
                common.PacketIO.receive(i);   // REGISTER_OK
                check("Ghost đăng ký xong xuất hiện trên A", waitFor(() -> ra.seen("Ghost"), 1000));
                // Không gửi PING -> Tracker phải tự xóa Ghost sau HEARTBEAT_TIMEOUT_MS
                check("Ghost im lặng mất heartbeat -> bị Tracker xóa", waitFor(() -> !ra.seen("Ghost"), common.Protocol.HEARTBEAT_TIMEOUT_MS + 1500));
            }
            check("A vẫn còn online nhờ gửi PING đều đặn", ra.seen("Alice") && !ra.trackerLost.get());
        }

        // ---------- Tắt Tracker: chat P2P vẫn chạy ----------
        tracker.stop();
        check("Tracker sập -> A được báo onTrackerDisconnected", waitFor(ra.trackerLost::get, 3000));
        A.sendMessage("Bob", "Tracker chết rồi nhưng vẫn chat được");
        check("Tracker sập: chat P2P vẫn hoạt động", "Alice:Tracker chết rồi nhưng vẫn chat được".equals(rb2.msgs.poll(2, TimeUnit.SECONDS)));

        A.stop(); B2.stop();
        System.out.println("\nKẾT QUẢ: " + pass + " PASS, " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
