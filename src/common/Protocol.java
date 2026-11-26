package common;

/**
 * Hằng số giao thức dùng chung. Có thể chỉnh bằng tham số JVM (-Dp2p.heartbeat.interval=...) để kiểm thử nhanh.
 */
public final class Protocol {
    private Protocol() { }

    /** Peer gửi PING cho Tracker mỗi chu kỳ này. */
    public static final int HEARTBEAT_INTERVAL_MS = Integer.getInteger("p2p.heartbeat.interval", 10_000);

    /**
     * Quá thời gian này không nhận được gói nào thì coi đối phương đã chết
     * (Tracker coi Peer offline; Peer coi Tracker mất kết nối). Mặc định = 3 chu kỳ.
     */
    public static final int HEARTBEAT_TIMEOUT_MS = Integer.getInteger("p2p.heartbeat.timeout", 30_000);

    // ---------- UDP Auto-Discovery trong mạng LAN ----------
    /** Cổng UDP mà Tracker lắng nghe gói broadcast tìm kiếm từ Client. */
    public static final int DISCOVERY_PORT = Integer.getInteger("p2p.discovery.port", 9001);
    /** Nội dung gói UDP Client broadcast để tìm Tracker. */
    public static final String DISCOVERY_REQUEST = "P2P_CHAT_DISCOVER_V1";
    /** Tiền tố gói UDP Tracker trả lời (kèm cổng TCP đang mở, ví dụ: P2P_CHAT_TRACKER_V1:9000). */
    public static final String DISCOVERY_RESPONSE_PREFIX = "P2P_CHAT_TRACKER_V1:";
    /** Thời gian Client chờ phản hồi UDP từ các Tracker trong LAN. */
    public static final int DISCOVERY_TIMEOUT_MS = Integer.getInteger("p2p.discovery.timeout", 1_500);

    // ---------- Tự động kết nối lại Tracker (Exponential Backoff) ----------
    /** Thời gian chờ ban đầu trước khi thử kết nối lại Tracker. */
    public static final int RECONNECT_INITIAL_MS = Integer.getInteger("p2p.reconnect.initial", 2_000);
    /** Thời gian chờ tối đa giữa hai lần thử kết nối lại Tracker. */
    public static final int RECONNECT_MAX_MS = Integer.getInteger("p2p.reconnect.max", 15_000);
}
