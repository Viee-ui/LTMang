# Đồ án Lập trình mạng - Chat và truyền file Hybrid P2P (TCP)

Java SE thuần (JDK 9 trở lên, khuyến nghị 17+), TCP Socket, đa luồng, Swing. Không database, không framework ngoài.

## Cấu trúc
```
src/
  common/     Packet, PacketType, PacketIO, PeerInfo      (giao thức dùng chung)
  server/     TrackerServer                               (quản lý danh sách online)
  client/     PeerNode, ClientMain                        (lõi mạng + điểm khởi động)
  client/ui/  LoginFrame, ChatMainFrame, ChatUIListener,
              PeerService, FileOfferResponder, UITheme,
              MockPeerService, UiPreview                  (giao diện Swing)
test/NetTest.java                                         (kiểm thử tích hợp, cần JDK 17+)
```

## Chạy
1. Biên dịch: `build.bat` (Windows) hoặc `./build.sh` (Linux/macOS).
2. Chạy Tracker (cổng mặc định 9000): `run-tracker.bat` / `./run-tracker.sh [cổng]`. Tracker in ra IP LAN của máy.
3. Chạy Client trên mỗi máy: `run-client.bat` / `./run-client.sh`, nhập Username và Tracker IP.

Demo hai máy: nhập IP LAN của máy Tracker vào ô Tracker IP trên CẢ HAI máy (kể cả peer chạy cùng máy Tracker,
đừng dùng 127.0.0.1). Cho phép Java qua Windows Firewall (mạng Private).

Xem thử giao diện không cần Tracker: `run-ui-preview.bat` / `./run-ui-preview.sh`.

Chạy kiểm thử tích hợp:
```
javac -encoding UTF-8 -cp out -d out test/NetTest.java
java -cp out NetTest
```

## Giao thức
- Đăng nhập: REGISTER -> REGISTER_OK / REGISTER_FAIL; Tracker broadcast ONLINE_LIST khi có thay đổi.
- Nhắn tin: MESSAGE (kết nối TCP bền vững trực tiếp giữa 2 peer) -> MESSAGE_ACK.
- Gửi file (kết nối riêng): FILE_OFFER -> FILE_ACCEPT/FILE_REJECT -> FILE_CHUNK x N (16 KB) -> FILE_END -> FILE_DONE/FILE_ERROR.
- Tracker không trung chuyển tin nhắn hay file.
