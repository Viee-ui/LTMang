package common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Gói gọn cách mở luồng đối tượng và gửi/nhận Packet, dùng chung cho Tracker và Peer.
 * Yêu cầu JDK 9 trở lên (ObjectInputFilter).
 */
public final class PacketIO {
    private PacketIO() { }

    /**
     * Danh sách trắng: chỉ cho phép deserialize các lớp của project và vài lớp cơ bản.
     * Chặn tấn công "deserialization" (gửi đối tượng độc hại để chạy mã trên máy nhận).
     */
    private static final String FILTER =
            "maxarray=1048576;maxdepth=8;common.*;java.util.*;java.lang.*;!*";

    /** Mở luồng ra TRƯỚC, flush header, rồi mới mở luồng vào - cả hai phía cùng làm vậy nên không bị kẹt (deadlock). */
    public static ObjectOutputStream openOutput(Socket s) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(s.getOutputStream()));
        out.flush();
        return out;
    }

    public static ObjectInputStream openInput(Socket s) throws IOException {
        ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(s.getInputStream()));
        in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(FILTER));
        return in;
    }

    /**
     * Gửi một gói tin. reset() sau mỗi lần ghi để ObjectOutputStream quên đối tượng cũ,
     * nếu không nó giữ tham chiếu tới MỌI gói đã gửi (rò rỉ bộ nhớ, nhất là khi gửi file lớn).
     */
    public static void send(ObjectOutputStream out, Packet p) throws IOException {
        out.writeObject(p);
        out.reset();
        out.flush();
    }

    /** Nhận một gói tin; đối tượng không phải Packet hoặc thiếu loại bị coi là lỗi giao thức. */
    public static Packet receive(ObjectInputStream in) throws IOException {
        try {
            Object o = in.readObject();
            if (o instanceof Packet) {
                Packet p = (Packet) o;
                if (p.getType() == null) throw new IOException("Gói tin thiếu loại.");
                return p;
            }
            throw new IOException("Gói tin không hợp lệ.");
        } catch (ClassNotFoundException e) {
            throw new IOException("Gói tin không hợp lệ.", e);
        }
    }
}
