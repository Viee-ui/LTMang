package client.ui;

import common.PeerInfo;

import java.io.File;
import java.util.List;

/**
 * Callback từ lớp mạng (PeerNode) lên giao diện (Observer pattern).
 *
 * QUY ƯỚC LUỒNG: PeerNode được phép gọi các hàm này từ BẤT KỲ luồng nào
 * (luồng đọc Tracker, luồng ClientHandler, luồng gửi file...).
 * Bên cài đặt (ChatMainFrame) tự bọc SwingUtilities.invokeLater, nên PeerNode không cần quan tâm tới EDT.
 */
public interface ChatUIListener {

    /** Tracker gửi danh sách online mới (có thể chứa cả chính mình, UI sẽ tự lọc). */
    void onOnlineListUpdated(List<PeerInfo> onlinePeers);

    /** Nhận được tin nhắn văn bản trực tiếp từ một Peer. */
    void onMessageReceived(String fromUser, String content);

    /** Có Peer muốn gửi file: UI hỏi người dùng rồi gọi responder.accept(...) hoặc responder.reject(). */
    void onFileOffered(String fromUser, String fileName, long fileSize, FileOfferResponder responder);

    /** Tiến trình truyền file (0-100). sending = true khi mình gửi, false khi mình nhận. Chỉ nên gọi khi % đổi. */
    void onFileProgress(String peerName, String fileName, int percent, boolean sending);

    /** Đã nhận xong file và lưu thành công. */
    void onFileReceived(String fromUser, File savedFile);

    /** Nhận file thất bại (đối phương ngắt giữa chừng, lỗi ghi đĩa...). Lỗi khi GỬI sẽ được ném từ sendFile(). */
    void onFileFailed(String peerName, String fileName, String reason);

    /** Mất kết nối tới Tracker (các cuộc chat P2P đang mở vẫn hoạt động bình thường). */
    void onTrackerDisconnected(String reason);
}
