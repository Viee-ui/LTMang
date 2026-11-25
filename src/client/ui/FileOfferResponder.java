package client.ui;

import java.io.File;

/**
 * "Phao cứu sinh" để UI trả lời lời đề nghị gửi file mà KHÔNG chặn luồng mạng.
 * PeerNode đưa đối tượng này cho UI qua ChatUIListener.onFileOffered(...) rồi chờ
 * (ví dụ CompletableFuture.get(60, SECONDS)) cho tới khi UI gọi accept() hoặc reject().
 */
public interface FileOfferResponder {
    /** Đồng ý nhận và lưu file vào đường dẫn saveTo. */
    void accept(File saveTo);

    /** Từ chối nhận file. */
    void reject();

    /** Trả về true nếu lời đề nghị đã hết thời gian chờ hoặc bị hủy trước khi người dùng trả lời. */
    default boolean isExpired() { return false; }
}
