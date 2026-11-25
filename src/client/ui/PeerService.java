package client.ui;

import java.io.File;
import java.io.IOException;

/**
 * Những gì giao diện cần từ lớp mạng. Lớp PeerNode chỉ cần "implements PeerService".
 * Giao diện chỉ biết interface này - không biết gì về Socket.
 */
public interface PeerService {

    /** Đăng ký listener nhận callback. Phải gọi TRƯỚC start() để không bỏ lỡ danh sách online đầu tiên. */
    void setListener(ChatUIListener listener);

    /**
     * Mở ServerSocket P2P (p2pPort = 0 nghĩa là tự chọn port trống), kết nối Tracker, đăng ký Username.
     * HÀM CHẶN - UI luôn gọi trong SwingWorker. Ném IOException với thông điệp rõ ràng khi thất bại
     * (Tracker không chạy, trùng username, port bận...).
     */
    void start(String username, String trackerHost, int trackerPort, int p2pPort) throws IOException;

    /** Gửi tin nhắn trực tiếp (có thể chặn khi mở kết nối). Ném IOException nếu Peer không còn liên lạc được. */
    void sendMessage(String toUser, String content) throws IOException;

    /**
     * Gửi file. Chặn cho tới khi xong; trong lúc gửi gọi listener.onFileProgress(..., true).
     * Ném IOException nếu đối phương từ chối hoặc lỗi mạng.
     */
    void sendFile(String toUser, File file) throws IOException;

    /** Báo Tracker mình offline, đóng ServerSocket và các kết nối. */
    void stop();

    String getUsername();

    /** IP của mình mà Tracker nhìn thấy (để hiển thị). */
    String getLocalIp();

    /** Port P2P thực sự đang mở (sau khi start() thành công). */
    int getP2pPort();
}
