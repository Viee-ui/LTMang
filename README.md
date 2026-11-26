# Đồ án Lập trình mạng - Chat và truyền file Hybrid P2P (TCP + UDP)

Java SE thuần (JDK 9 trở lên, khuyến nghị 17+), TCP Socket, UDP Broadcast Discovery, đa luồng, Swing. Không database, không framework ngoài.

## Cấu trúc
```
src/
  common/     Packet, PacketType, PacketIO, PeerInfo,
              Protocol, TrackerDiscovery                  (giao thức dùng chung + tìm Tracker LAN)
  server/     TrackerServer                               (quản lý danh sách online + UDP Discovery)
  client/     PeerNode, ClientMain                        (lõi mạng, auto-reconnect + điểm khởi động)
  client/ui/  LoginFrame, ChatMainFrame, ChatUIListener,
              PeerService, FileOfferResponder, UITheme,
              MockPeerService, UiPreview                  (giao diện Swing)
test/NetTest.java                                         (kiểm thử tích hợp, cần JDK 17+)
```

## Chạy
1. Biên dịch: `build.bat` (Windows) hoặc `./build.sh` (Linux/macOS).
2. Chạy Tracker (cổng TCP mặc định 9000, UDP Discovery mặc định 9001): `run-tracker.bat` / `./run-tracker.sh [cổng]`. Tracker in ra IP LAN của máy.
3. Chạy Client trên mỗi máy: `run-client.bat` / `./run-client.sh`, bấm **"Tìm trong LAN"** (hoặc tự nhập Tracker IP), điền Username và đăng nhập.

Demo hai máy: dùng nút **"Tìm trong LAN"** hoặc nhập IP LAN của máy Tracker vào ô Tracker IP trên CẢ HAI máy (kể cả peer chạy cùng máy Tracker,
đừng dùng 127.0.0.1). Cho phép Java qua Windows Firewall (mạng Private, cả TCP và UDP).

Xem thử giao diện không cần Tracker: `run-ui-preview.bat` / `./run-ui-preview.sh`.

Chạy kiểm thử tích hợp:
```
javac -encoding UTF-8 -cp out -d out test/NetTest.java
java -cp out NetTest
```

## Giao thức
- Tìm Tracker tự động (UDP Broadcast): Client gửi `P2P_CHAT_DISCOVER_V1` tới cổng UDP 9001 -> Tracker phản hồi `P2P_CHAT_TRACKER_V1:<tcpPort>`.
- Đăng nhập & Heartbeat: REGISTER -> REGISTER_OK / REGISTER_FAIL; Tracker broadcast ONLINE_LIST khi có thay đổi; PING/PONG định kỳ phát hiện rớt mạng.
- Tự động kết nối lại Tracker: khi mất kết nối Tracker, chat P2P đang mở vẫn chạy bình thường và PeerNode tự thử đăng ký lại theo chu kỳ tăng dần (Exponential Backoff: 2s -> 4s -> 8s -> 15s).
- Nhắn tin: MESSAGE (kết nối TCP bền vững trực tiếp giữa 2 peer) -> MESSAGE_ACK.
- Gửi file (kết nối riêng): FILE_OFFER -> FILE_ACCEPT/FILE_REJECT -> FILE_CHUNK x N (16 KB) -> FILE_END (SHA-256) -> FILE_DONE/FILE_ERROR.
- Tracker không trung chuyển tin nhắn hay file.
