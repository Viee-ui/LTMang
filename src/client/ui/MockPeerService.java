package client.ui;

import common.PeerInfo;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CHỈ DÙNG ĐỂ XEM THỬ GIAO DIỆN khi chưa có Tracker/PeerNode thật: giả lập danh sách online,
 * tin nhắn đến, lời đề nghị gửi file và tiến trình truyền file. Callback được gọi từ luồng nền
 * (giống PeerNode thật) để kiểm chứng giao diện đã an toàn luồng.
 * Gõ Tracker IP = "fail" để thử thông báo lỗi đăng nhập.
 */
public class MockPeerService implements PeerService {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "mock-network");
        t.setDaemon(true);
        return t;
    });
    private volatile ChatUIListener listener;
    private volatile boolean stopped;
    private String username;
    private int p2pPort;

    @Override public void setListener(ChatUIListener l) { this.listener = l; }

    @Override
    public void start(String username, String trackerHost, int trackerPort, int port) throws IOException {
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if ("fail".equalsIgnoreCase(trackerHost)) throw new ConnectException("Connection refused");
        this.username = username;
        this.p2pPort = (port == 0) ? 49152 + new Random().nextInt(10000) : port;

        PeerInfo alice = new PeerInfo("Alice", "192.168.1.21", 50211);
        PeerInfo bob = new PeerInfo("Bob", "192.168.1.35", 50388);
        PeerInfo me = new PeerInfo(username, getLocalIp(), this.p2pPort);

        scheduler.schedule(() -> listener.onOnlineListUpdated(Arrays.asList(me, alice, bob)), 300, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> listener.onMessageReceived("Alice", "Chào " + username + "! Bạn nhận được tin này chứ?"), 3, TimeUnit.SECONDS);
        scheduler.schedule(this::bobSendsFile, 7, TimeUnit.SECONDS);
        scheduler.schedule(() -> listener.onOnlineListUpdated(Arrays.asList(me, alice)), 16, TimeUnit.SECONDS);          // Bob offline
        scheduler.schedule(() -> listener.onOnlineListUpdated(Arrays.asList(me, alice, bob)), 22, TimeUnit.SECONDS);     // Bob quay lại
    }

    private void bobSendsFile() {
        CompletableFuture<File> answer = new CompletableFuture<>();
        listener.onFileOffered("Bob", "bao_cao_cuoi_ky.pdf", 3_145_728L, new FileOfferResponder() {
            @Override public void accept(File saveTo) { answer.complete(saveTo); }
            @Override public void reject() { answer.complete(null); }
        });
        scheduler.execute(() -> {
            try {
                File target = answer.get(60, TimeUnit.SECONDS);
                if (target == null) return;
                for (int p = 0; p <= 100; p += 4) {
                    listener.onFileProgress("Bob", "bao_cao_cuoi_ky.pdf", p, false);
                    Thread.sleep(60);
                }
                java.nio.file.Files.write(target.toPath(), new byte[]{'d', 'e', 'm', 'o'});
                listener.onFileReceived("Bob", target);
            } catch (Exception e) {
                if (!stopped) listener.onFileFailed("Bob", "bao_cao_cuoi_ky.pdf", String.valueOf(e.getMessage()));
            }
        });
    }

    @Override
    public void sendMessage(String toUser, String content) {
        scheduler.schedule(() -> listener.onMessageReceived(toUser, "(echo) " + content), 700, TimeUnit.MILLISECONDS);
    }

    @Override
    public void sendFile(String toUser, File file) throws IOException {
        try {
            for (int p = 0; p <= 100; p += 5) {
                listener.onFileProgress(toUser, file.getName(), p, true);
                Thread.sleep(80);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bị gián đoạn");
        }
    }

    @Override public void stop() { stopped = true; scheduler.shutdownNow(); }
    @Override public String getUsername() { return username; }
    @Override public String getLocalIp() { return "192.168.1.10"; }
    @Override public int getP2pPort() { return p2pPort; }
}
