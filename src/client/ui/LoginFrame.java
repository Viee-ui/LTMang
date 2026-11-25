package client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Màn hình đăng nhập. KHÔNG tự mở Socket: chỉ thu thập thông tin rồi gọi peerService.start(...)
 * trong SwingWorker (luồng nền) để cửa sổ không bị treo khi chờ kết nối Tracker.
 */
public class LoginFrame extends JFrame {

    private static final int DEFAULT_TRACKER_PORT = 9000;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_. -]{1,20}$");

    private final PeerService peerService;

    private final JTextField txtUsername = new JTextField();
    private final JTextField txtTrackerIp = new JTextField("127.0.0.1");
    private final JTextField txtTrackerPort = new JTextField(String.valueOf(DEFAULT_TRACKER_PORT));
    private final JCheckBox chkAutoPort = new JCheckBox("Tự động chọn port trống", true);
    private final JTextField txtP2pPort = new JTextField();
    private final JLabel lblError = new JLabel(" ");
    private final UITheme.FlatButton btnLogin = new UITheme.FlatButton("Đăng nhập", UITheme.PRIMARY, Color.WHITE);

    public LoginFrame(PeerService peerService) {
        super("P2P Chat - Đăng nhập");
        this.peerService = peerService;
        buildUI();
    }

    private void buildUI() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(Color.WHITE);
        root.setBorder(new EmptyBorder(28, 36, 28, 36));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        int row = 0;

        JLabel title = new JLabel("P2P Chat");
        title.setFont(UITheme.FONT_TITLE.deriveFont(Font.BOLD, 26f));
        title.setForeground(UITheme.PRIMARY);
        c.gridy = row++; c.insets = new Insets(0, 0, 0, 0);
        root.add(title, c);

        JLabel subtitle = new JLabel("Chat và truyền file trực tiếp giữa các máy (Hybrid P2P)");
        subtitle.setFont(UITheme.FONT_SMALL);
        subtitle.setForeground(UITheme.TEXT_MUTED);
        c.gridy = row++; c.insets = new Insets(2, 0, 22, 0);
        root.add(subtitle, c);

        // Username
        UITheme.styleField(txtUsername);
        row = addLabeled(root, c, row, "Tên hiển thị (Username)", txtUsername);

        // Tracker IP + Port
        UITheme.styleField(txtTrackerIp);
        UITheme.styleField(txtTrackerPort);
        txtTrackerPort.setPreferredSize(new Dimension(84, txtTrackerPort.getPreferredSize().height));
        JPanel trackerRow = new JPanel(new BorderLayout(8, 0));
        trackerRow.setOpaque(false);
        trackerRow.add(txtTrackerIp, BorderLayout.CENTER);
        trackerRow.add(txtTrackerPort, BorderLayout.EAST);
        row = addLabeled(root, c, row, "Tracker Server (IP : Port)", trackerRow);

        // Port P2P
        UITheme.styleField(txtP2pPort);
        txtP2pPort.setEnabled(false);
        chkAutoPort.setOpaque(false);
        chkAutoPort.setFont(UITheme.FONT_NORMAL);
        chkAutoPort.setFocusPainted(false);
        chkAutoPort.addActionListener(e -> {
            txtP2pPort.setEnabled(!chkAutoPort.isSelected());
            if (chkAutoPort.isSelected()) txtP2pPort.setText("");
            else txtP2pPort.requestFocusInWindow();
        });
        JPanel portBox = new JPanel(new BorderLayout(0, 6));
        portBox.setOpaque(false);
        portBox.add(chkAutoPort, BorderLayout.NORTH);
        portBox.add(txtP2pPort, BorderLayout.CENTER);
        row = addLabeled(root, c, row, "Port lắng nghe P2P", portBox);

        // Thông báo lỗi
        lblError.setFont(UITheme.FONT_SMALL);
        lblError.setForeground(UITheme.ERROR);
        c.gridy = row++; c.insets = new Insets(4, 0, 8, 0);
        root.add(lblError, c);

        // Nút đăng nhập
        btnLogin.addActionListener(e -> doLogin());
        c.gridy = row++; c.insets = new Insets(0, 0, 0, 0);
        root.add(btnLogin, c);

        setContentPane(root);
        getRootPane().setDefaultButton(btnLogin); // bấm Enter = Đăng nhập
        setPreferredSize(new Dimension(420, 560));
        pack();
        setLocationRelativeTo(null);
        SwingUtilities.invokeLater(txtUsername::requestFocusInWindow);
    }

    private int addLabeled(JPanel root, GridBagConstraints c, int row, String label, JComponent field) {
        JLabel lbl = new JLabel(label);
        lbl.setFont(UITheme.FONT_BOLD);
        lbl.setForeground(UITheme.TEXT);
        c.gridy = row++; c.insets = new Insets(0, 0, 5, 0);
        root.add(lbl, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0);
        root.add(field, c);
        return row;
    }

    private void doLogin() {
        // ---- 1. Kiểm tra đầu vào ----
        final String username = txtUsername.getText().trim();
        final String trackerHost = txtTrackerIp.getText().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            showError("Username gồm 1-20 ký tự (chữ, số, khoảng trắng, _ . -).");
            txtUsername.requestFocusInWindow();
            return;
        }
        if (trackerHost.isEmpty()) {
            showError("Vui lòng nhập IP của Tracker Server.");
            return;
        }
        final int trackerPort;
        final int p2pPort;
        try {
            trackerPort = parsePort(txtTrackerPort.getText(), 1);
        } catch (NumberFormatException ex) {
            showError("Port Tracker không hợp lệ (1-65535).");
            return;
        }
        if (chkAutoPort.isSelected()) {
            p2pPort = 0; // 0 => ServerSocket(0) tự chọn port trống
        } else {
            try {
                p2pPort = parsePort(txtP2pPort.getText(), 1024);
            } catch (NumberFormatException ex) {
                showError("Port P2P không hợp lệ (1024-65535).");
                return;
            }
        }

        // ---- 2. Tạo sẵn cửa sổ chat (chưa hiện) và gắn listener TRƯỚC khi start() ----
        final ChatMainFrame chatFrame = new ChatMainFrame(peerService);
        peerService.setListener(chatFrame);

        // ---- 3. Kết nối ở luồng nền ----
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                peerService.start(username, trackerHost, trackerPort, p2pPort);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    chatFrame.showAfterLogin();
                    dispose();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    chatFrame.dispose();
                    setBusy(false);
                } catch (ExecutionException ex) {
                    chatFrame.dispose();
                    setBusy(false);
                    showError(describe(ex.getCause(), trackerHost, trackerPort));
                }
            }
        }.execute();
    }

    private static int parsePort(String text, int min) {
        int p = Integer.parseInt(text.trim());
        if (p < min || p > 65535) throw new NumberFormatException("out of range");
        return p;
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnLogin.setText(busy ? "Đang kết nối..." : "Đăng nhập");
        txtUsername.setEnabled(!busy);
        txtTrackerIp.setEnabled(!busy);
        txtTrackerPort.setEnabled(!busy);
        chkAutoPort.setEnabled(!busy);
        txtP2pPort.setEnabled(!busy && !chkAutoPort.isSelected());
        if (busy) showError(" ");
    }

    private void showError(String message) {
        lblError.setText("<html><body style='width:300px'>" + UITheme.toHtml(message) + "</body></html>");
    }

    /** Đổi ngoại lệ mạng kỹ thuật thành câu tiếng Việt dễ hiểu. */
    private static String describe(Throwable t, String host, int port) {
        if (t instanceof ConnectException)
            return "Không kết nối được Tracker tại " + host + ":" + port + ". Kiểm tra Tracker đã chạy chưa, IP/port và tường lửa.";
        if (t instanceof UnknownHostException)
            return "Địa chỉ Tracker không hợp lệ: " + host;
        if (t instanceof SocketTimeoutException)
            return "Tracker không phản hồi (hết thời gian chờ).";
        if (t instanceof BindException)
            return "Port P2P đang bị chương trình khác dùng. Hãy chọn port khác hoặc bật tự động chọn port.";
        String msg = t == null ? null : t.getMessage();
        return (msg == null || msg.isEmpty()) ? "Đăng nhập thất bại: " + t : msg;
    }
}
