package client.ui;

import common.PeerInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Màn hình chat chính: cột trái = danh sách online, cột phải = khung chat + gửi file.
 *
 * - KHÔNG chứa Socket. Mọi thao tác mạng đi qua PeerService, kết quả quay về qua ChatUIListener.
 * - Mọi dữ liệu giao diện (chatHistory, unreadCounts, currentPeer...) CHỈ được đụng tới trên EDT,
 *   nên không cần synchronized.
 * - Các lời gọi PeerService có thể chặn (mở socket) nên được đẩy sang executor riêng, EDT không bao giờ bị treo.
 */
public class ChatMainFrame extends JFrame implements ChatUIListener {

    private static final int SIDEBAR_WIDTH = 220;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final PeerService peerService;

    // ---- Trạng thái (chỉ dùng trên EDT) ----
    private final Map<String, List<String>> chatHistory = new HashMap<>();   // username -> các dòng HTML
    private final Map<String, Integer> unreadCounts = new HashMap<>();
    private final DefaultListModel<PeerInfo> peerListModel = new DefaultListModel<>();
    private String currentPeer;           // username đang chat cùng (null = chưa chọn ai)
    private PeerInfo currentPeerInfo;
    private boolean currentPeerOnline;
    private boolean updatingList;         // true khi đang nạp lại JList -> bỏ qua sự kiện chọn
    private File lastDirectory = new File(System.getProperty("user.home"));

    // Theo dõi các phiên truyền file song song (chống spam invokeLater & không ẩn sớm thanh tiến trình)
    private final Map<String, Integer> progressCache = new ConcurrentHashMap<>();
    private final Set<String> activeTransfers = new HashSet<>();               // chỉ dùng trên EDT
    private final Map<String, String> savedFileToOfferKey = new HashMap<>();   // chỉ dùng trên EDT

    // ---- Luồng nền cho các lệnh có thể chặn ----
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor(daemon("msg-sender")); // giữ đúng thứ tự tin nhắn
    private final ExecutorService fileExecutor = Executors.newCachedThreadPool(daemon("file-sender"));

    // ---- Component ----
    private final JLabel lblMyName = new JLabel("...");
    private final JLabel lblMyStatus = new JLabel("Online");
    private final JLabel lblMyAddress = new JLabel(" ");
    private final JLabel lblOnlineCaption = new JLabel();
    private final JList<PeerInfo> lstPeers = new JList<>(peerListModel);

    private final JLabel lblPeerName = new JLabel("Chưa chọn cuộc trò chuyện");
    private final JLabel lblPeerAddress = new JLabel(" ");
    private final JTextPane chatPane = new JTextPane();
    private final JPanel transferPanel = new JPanel(new BorderLayout(0, 4));
    private final JLabel lblTransfer = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final UITheme.HintTextField txtInput = new UITheme.HintTextField("Nhập tin nhắn rồi nhấn Enter...");
    private final UITheme.FlatButton btnSend = new UITheme.FlatButton("Gửi", UITheme.PRIMARY, Color.WHITE);
    private final UITheme.FlatButton btnAttach =
            new UITheme.FlatButton("Đính kèm File", UITheme.BORDER, UITheme.TEXT);

    public ChatMainFrame(PeerService peerService) {
        super("P2P Chat");
        this.peerService = peerService;
        buildUI();
        setInputEnabled(false);
        renderConversation();
        updateOnlineCaption();
    }

    // =====================================================================
    //  XÂY DỰNG GIAO DIỆN
    // =====================================================================

    private void buildUI() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeftPanel(), buildRightPanel());
        split.setDividerLocation(SIDEBAR_WIDTH);
        split.setDividerSize(1);
        split.setResizeWeight(0);       // chỉ cột phải co giãn khi đổi kích thước cửa sổ
        split.setEnabled(false);        // cố định cột trái ~220px
        split.setBorder(null);

        setContentPane(split);
        setSize(940, 640);
        setMinimumSize(new Dimension(720, 480));
        setLocationRelativeTo(null);
    }

    private JComponent buildLeftPanel() {
        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(UITheme.SIDEBAR_BG);
        left.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        left.setMinimumSize(new Dimension(SIDEBAR_WIDTH, 0));

        // Header: tên mình + trạng thái + IP:Port
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(18, 16, 14, 16));

        lblMyName.setFont(UITheme.FONT_TITLE);
        lblMyName.setForeground(UITheme.TEXT);
        lblMyStatus.setIcon(new UITheme.DotIcon(UITheme.ONLINE, 10));
        lblMyStatus.setIconTextGap(6);
        lblMyStatus.setFont(UITheme.FONT_SMALL);
        lblMyStatus.setForeground(UITheme.TEXT_MUTED);
        lblMyAddress.setFont(UITheme.FONT_SMALL);
        lblMyAddress.setForeground(UITheme.TEXT_MUTED);
        for (JComponent c : new JComponent[]{lblMyName, lblMyStatus, lblMyAddress}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        header.add(lblMyName);
        header.add(Box.createVerticalStrut(4));
        header.add(lblMyStatus);
        header.add(Box.createVerticalStrut(2));
        header.add(lblMyAddress);

        lblOnlineCaption.setFont(UITheme.FONT_SMALL.deriveFont(Font.BOLD));
        lblOnlineCaption.setForeground(UITheme.TEXT_MUTED);
        lblOnlineCaption.setBorder(new EmptyBorder(10, 16, 8, 16));

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(lblOnlineCaption, BorderLayout.SOUTH);
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER));
        left.add(north, BorderLayout.NORTH);

        // Danh sách peer
        lstPeers.setCellRenderer(new PeerCellRenderer());
        lstPeers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstPeers.setBackground(UITheme.SIDEBAR_BG);
        lstPeers.setFixedCellHeight(56);
        lstPeers.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingList) return;
            PeerInfo selected = lstPeers.getSelectedValue();
            if (selected != null) openConversation(selected);
        });
        JScrollPane listScroll = new JScrollPane(lstPeers);
        listScroll.setBorder(null);
        listScroll.getViewport().setBackground(UITheme.SIDEBAR_BG);
        left.add(listScroll, BorderLayout.CENTER);
        return left;
    }

    private JComponent buildRightPanel() {
        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(UITheme.CHAT_BG);

        // ---- Top bar ----
        JPanel topBar = new JPanel();
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.Y_AXIS));
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER),
                new EmptyBorder(14, 20, 12, 20)));
        lblPeerName.setFont(UITheme.FONT_TITLE);
        lblPeerName.setForeground(UITheme.TEXT);
        lblPeerAddress.setFont(UITheme.FONT_SMALL);
        lblPeerAddress.setForeground(UITheme.TEXT_MUTED);
        lblPeerName.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblPeerAddress.setAlignmentX(Component.LEFT_ALIGNMENT);
        topBar.add(lblPeerName);
        topBar.add(Box.createVerticalStrut(2));
        topBar.add(lblPeerAddress);
        right.add(topBar, BorderLayout.NORTH);

        // ---- Center: khung chat (HTML) ----
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = new StyleSheet();                 // StyleSheet riêng, không đụng vào mặc định dùng chung
        css.addStyleSheet(kit.getStyleSheet());
        css.addRule("body { font-family: '" + UITheme.FONT_FAMILY + "', sans-serif; font-size: 13px;"
                + " color: #111827; margin: 10px 16px; }");
        kit.setStyleSheet(css);
        chatPane.setEditorKit(kit);
        chatPane.setEditable(false);
        chatPane.setBackground(UITheme.CHAT_BG);

        JScrollPane chatScroll = new JScrollPane(chatPane,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        chatScroll.setBorder(null);
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);

        // Thanh tiến trình truyền file (ẩn khi không có giao dịch)
        transferPanel.setBackground(Color.decode("#F9FAFB"));
        transferPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER),
                new EmptyBorder(8, 20, 8, 20)));
        lblTransfer.setFont(UITheme.FONT_SMALL);
        lblTransfer.setForeground(UITheme.TEXT_MUTED);
        progressBar.setStringPainted(true);
        progressBar.setForeground(UITheme.PRIMARY);
        progressBar.setPreferredSize(new Dimension(100, 16));
        transferPanel.add(lblTransfer, BorderLayout.NORTH);
        transferPanel.add(progressBar, BorderLayout.CENTER);
        transferPanel.setVisible(false);

        JPanel center = new JPanel(new BorderLayout());
        center.add(chatScroll, BorderLayout.CENTER);
        center.add(transferPanel, BorderLayout.SOUTH);
        right.add(center, BorderLayout.CENTER);

        // ---- Bottom: ô nhập + nút ----
        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));   // GridLayout để 2 nút cao bằng ô nhập
        buttons.setOpaque(false);
        buttons.add(btnAttach);
        buttons.add(btnSend);

        JPanel inputBar = new JPanel(new BorderLayout(8, 0));
        inputBar.setBackground(Color.WHITE);
        inputBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER),
                new EmptyBorder(12, 16, 12, 16)));
        inputBar.add(txtInput, BorderLayout.CENTER);
        inputBar.add(buttons, BorderLayout.EAST);
        right.add(inputBar, BorderLayout.SOUTH);

        txtInput.addActionListener(e -> sendCurrentMessage());   // Enter = gửi
        btnSend.addActionListener(e -> sendCurrentMessage());
        btnAttach.addActionListener(e -> chooseAndSendFile());
        return right;
    }

    // =====================================================================
    //  GỌI TỪ LoginFrame
    // =====================================================================

    /** Hiện cửa sổ sau khi peerService.start() thành công; ghi rõ Port P2P lên tiêu đề. */
    public void showAfterLogin() {
        String user = peerService.getUsername();
        int port = peerService.getP2pPort();
        lblMyName.setText(user);
        lblMyAddress.setText(peerService.getLocalIp() + ":" + port);
        setTitle("P2P Chat - " + user + " - Port P2P: " + port);
        setVisible(true);
    }

    // =====================================================================
    //  CALLBACK TỪ LỚP MẠNG (có thể gọi từ luồng bất kỳ -> luôn invokeLater)
    // =====================================================================

    @Override
    public void onOnlineListUpdated(List<PeerInfo> onlinePeers) {
        final List<PeerInfo> snapshot = new ArrayList<>(onlinePeers); // sao chép: PeerNode có thể sửa list gốc sau đó
        SwingUtilities.invokeLater(() -> applyOnlineList(snapshot));
    }

    @Override
    public void onMessageReceived(String fromUser, String content) {
        SwingUtilities.invokeLater(() -> {
            addToHistory(fromUser, formatTheirs(fromUser, content));
            markUnreadIfOther(fromUser);
        });
    }

    @Override
    public void onFileOffered(String fromUser, String fileName, long fileSize, FileOfferResponder responder) {
        SwingUtilities.invokeLater(() -> handleFileOffer(fromUser, fileName, fileSize, responder));
    }

    @Override
    public void onFileProgress(String peerName, String fileName, int percent, boolean sending) {
        final String safeFile = fileName == null ? "" : fileName;
        final String key = transferKey(peerName, safeFile, sending);
        Integer prev = progressCache.put(key, percent);
        if (prev != null && prev == percent) return;
        SwingUtilities.invokeLater(() -> {
            activeTransfers.add(key);
            transferPanel.setVisible(true);
            String extra = activeTransfers.size() > 1 ? "  (+" + (activeTransfers.size() - 1) + " file khác)" : "";
            lblTransfer.setText((sending ? "Đang gửi \"" : "Đang nhận \"") + safeFile
                    + (sending ? "\" tới " : "\" từ ") + peerName + extra);
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
        });
    }

    @Override
    public void onFileReceived(String fromUser, File savedFile) {
        SwingUtilities.invokeLater(() -> {
            String key = savedFileToOfferKey.remove(savedFile.getAbsolutePath());
            if (key == null) key = transferKey(fromUser, savedFile.getName(), false);
            finishTransfer(key);
            addSystemLine(fromUser, "Đã nhận file \"" + savedFile.getName() + "\" ("
                    + UITheme.formatSize(savedFile.length()) + "). Lưu tại: " + savedFile.getAbsolutePath());
            markUnreadIfOther(fromUser);
        });
    }

    @Override
    public void onFileFailed(String peerName, String fileName, String reason) {
        SwingUtilities.invokeLater(() -> {
            String key = transferKey(peerName, fileName, false);
            savedFileToOfferKey.values().removeIf(k -> k.equals(key));
            finishTransfer(key);
            addSystemLine(peerName, "Truyền file \"" + fileName + "\" thất bại: " + reason);
            markUnreadIfOther(peerName);
        });
    }

    @Override
    public void onTrackerDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            lblMyStatus.setIcon(new UITheme.DotIcon(UITheme.OFFLINE, 10));
            lblMyStatus.setText("Mất kết nối Tracker");
            lblMyStatus.setToolTipText(reason);
            lblOnlineCaption.setText("DANH SÁCH CÓ THỂ ĐÃ CŨ");
        });
    }

    // =====================================================================
    //  DANH SÁCH ONLINE
    // =====================================================================

    private void applyOnlineList(List<PeerInfo> peers) {
        String me = peerService.getUsername();
        peers.sort((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()));

        PeerInfo currentStillOnline = null;
        updatingList = true;
        try {
            peerListModel.clear();
            for (PeerInfo p : peers) {
                if (p.getUsername().equals(me)) continue;          // không hiện chính mình
                peerListModel.addElement(p);
                if (p.getUsername().equals(currentPeer)) currentStillOnline = p;
            }
            if (currentStillOnline != null) lstPeers.setSelectedValue(currentStillOnline, true);
        } finally {
            updatingList = false;
        }

        lblMyStatus.setIcon(new UITheme.DotIcon(UITheme.ONLINE, 10));
        lblMyStatus.setText("Online");
        lblMyStatus.setToolTipText(null);
        updateOnlineCaption();

        if (currentPeer == null) {
            renderConversation();           // làm mới màn hình chào ("chưa có ai online" / "chọn một người")
            return;
        }
        if (currentStillOnline != null) {
            currentPeerInfo = currentStillOnline;                  // có thể đổi IP/port nếu họ đăng nhập lại
            if (!currentPeerOnline) {
                currentPeerOnline = true;
                addSystemLine(currentPeer, currentPeer + " đã online trở lại.");
            }
        } else if (currentPeerOnline) {
            currentPeerOnline = false;
            addSystemLine(currentPeer, currentPeer + " đã offline. Bạn không thể gửi tin nhắn hay file cho tới khi họ quay lại.");
        }
        setInputEnabled(currentPeerOnline);
        updateTopBar();
    }

    private void updateOnlineCaption() {
        lblOnlineCaption.setText("ĐANG ONLINE (" + peerListModel.getSize() + ")");
    }

    private void openConversation(PeerInfo peer) {
        currentPeer = peer.getUsername();
        currentPeerInfo = peer;
        currentPeerOnline = true;
        unreadCounts.remove(currentPeer);
        lstPeers.repaint();
        updateTopBar();
        renderConversation();
        setInputEnabled(true);
        txtInput.requestFocusInWindow();
    }

    private void updateTopBar() {
        if (currentPeer == null) {
            lblPeerName.setText("Chưa chọn cuộc trò chuyện");
            lblPeerAddress.setText(" ");
        } else if (currentPeerOnline && currentPeerInfo != null) {
            lblPeerName.setText(currentPeer);
            lblPeerAddress.setText("Kết nối P2P trực tiếp tới " + currentPeerInfo.getAddress());
        } else {
            lblPeerName.setText(currentPeer + " (offline)");
            lblPeerAddress.setText("Người dùng này hiện không online");
        }
    }

    private void setInputEnabled(boolean enabled) {
        txtInput.setEnabled(enabled);
        btnSend.setEnabled(enabled);
        btnAttach.setEnabled(enabled);
    }

    // =====================================================================
    //  HIỂN THỊ TIN NHẮN
    // =====================================================================

    /** Lưu dòng HTML vào lịch sử của peer; nếu đang mở đúng cuộc chat đó thì vẽ luôn. */
    private void addToHistory(String peer, String htmlLine) {
        List<String> history = chatHistory.computeIfAbsent(peer, k -> new ArrayList<>());
        history.add(htmlLine);
        if (!peer.equals(currentPeer)) return;
        if (history.size() == 1) renderConversation();   // dòng đầu tiên: dựng lại để không bị dòng trống thừa ở đầu
        else appendToView(htmlLine);
    }

    private void addSystemLine(String peer, String text) {
        addToHistory(peer, "<div style='margin:4px 0; color:#6B7280; font-style:italic;'>["
                + now() + "] " + UITheme.toHtml(text) + "</div>");
    }

    private String formatMine(String text) {
        return "<div style='margin:4px 0;'><span style='color:#9CA3AF;'>[" + now() + "]</span> "
                + "<span style='color:#2563EB; font-weight:bold;'>Bạn: " + UITheme.toHtml(text) + "</span></div>";
    }

    private String formatTheirs(String from, String text) {
        return "<div style='margin:4px 0;'><span style='color:#9CA3AF;'>[" + now() + "]</span> "
                + "<span style='color:#111827;'><span style='font-weight:bold;'>" + UITheme.toHtml(from)
                + ":</span> " + UITheme.toHtml(text) + "</span></div>";
    }

    private static String now() {
        return LocalTime.now().format(TIME_FMT);
    }

    /** Vẽ lại toàn bộ khung chat từ lịch sử của peer đang chọn (dùng khi đổi người chat). */
    private void renderConversation() {
        String inner;
        if (currentPeer == null) {
            String hint = peerListModel.isEmpty()
                    ? "Chưa có ai khác đang online.<br>Hãy mở thêm một client và đăng nhập."
                    : "Chọn một người ở danh sách bên trái<br>để bắt đầu trò chuyện.";
            inner = "<div style='margin-top:120px; text-align:center; color:#9CA3AF;'>" + hint + "</div>";
        } else {
            inner = String.join("", chatHistory.getOrDefault(currentPeer, Collections.<String>emptyList()));
        }
        chatPane.setText("<html><body>" + inner + "</body></html>");
        scrollToBottom();
    }

    /** Thêm 1 dòng vào cuối khung chat mà không phải dựng lại toàn bộ. */
    private void appendToView(String htmlLine) {
        try {
            HTMLDocument doc = (HTMLDocument) chatPane.getDocument();
            Element body = findBody(doc);
            if (body == null) throw new IllegalStateException("no body");
            doc.insertBeforeEnd(body, htmlLine);
            scrollToBottom();
        } catch (BadLocationException | IOException | RuntimeException ex) {
            renderConversation();   // phương án dự phòng
        }
    }

    private static Element findBody(HTMLDocument doc) {
        Element root = doc.getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element e = root.getElement(i);
            if ("body".equalsIgnoreCase(e.getName())) return e;
        }
        return null;
    }

    private void scrollToBottom() {
        // invokeLater: đợi layout tính xong chiều cao mới rồi mới cuộn
        SwingUtilities.invokeLater(() -> chatPane.setCaretPosition(chatPane.getDocument().getLength()));
    }

    // =====================================================================
    //  GỬI TIN NHẮN / FILE (đẩy sang luồng nền)
    // =====================================================================

    private void sendCurrentMessage() {
        if (currentPeer == null || !currentPeerOnline) return;
        final String text = txtInput.getText().trim();
        if (text.isEmpty()) return;
        final String target = currentPeer;

        txtInput.setText("");
        addToHistory(target, formatMine(text));      // hiện ngay, không chờ mạng

        messageExecutor.submit(() -> {
            try {
                peerService.sendMessage(target, text);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        addSystemLine(target, "Không gửi được tin nhắn tới " + target + ": " + describe(ex)));
            }
        });
    }

    private void chooseAndSendFile() {
        if (currentPeer == null || !currentPeerOnline) return;
        final String target = currentPeer;

        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle("Chọn file để gửi cho " + target);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final File file = chooser.getSelectedFile();
        lastDirectory = chooser.getCurrentDirectory();

        if (file == null || !file.isFile() || !file.canRead()) {
            JOptionPane.showMessageDialog(this, "Không đọc được file đã chọn.", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        addSystemLine(target, "Đang gửi file \"" + file.getName() + "\" (" + UITheme.formatSize(file.length())
                + ") tới " + target + "... Chờ đối phương chấp nhận.");

        final String sendKey = transferKey(target, file.getName(), true);
        fileExecutor.submit(() -> {
            try {
                peerService.sendFile(target, file);   // chặn tới khi xong, tiến trình về qua onFileProgress
                SwingUtilities.invokeLater(() -> {
                    finishTransfer(sendKey);
                    addSystemLine(target, "Đã gửi file \"" + file.getName() + "\" thành công.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    finishTransfer(sendKey);
                    addSystemLine(target, "Gửi file \"" + file.getName() + "\" thất bại: " + describe(ex));
                });
            }
        });
    }

    private void finishTransfer(String key) {
        progressCache.remove(key);
        activeTransfers.remove(key);
        if (activeTransfers.isEmpty()) {
            transferPanel.setVisible(false);
        }
    }

    private static String transferKey(String peerName, String fileName, boolean sending) {
        return (sending ? "S:" : "R:") + peerName + ":" + (fileName == null ? "" : fileName);
    }

    private void markUnreadIfOther(String peer) {
        if (!peer.equals(currentPeer)) {
            unreadCounts.merge(peer, 1, Integer::sum);
            lstPeers.repaint();
        }
    }

    // =====================================================================
    //  NHẬN FILE: hỏi đồng ý -> chọn nơi lưu -> trả lời cho PeerNode
    // =====================================================================

    private void handleFileOffer(String fromUser, String fileName, long fileSize, FileOfferResponder responder) {
        // Tên file do đối phương gửi -> chỉ lấy phần tên, bỏ mọi đường dẫn (chống "../../")
        String safeName = new File(fileName).getName();
        if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) safeName = "file_nhan_duoc";

        int choice = JOptionPane.showConfirmDialog(this,
                fromUser + " muốn gửi cho bạn một file:\n\n" + safeName + "  (" + UITheme.formatSize(fileSize) + ")\n\nBạn có muốn nhận không?",
                "Yêu cầu nhận file", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (responder.isExpired()) return;
        if (choice != JOptionPane.YES_OPTION) {
            responder.reject();
            addSystemLine(fromUser, "Bạn đã từ chối nhận file \"" + safeName + "\".");
            return;
        }

        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle("Chọn nơi lưu file");
        chooser.setSelectedFile(new File(lastDirectory, safeName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            if (!responder.isExpired()) {
                responder.reject();
                addSystemLine(fromUser, "Bạn đã hủy nhận file \"" + safeName + "\".");
            }
            return;
        }
        File target = chooser.getSelectedFile();
        lastDirectory = chooser.getCurrentDirectory();

        if (target.exists() && JOptionPane.showConfirmDialog(this,
                "File \"" + target.getName() + "\" đã tồn tại. Ghi đè?", "Xác nhận",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            if (!responder.isExpired()) {
                responder.reject();
                addSystemLine(fromUser, "Bạn đã hủy nhận file \"" + safeName + "\" (không ghi đè).");
            }
            return;
        }
        if (responder.isExpired()) return;

        savedFileToOfferKey.put(target.getAbsolutePath(), transferKey(fromUser, safeName, false));
        responder.accept(target);
        addSystemLine(fromUser, "Đang nhận file \"" + safeName + "\" từ " + fromUser + "...");
    }

    // =====================================================================
    //  THOÁT
    // =====================================================================

    private void shutdown() {
        setVisible(false);
        dispose();
        // stop() có thể chặn (đóng socket, báo Tracker) -> làm ở luồng riêng rồi thoát hẳn JVM
        new Thread(() -> {
            try {
                peerService.stop();
            } catch (Exception ignored) {
                // đang thoát, bỏ qua
            } finally {
                System.exit(0);
            }
        }, "shutdown").start();
    }

    // =====================================================================
    //  TIỆN ÍCH
    // =====================================================================

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Mỗi dòng trong danh sách online: chấm xanh + tên + IP:Port + số tin chưa đọc. */
    private class PeerCellRenderer extends JPanel implements ListCellRenderer<PeerInfo> {
        private final JLabel lblName = new JLabel();
        private final JLabel lblAddr = new JLabel();
        private final JLabel lblBadge = new JLabel();

        PeerCellRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(8, 16, 8, 14));
            lblName.setIcon(new UITheme.DotIcon(UITheme.ONLINE, 10));
            lblName.setIconTextGap(8);
            lblName.setForeground(UITheme.TEXT);
            lblAddr.setFont(UITheme.FONT_SMALL);
            lblAddr.setForeground(UITheme.TEXT_MUTED);
            lblAddr.setBorder(new EmptyBorder(0, 18, 0, 0));
            lblBadge.setFont(UITheme.FONT_BOLD);
            lblBadge.setForeground(UITheme.PRIMARY);

            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            text.add(lblName);
            text.add(lblAddr);
            add(text, BorderLayout.CENTER);
            add(lblBadge, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends PeerInfo> list, PeerInfo value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            int unread = unreadCounts.getOrDefault(value.getUsername(), 0);
            lblName.setText(value.getUsername());
            lblName.setFont(unread > 0 ? UITheme.FONT_BOLD : UITheme.FONT_NORMAL);
            lblAddr.setText(value.getAddress());
            lblBadge.setText(unread > 0 ? "(" + unread + ")" : "");
            setBackground(isSelected ? UITheme.SELECTED_BG : UITheme.SIDEBAR_BG);
            return this;
        }
    }
}
