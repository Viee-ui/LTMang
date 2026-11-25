package common;

import java.io.Serializable;
import java.util.Objects;

/**
 * Thông tin một Peer đang online: Username -> IP + cổng P2P đang lắng nghe.
 * Hai PeerInfo được coi là bằng nhau khi trùng Username (Username là khóa duy nhất trên Tracker).
 *
 * Nằm trong package `common` vì Tracker và Client cùng dùng: khi serialize qua ObjectStream,
 * hai bên phải có lớp với CÙNG tên đầy đủ (common.PeerInfo).
 */
public final class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String ip;
    private final int port;

    public PeerInfo(String username, String ip, int port) {
        this.username = username;
        this.ip = ip;
        this.port = port;
    }

    public String getUsername() { return username; }
    public String getIp() { return ip; }
    public int getPort() { return port; }

    /** Dạng "ip:port" để hiển thị trên giao diện. */
    public String getAddress() { return ip + ":" + port; }

    @Override
    public boolean equals(Object o) {
        return o instanceof PeerInfo && ((PeerInfo) o).username.equals(username);
    }

    @Override
    public int hashCode() { return Objects.hash(username); }

    @Override
    public String toString() { return username; }
}
