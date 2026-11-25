package common;

/** Các loại gói tin trong toàn hệ thống (Peer <-> Tracker và Peer <-> Peer). */
public enum PacketType {
    // ---- Peer <-> Tracker ----
    REGISTER,        // Peer -> Tracker: xin đăng ký (username + cổng P2P)
    REGISTER_OK,     // Tracker -> Peer: thành công (kèm IP Tracker nhìn thấy + danh sách online)
    REGISTER_FAIL,   // Tracker -> Peer: thất bại (trùng username, dữ liệu sai...)
    ONLINE_LIST,     // Tracker -> mọi Peer: danh sách online mới (broadcast)
    UNREGISTER,      // Peer -> Tracker: báo thoát
    PING,            // Peer -> Tracker: "tôi vẫn sống" (heartbeat)
    PONG,            // Tracker -> Peer: "Tracker vẫn sống"

    // ---- Peer <-> Peer: tin nhắn ----
    MESSAGE,         // nội dung tin nhắn
    MESSAGE_ACK,     // người nhận xác nhận đã chuyển tin lên giao diện

    // ---- Peer <-> Peer: truyền file ----
    FILE_OFFER,      // người gửi: "tôi muốn gửi file X, dung lượng Y"
    FILE_ACCEPT,     // người nhận đồng ý
    FILE_REJECT,     // người nhận từ chối / hết thời gian chờ
    FILE_CHUNK,      // một khối dữ liệu file
    FILE_END,        // người gửi: đã gửi hết (kèm mã SHA-256 của toàn bộ file)
    FILE_DONE,       // người nhận: đã nhận đủ và lưu thành công
    FILE_ERROR       // người nhận: có lỗi trong lúc nhận
}
