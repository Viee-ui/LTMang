package client.ui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Enumeration;

/**
 * Bảng màu, font và các component "phẳng" dùng chung cho toàn bộ giao diện (Modern Flat UI).
 */
public final class UITheme {
    private UITheme() { }

    // ===== Bảng màu =====
    public static final Color SIDEBAR_BG  = Color.decode("#F4F6F9");
    public static final Color CHAT_BG     = Color.WHITE;
    public static final Color PRIMARY     = Color.decode("#2563EB");
    public static final Color TEXT        = Color.decode("#111827");
    public static final Color TEXT_MUTED  = Color.decode("#6B7280");
    public static final Color BORDER      = Color.decode("#E5E7EB");
    public static final Color FIELD_BORDER = Color.decode("#D1D5DB");
    public static final Color ONLINE      = Color.decode("#22C55E");
    public static final Color OFFLINE     = Color.decode("#EF4444");
    public static final Color SELECTED_BG = Color.decode("#DBEAFE");
    public static final Color ERROR       = Color.decode("#DC2626");

    // ===== Font =====
    public static final String FONT_FAMILY = "Segoe UI";
    public static final Font FONT_NORMAL = new Font(FONT_FAMILY, Font.PLAIN, 13);
    public static final Font FONT_BOLD   = new Font(FONT_FAMILY, Font.BOLD, 13);
    public static final Font FONT_TITLE  = new Font(FONT_FAMILY, Font.BOLD, 15);
    public static final Font FONT_SMALL  = new Font(FONT_FAMILY, Font.PLAIN, 12);

    /** Gọi 1 lần ở main(), TRƯỚC khi tạo bất kỳ cửa sổ nào. */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // dùng L&F mặc định nếu hệ thống không hỗ trợ
        }
        // Đổi toàn bộ font mặc định sang Segoe UI 13 (JOptionPane, JFileChooser, JList...)
        for (Enumeration<Object> keys = UIManager.getDefaults().keys(); keys.hasMoreElements(); ) {
            Object key = keys.nextElement();
            if (UIManager.get(key) instanceof FontUIResource) {
                UIManager.put(key, new FontUIResource(FONT_NORMAL));
            }
        }
        UIManager.put("OptionPane.yesButtonText", "Có");
        UIManager.put("OptionPane.noButtonText", "Không");
        UIManager.put("OptionPane.okButtonText", "OK");
        UIManager.put("OptionPane.cancelButtonText", "Hủy");
        UIManager.put("ProgressBar.foreground", PRIMARY);
    }

    // ===== Tiện ích chuỗi =====

    /**
     * Chuyển văn bản thô của người dùng thành HTML an toàn để nhét vào JTextPane:
     * - escape &, <, > để người dùng không thể chèn thẻ HTML (ví dụ &lt;img src=...&gt;);
     * - \n thành &lt;br&gt;;
     * - chuỗi quá dài không có khoảng trắng (URL, "aaaa...") được chèn thêm 1 khoảng trắng sau mỗi 32 ký tự,
     *   vì Swing HTML chỉ xuống dòng ở khoảng trắng/dấu câu, không tự bẻ từ.
     */
    public static String toHtml(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        int run = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                run = 0;
            } else if (++run > 32) {
                sb.append(' ');
                run = 1;
            }
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\n': sb.append("<br>");   break;
                case '\r': break;
                case '\t': sb.append("&nbsp;&nbsp;&nbsp;&nbsp;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 1536 -> "1.5 KB", 5242880 -> "5.0 MB". */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    // ===== Tiện ích style =====

    public static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER),
                BorderFactory.createEmptyBorder(7, 10, 7, 10));
    }

    public static void styleField(JTextField field) {
        field.setFont(FONT_NORMAL);
        field.setForeground(TEXT);
        field.setBorder(fieldBorder());
    }

    private static Color shade(Color c, double factor) {
        return new Color(
                Math.max(0, Math.min(255, (int) (c.getRed() * factor))),
                Math.max(0, Math.min(255, (int) (c.getGreen() * factor))),
                Math.max(0, Math.min(255, (int) (c.getBlue() * factor))));
    }

    // ===== Component phẳng =====

    /** Nút phẳng bo góc, tự vẽ nên màu hiển thị giống nhau trên mọi Look & Feel. */
    public static class FlatButton extends JButton {
        private final Color base;
        private boolean hovering;

        public FlatButton(String text, Color background, Color foreground) {
            super(text);
            this.base = background;
            setForeground(foreground);
            setFont(FONT_BOLD);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovering = true;  repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hovering = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill;
            if (!isEnabled())                fill = shade(base, 1.0).brighter();
            else if (getModel().isPressed()) fill = shade(base, 0.80);
            else if (hovering)               fill = shade(base, 0.90);
            else                             fill = base;
            g2.setColor(isEnabled() ? fill : new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 110));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g); // vẽ chữ
        }
    }

    /** Ô nhập có chữ gợi ý mờ khi đang trống. */
    public static class HintTextField extends JTextField {
        private final String hint;

        public HintTextField(String hint) {
            this.hint = hint;
            styleField(this);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(new Color(156, 163, 175));
                g2.setFont(getFont());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(hint, in.left, y);
                g2.dispose();
            }
        }
    }

    /** Chấm tròn màu (trạng thái Online/Offline), tự vẽ nên không phụ thuộc font emoji. */
    public static class DotIcon implements Icon {
        private final Color color;
        private final int size;

        public DotIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, size, size);
            g2.dispose();
        }

        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }
    }
}
