package common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Gói tin duy nhất của giao thức. Một lớp với vài trường tùy chọn; mỗi loại gói chỉ dùng một vài trường
 * (xem các hàm tạo tĩnh bên dưới). Ưu điểm: dễ giải thích, dễ kiểm soát (ObjectInputFilter chỉ cần cho phép `common.*`).
 */
public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;

    private final PacketType type;
    private String sender;          // username người gửi
    private String content;         // nội dung tin nhắn / lý do lỗi / IP
    private int port;               // cổng P2P (REGISTER)
    private String fileName;        // FILE_OFFER
    private long fileSize;          // FILE_OFFER
    private byte[] data;            // FILE_CHUNK
    private ArrayList<PeerInfo> peers; // REGISTER_OK, ONLINE_LIST

    private Packet(PacketType type) { this.type = type; }

    // ---------- Peer <-> Tracker ----------
    public static Packet register(String username, int p2pPort) {
        Packet p = new Packet(PacketType.REGISTER);
        p.sender = username;
        p.port = p2pPort;
        return p;
    }

    public static Packet registerOk(String yourIp, List<PeerInfo> peers) {
        Packet p = new Packet(PacketType.REGISTER_OK);
        p.content = yourIp;
        p.peers = new ArrayList<>(peers);
        return p;
    }

    public static Packet registerFail(String reason) {
        Packet p = new Packet(PacketType.REGISTER_FAIL);
        p.content = reason;
        return p;
    }

    public static Packet onlineList(List<PeerInfo> peers) {
        Packet p = new Packet(PacketType.ONLINE_LIST);
        p.peers = new ArrayList<>(peers);
        return p;
    }

    public static Packet unregister(String username) {
        Packet p = new Packet(PacketType.UNREGISTER);
        p.sender = username;
        return p;
    }

    // ---------- Peer <-> Peer ----------
    public static Packet message(String sender, String content) {
        Packet p = new Packet(PacketType.MESSAGE);
        p.sender = sender;
        p.content = content;
        return p;
    }

    public static Packet messageAck() { return new Packet(PacketType.MESSAGE_ACK); }

    public static Packet fileOffer(String sender, String fileName, long fileSize) {
        Packet p = new Packet(PacketType.FILE_OFFER);
        p.sender = sender;
        p.fileName = fileName;
        p.fileSize = fileSize;
        return p;
    }

    public static Packet fileAccept() { return new Packet(PacketType.FILE_ACCEPT); }

    public static Packet fileReject(String reason) {
        Packet p = new Packet(PacketType.FILE_REJECT);
        p.content = reason;
        return p;
    }

    public static Packet fileChunk(byte[] data) {
        Packet p = new Packet(PacketType.FILE_CHUNK);
        p.data = data;
        return p;
    }

    public static Packet fileEnd() { return new Packet(PacketType.FILE_END); }

    public static Packet fileDone() { return new Packet(PacketType.FILE_DONE); }

    public static Packet fileError(String reason) {
        Packet p = new Packet(PacketType.FILE_ERROR);
        p.content = reason;
        return p;
    }

    // ---------- Getter ----------
    public PacketType getType() { return type; }
    public String getSender() { return sender; }
    public String getContent() { return content; }
    public int getPort() { return port; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public byte[] getData() { return data; }
    public List<PeerInfo> getPeers() { return peers == null ? new ArrayList<>() : peers; }

    @Override
    public String toString() { return "Packet[" + type + (sender != null ? ", from=" + sender : "") + "]"; }
}
