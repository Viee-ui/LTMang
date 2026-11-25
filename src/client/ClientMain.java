package client;

import client.ui.LoginFrame;
import client.ui.UITheme;

import javax.swing.SwingUtilities;

/** Điểm khởi động của Peer Client (chạy trên mỗi máy). */
public class ClientMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UITheme.install();
            new LoginFrame(new PeerNode()).setVisible(true);
        });
    }
}
