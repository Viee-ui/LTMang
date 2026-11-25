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
}
