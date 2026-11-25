package client.ui;

import javax.swing.SwingUtilities;

/** Chạy thử giao diện độc lập với MockPeerService (không cần Tracker). */
public class UiPreview {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UITheme.install();
            new LoginFrame(new MockPeerService()).setVisible(true);
        });
    }
}
