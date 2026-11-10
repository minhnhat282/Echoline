# Kế hoạch: Dialer tối giản, tập trung chặn và tự động trả lời

Trạng thái: bản nháp để duyệt. Chưa có dòng code nào của ứng dụng mới.

## 1. Mục tiêu

Một ứng dụng dialer viết mới hoàn toàn, với thứ tự ưu tiên:

1. **Chặn cuộc gọi** và **tự động trả lời** chính xác, phản hồi nhanh nhất có thể.
2. **Kiến trúc sạch**, dễ bảo trì, phần lõi kiểm thử được bằng JUnit thường (không cần thiết bị).
3. **Tối giản**: chỉ có những gì một dialer bắt buộc phải có, cộng với hai tính năng trọng tâm.
4. **Android 11 trở lên** (minSdk 30), **hỗ trợ hai SIM**.

Tính năng chặn / tự trả lời tương đương bộ mã nguồn đầu tiên (`src.zip`, đã vá), nhưng kiến trúc được thiết kế lại. Hai bộ mã còn lại (Fossify-based và Goodwy) chỉ dùng để tham khảo cách dùng API Telecom, không sao chép.

### Ngoài phạm vi (mặc định)

Danh bạ đầy đủ, quay số nhanh, hội nghị (conference), ghi chú người gọi, hẹn giờ, nhiều giao diện, mua hàng, quảng cáo, phân tích. Có thể thêm sau, nhưng không nằm trong kế hoạch này.

## 2. Ràng buộc nền tảng

| Hạng mục | Quyết định |
|---|---|
| minSdk | 30 (Android 11) |
| compileSdk / targetSdk | Bản ổn định mới nhất tại thời điểm tạo dự án (kiểm tra lại khi bắt đầu) |
| Vai trò hệ thống | `ROLE_DIALER` (bắt buộc), `ROLE_CALL_SCREENING` (xin nếu thử nghiệm cho thấy cần, xem mục 12) |
| Hai SIM | Dùng `PhoneAccountHandle`, `SubscriptionManager` |
| Ngôn ngữ | Kotlin, coroutines |
| UI | Jetpack Compose + Material 3 (đổi được, xem mục 13) |
| DI | Thủ công (một đối tượng `AppGraph`), không dùng Hilt/Dagger |
| JSON | `kotlinx.serialization` (mã sinh sẵn, không phản chiếu nên an toàn với R8) |
| Số điện thoại | `libphonenumber` (JVM thuần, dùng được trong module domain) |
| Lưu trữ | Một tệp JSON ghi nguyên tử (không Room, không SharedPreferences cho quy tắc) |

Lưu ý giấy phép: nếu có đoạn nào sao chép hoặc chuyển thể gần nguyên văn từ Fossify hoặc Goodwy thì ứng dụng phải tuân GPLv3. Kế hoạch này viết lại từ đầu, chỉ tham khảo hành vi API.

## 3. Tính năng

### 3.1 Bắt buộc của một dialer

- Bàn phím số, gọi đi (chọn SIM).
- Màn hình cuộc gọi: nghe, từ chối, cúp, tắt micro, loa ngoài, bàn phím DTMF, giữ máy.
- Thông báo cuộc gọi (đến, đang gọi) và full-screen intent khi khóa màn hình.
- Onboarding xin vai trò và quyền.

### 3.2 Chặn cuộc gọi (tương đương bản đầu)

- Quy tắc theo **số cụ thể** hoặc **wildcard** (tiền tố), hành động `BLOCK` / `ALLOW` / `AUTO_ANSWER`.
- Công tắc **chặn số ẩn**, **chặn số lạ** (số không có trong danh bạ).
- Số trong danh bạ được miễn wildcard và "chặn số lạ" (quy tắc số cụ thể vẫn áp dụng, kể cả với số trong danh bạ).
- Tạm ngừng chặn trong 2 ngày sau khi người dùng gọi số khẩn cấp (con số 2 ngày chỉnh được).
- Kiểu chặn: từ chối hẳn (reject) hoặc chỉ tắt chuông (silence).
- Nhật ký quyết định (60 dòng gần nhất) kèm thời gian xử lý, để tự kiểm tra tại sao một cuộc bị chặn hay không.
- Nhập / xuất quy tắc (đọc được tệp xuất của bản đầu).
- Tùy chọn: nhập một lần từ danh sách chặn của hệ thống (chỉ đọc).

### 3.3 Tự động trả lời (tương đương bản đầu)

- Kích hoạt bằng quy tắc `AUTO_ANSWER` (số cụ thể hoặc wildcard).
- Hai chế độ: **ẩn** (trả lời ngay, không hiện giao diện) và **có giao diện** (đổ chuông rồi trả lời sau N giây).
- Tắt micro khi trả lời (tùy chọn), ép âm thanh về loa trong hoặc tai nghe có dây, tự cúp sau N giây.
- Với wildcard: không tự trả lời nếu số đã có trong lịch sử cuộc gọi gần đây (cửa sổ phút chỉnh được).
- Không tự trả lời khi đang có cuộc gọi khác.
- Nhãn "Tự trả lời" trong nhật ký (nếu có màn hình nhật ký cuộc gọi, xem mục 13).

### 3.4 Mới so với bản đầu

- **Phạm vi theo SIM** cho từng quy tắc: mọi SIM, SIM 1, SIM 2.
- Wildcard theo tiền tố E.164, hỗ trợ cả số quốc tế (`+84 24*`).
- Đo thời gian phản hồi của bộ lọc ngay trong nhật ký.

## 4. Kiến trúc

### 4.1 Hai module Gradle

```
:domain   (Kotlin/JVM thuần)   quy tắc, engine, chuẩn hóa số, mã hóa JSON, máy trạng thái
:app      (Android)            lưu trữ, Telecom, UI
```

Tách `:domain` thành module JVM để **ép buộc** domain không import được Android, và chạy test chỉ mất vài giây mà không cần Android toolchain.

### 4.2 Cấu trúc gói

```
:domain
  model/      Rule, RuleAction, SimScope, FilterConfig, Decision, CallInfo
  number/     PhoneKeys (E.164, tiền tố), PatternParser
  engine/     DecisionEngine, RuleIndex, Facts (giao diện các dữ kiện)
  codec/      FilterConfigCodec (kotlinx.serialization, versioned)
  session/    AutoAnswerMachine (máy trạng thái thuần, nhận sự kiện, trả lệnh)

:app
  data/       FilterConfigStore, ContactsIndex, RecentNumbers, EmergencyTracker,
              DecisionLog, SimRepository, CallLogReader
  telecom/    CallScreeningServiceImpl, InCallServiceImpl, CallRegistry,
              CallHandle (giao diện), AudioController, AutoAnswerRunner
  ui/         theme/, dialpad/, incall/, rules/, settings/, log/
  AppGraph.kt, App.kt
```

Quy tắc phụ thuộc: `ui -> telecom/data -> domain`. `telecom` và `data` không biết `ui`. `domain` không biết ai.

### 4.3 Các nguyên tắc thiết kế

1. **Engine là hàm thuần**: `evaluate(call: CallInfo, config: FilterConfig, facts: Facts): Decision`. `Facts` cung cấp các dữ kiện tính lười (có trong danh bạ, đang có cuộc khác, có route riêng tư, có trong lịch sử, vừa gọi khẩn cấp). Engine chỉ hỏi dữ kiện khi sắp áp dụng một quyết định khác "đổ chuông".
2. **Một cấu hình bất biến, một nguồn sự thật**: `FilterConfig` (quy tắc và cài đặt chung một đối tượng) giữ trong `StateFlow`. Đường nóng chỉ đọc `.value`. Mọi thay đổi đi qua `update { }` được tuần tự hóa, ghi tệp nguyên tử, rồi mới công bố. Không có tình trạng quy tắc và cài đặt lệch phiên bản.
3. **Android bị bọc sau giao diện**: `CallHandle` (answer, reject, disconnect, state, callback) và `AudioController` (mute, route) có bản thật và bản giả. Nhờ vậy máy trạng thái tự trả lời kiểm thử được trên JVM với thời gian ảo.
4. **Hẹn giờ bằng coroutine có cấu trúc**: mỗi phiên tự trả lời chạy trong một `CoroutineScope` riêng, hủy là hủy sạch mọi hẹn giờ. Không có `Handler.postDelayed` rời rạc, nên không có hẹn giờ mồ côi.
5. **Mọi lệnh Telecom đi qua một chỗ có bắt ngoại lệ** (`CallHandle` bản thật), vì `answer`/`disconnect` có thể ném ngoại lệ khi cuộc gọi vừa kết thúc.
6. **Không trạng thái toàn cục lẻ tẻ**: `CallRegistry` là nơi duy nhất giữ các cuộc gọi đang sống, tự dọn theo trạng thái thật của `Call`.

## 5. Đường nóng: bộ lọc cuộc gọi

Mục tiêu: **quyết định xong trong vài mili-giây** khi bộ nhớ đệm đã ấm; luôn trả lời hệ thống trước hạn 5 giây.

### 5.1 Hai đường

`onScreenCall` chạy trên luồng chính, nên:

- **Đường nhanh (đồng bộ, trong bộ nhớ):** đủ cho đa số cuộc gọi.
  - đọc `FilterConfig` snapshot
  - chuẩn hóa số, tra `RuleIndex` (HashMap, khoảng 15 lần tra, không phụ thuộc số quy tắc)
  - dữ kiện "có trong danh bạ" lấy từ `ContactsIndex` trong bộ nhớ
  - dữ kiện "có trong lịch sử" lấy từ `RecentNumbers` trong bộ nhớ
  - trả lời hệ thống ngay, rồi mới ghi nhật ký
- **Đường chậm (bất đồng bộ):** chỉ khi một dữ kiện chưa sẵn sàng (ví dụ danh bạ chưa nạp xong sau khi khởi động).
  - chạy ở dispatcher IO, có đồng hồ canh 3 giây: hết giờ thì cho đổ chuông
  - kết quả đến muộn vẫn được áp dụng cho cuộc đang đổ chuông (từ chối hoặc tự trả lời có giao diện)

### 5.2 Bộ nhớ đệm thay cho truy vấn

- **`ContactsIndex`**: tập các khóa E.164 và dạng nội địa của mọi số trong danh bạ. Nạp khi khởi động, làm mới bằng `ContentObserver` (có debounce). Hàng chục nghìn số vẫn chỉ vài MB. Khi chưa sẵn sàng thì rơi về đường chậm.
- **`RecentNumbers`**: vòng nhớ các số gọi vào/ra gần đây. Khởi tạo từ call log một lần, sau đó được `InCallService` cập nhật mỗi khi cuộc gọi kết thúc (app thấy mọi cuộc gọi). Đường nóng không bao giờ truy vấn call log.
- **`EmergencyTracker`**: thời điểm cuộc gọi khẩn cấp gần nhất (lưu bền).

### 5.3 Chia sẻ kết quả với `InCallService`

Bộ lọc tạo một `PendingDecision` trong `CallRegistry` (khóa: số cộng thời điểm tạo cuộc gọi, có ngưỡng dung sai, chỉ chấp nhận khi đúng một ứng viên). `InCallService` đón đúng lần đánh giá đó, không tính lại. Ghi lại *điều đã trả lời hệ thống* tách khỏi *kết quả đánh giá*, để biết khi nào cần áp dụng bù.

### 5.4 Ngân sách và đo lường

Mỗi quyết định ghi `thời gian từ onScreenCall đến respondToCall` vào nhật ký. Mục tiêu đề xuất: đường nhanh dưới 10 ms ở p95 trên thiết bị tầm trung; đường chậm dưới 3 s tuyệt đối. Con số này cần đo thật trên máy của bạn (mục 11).

## 6. Bộ quy tắc (đặc tả)

Thứ tự ưu tiên, dừng ở bước đầu tiên khớp:

1. Số ẩn (không có số hoặc `PRESENTATION_RESTRICTED`): theo công tắc chặn số ẩn.
2. Quy tắc số cụ thể khớp (và phạm vi SIM khớp): áp dụng, kể cả khi số có trong danh bạ.
3. Wildcard khớp, tiền tố dài nhất thắng: áp dụng, trừ khi số có trong danh bạ (thì đổ chuông).
4. Không khớp quy tắc nào: nếu bật "chặn số lạ" và số không có trong danh bạ thì chặn, ngược lại đổ chuông.
5. Lớp ngoài cùng: mọi quyết định **chặn** bị hủy nếu đang trong thời gian ân hạn sau cuộc gọi khẩn cấp.

Chi tiết:

- **Wildcard chuẩn hóa thành tiền tố E.164** (`02*` thành `+842*`, `+84 24*` thành `+8424*`), nên không phụ thuộc quốc gia hiện tại. Dạng `8424*` (thiếu `+`, bắt đầu bằng mã quốc gia) bị từ chối vì mơ hồ. Cảnh báo khi tiền tố quá rộng (ít hơn 3 chữ số có nghĩa).
- **Phạm vi SIM** là một điều kiện của quy tắc, kiểm tra trước khi coi là khớp.
- `AUTO_ANSWER` chỉ thành quyết định nếu: tính năng bật, không có cuộc khác, có route riêng tư, số không trong danh bạ, và (với wildcard) không có trong lịch sử gần đây.
- Mọi lỗi bất ngờ khi đánh giá dẫn tới **đổ chuông**, kèm lý do trong nhật ký (không bao giờ chặn nhầm khi có lỗi).

## 7. Tự động trả lời

### 7.1 Máy trạng thái (`AutoAnswerMachine`, thuần, trong `:domain`)

```
Pending(delay) -> Answering -> Verifying(route) -> Active -> Ended
        \________ (cuộc bị người dùng nghe/từ chối/cúp) ______/
```

Vào: sự kiện (đã vào trạng thái RINGING, ACTIVE, DISCONNECTED, thay đổi âm thanh, hết giờ).
Ra: lệnh (answer, disconnect, mute, ép route, hủy hẹn giờ).
Runner trong `:app` chuyển lệnh thành thao tác Telecom và đẩy sự kiện về máy.

### 7.2 Chế độ ẩn và chế độ có giao diện

- **Ẩn**: `CallRegistry` đánh dấu cuộc gọi là ẩn; `InCallService` không mở UI, không đăng thông báo. Bộ lọc tắt chuông ngay từ đầu (`setSilenceCall`).
- **Có giao diện**: để đổ chuông bình thường, trả lời sau N giây. Khi tắt micro, đồng thời cập nhật trạng thái mute mà UI hiển thị, để nút micro không bị sai.

### 7.3 Âm thanh

- Qua `AudioController` có hai bản cài đặt: `CallAudioState` (Android 11 đến 13) và `CallEndpoint` (từ Android 14), chọn theo phiên bản.
- Sau khi nghe máy: tắt micro ngay, ép route về loa trong hoặc tai nghe có dây, xác minh theo **sự kiện thay đổi âm thanh** (không thăm dò theo thời gian). Quá hạn chót ngắn mà chưa xác minh được thì cúp.
- Cuộc ẩn mà âm thanh rời route riêng tư giữa chừng thì cúp ngay.
- Nếu tắt micro ở cấp hệ thống phải dùng thì ghi cờ bền trước, để lần khởi động sau khôi phục được nếu tiến trình bị kill giữa chừng.

### 7.4 Các tình huống biên bắt buộc xử lý (và có test)

1. Có cuộc gọi thứ hai khi cuộc tự trả lời đang chạy: cuộc đã nghe thì cúp; cuộc còn đang chờ trả lời thì chỉ hủy hẹn giờ, để cuộc đó đổ chuông bình thường (không từ chối người gọi).
2. Chuông đã bị tắt nhưng tự trả lời không thể thực hiện (route, có cuộc khác): tự phát chuông thay thế, không để cuộc gọi hiện ra trong im lặng.
3. Người gọi cúp trước khi hết giờ chờ, hoặc người dùng tự nghe/từ chối: hủy sạch phiên.
4. Lệnh `answer`/`disconnect` ném ngoại lệ: bắt, ghi nhật ký, không sập app.
5. Người dùng bật lại micro trong cuộc có giao diện: hủy tự cúp, giữ cuộc gọi.
6. Kết quả đánh giá đến sau khi hệ thống đã được trả lời "đổ chuông": áp dụng bù lên cuộc đang đổ chuông.
7. Cuộc gọi ẩn bị kẹt trong trạng thái chưa trả lời: tự dọn theo trạng thái thật, không làm tắt tính năng cho các cuộc sau.
8. Service bị hủy: dọn toàn bộ phiên.

## 8. Hai SIM

- **Nhận diện SIM của cuộc gọi**: `Call.Details.accountHandle` (có cả trong bộ lọc). `SimRepository` giữ ánh xạ `PhoneAccountHandle` sang khe SIM và tên hiển thị, cập nhật qua `SubscriptionManager.OnSubscriptionsChangedListener`. Cần `READ_PHONE_STATE`.
- **Quy tắc theo SIM**: `SimScope { ANY, SLOT_1, SLOT_2 }`. Lưu theo khe, không theo `subscriptionId` (id đổi khi đổi SIM). eSIM và đổi SIM là điểm cần thử thật.
- **Gọi đi**: người dùng chọn SIM mặc định (SIM 1, SIM 2, hay hỏi mỗi lần); gọi bằng `TelecomManager.placeCall` kèm `EXTRA_PHONE_ACCOUNT_HANDLE`.
- **Hiển thị**: màn hình cuộc gọi và quy tắc luôn nói rõ SIM nào.
- Chưa làm: cài đặt tự trả lời riêng từng SIM (độ trễ, thời lượng). Phạm vi theo SIM của quy tắc đã đủ cho nhu cầu "chỉ tự trả lời trên SIM 2".

## 9. Giao diện (tối giản)

| Màn hình | Nội dung |
|---|---|
| Bàn phím số | Nhập số, chọn SIM, gọi |
| Cuộc gọi | Đến, đi, đang gọi; nghe/từ chối/cúp, mute, loa, DTMF, giữ máy; hiển thị SIM |
| Quy tắc | Danh sách, thêm/sửa/xóa, tìm kiếm, nhập/xuất |
| Cài đặt | Công tắc chặn số ẩn/số lạ, kiểu chặn, tự trả lời (độ trễ, thời lượng, tắt micro, cửa sổ lịch sử), SIM mặc định, ân hạn khẩn cấp |
| Nhật ký quyết định | 60 dòng gần nhất kèm lý do và thời gian xử lý |
| Onboarding | Vai trò dialer, quyền, full-screen intent |

Màn hình cuộc gọi là activity riêng (hiển thị được trên màn hình khóa); còn lại một activity duy nhất với điều hướng Compose. Không có thanh tab, không có danh bạ.

## 10. Quyền và thành phần Manifest

Quyền: `READ_CONTACTS`, `READ_CALL_LOG`, `READ_PHONE_STATE`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `POST_NOTIFICATIONS` (Android 13+), `USE_FULL_SCREEN_INTENT` (cần xin từ Android 14 nếu chưa có mặc định), `VIBRATE`. Kiểm tra lại tập tối thiểu khi code.

Thành phần: `InCallServiceImpl` (`BIND_INCALL_SERVICE`, metadata `IN_CALL_SERVICE_UI`), `CallScreeningServiceImpl` (`BIND_SCREENING_SERVICE`), activity cuộc gọi, activity chính, intent-filter `DIAL`/`tel:` cho dialer mặc định.

## 11. Kiểm thử

- **Test JVM (`:domain`)**: engine theo bảng (mọi nhánh ưu tiên), chuẩn hóa số và wildcard, codec JSON (kể cả dữ liệu hỏng và tệp xuất của bản đầu), máy trạng thái tự trả lời với thời gian ảo (đủ các tình huống biên ở mục 7.4).
- **Test JVM (`:app`)** với `CallHandle` và `AudioController` giả: `CallRegistry`, `PendingDecision`, `FilterConfigStore` (ghi nguyên tử, tuần tự hóa).
- **Thử trên thiết bị thật**: ma trận gồm chặn số cụ thể, wildcard, số ẩn, số lạ, tự trả lời ẩn và có giao diện, cuộc thứ hai chen vào, người gọi cúp sớm, gọi khẩn cấp rồi nhận gọi lại, hai SIM, khóa màn hình.
- **Trình giả lập** chỉ có một SIM nhưng thử được luồng cuộc gọi bằng `adb emu gsm call <số>`.
- **Đo độ trễ** bằng chính nhật ký quyết định (mục 5.4).

Giới hạn cần nói rõ: môi trường mình làm việc không có Gradle hay trình biên dịch, nên mình **không** biên dịch hay chạy test được. Mỗi chặng sẽ giao kèm danh sách "cần kiểm tra khi build", và bạn chạy `./gradlew :domain:test` rồi build trong Android Studio để báo lỗi lại.

## 12. Rủi ro và điều cần kiểm chứng sớm

| Rủi ro | Cách xử lý |
|---|---|
| Bộ lọc có được gọi khi app chỉ là dialer mặc định, hay cần thêm `ROLE_CALL_SCREENING`? | Thử ngay ở mốc M2, trước khi làm tiếp phần tự trả lời. Goodwy xin vai trò Caller ID khi bật chặn số lạ/ẩn, gợi ý có thể cần |
| `ContactsIndex` chưa nạp xong lúc có cuộc gọi đầu tiên sau khởi động | Rơi về đường chậm; khởi động nạp ngay khi tiến trình bắt đầu |
| Hành vi `setSilenceCall`, `setSkipCallLog` khác nhau giữa hãng máy | Thử trên máy thật; lớp phản hồi gom ở một chỗ để chỉnh |
| Route âm thanh khác nhau giữa hãng (Bluetooth, tai nghe) | Có hạn chót xác minh và cúp an toàn; thử nhiều thiết bị nếu có |
| Hai SIM: eSIM, đổi SIM, ánh xạ handle sang khe | Lưu theo khe; thử thật trên máy hai SIM |
| Cửa hàng ứng dụng có thể xem xét kỹ việc tự trả lời im lặng kèm tắt micro | Mặc định phát hành cá nhân; chưa kiểm tra chính sách cụ thể |

## 13. Quyết định cần bạn chốt

Mỗi mục có mặc định để mình bắt đầu ngay nếu bạn không đổi.

| # | Quyết định | Mặc định |
|---|---|---|
| 1 | UI toolkit | Compose + Material 3 (ngắn gọn hơn; APK nặng hơn View XML một chút) |
| 2 | Có màn hình nhật ký cuộc gọi (recents) trong app không | Có bản rất gọn, chỉ để thấy nhãn "Tự trả lời" và chặn/cho phép nhanh; bỏ được |
| 3 | Ngôn ngữ giao diện | Tiếng Việt và tiếng Anh |
| 4 | Nhập từ danh sách chặn hệ thống | Có, một lần, chỉ đọc |
| 5 | Nhập tệp xuất của bản đầu | Có (đọc được cả quy tắc cũ dạng `02*`) |
| 6 | Phát hành | Cá nhân (không lên cửa hàng) |
| 7 | applicationId, tên ứng dụng, icon | Bạn đặt; mình dùng tên tạm |
| 8 | Cài đặt tự trả lời riêng theo SIM | Chưa làm (chỉ có phạm vi SIM của quy tắc) |

## 14. Các mốc giao hàng

Mỗi mốc giao một tệp zip, kèm ghi chú "cần kiểm tra khi build" và tiêu chí nghiệm thu.

| Mốc | Nội dung | Nghiệm thu |
|---|---|---|
| **M0** | Khung dự án hai module, version catalog, `:domain` với model, chuẩn hóa số, engine, codec, và test | `./gradlew :domain:test` xanh |
| **M1** | `:app` tầng `data`: `FilterConfigStore`, `ContactsIndex`, `RecentNumbers`, `EmergencyTracker`, `DecisionLog`, `SimRepository` | Test JVM của store xanh; app build được |
| **M2** | Bộ lọc cuộc gọi + `CallRegistry`, đường nhanh và đường chậm; chặn hoạt động; onboarding vai trò | Trên máy thật: chặn số cụ thể, số ẩn, số lạ; đo độ trễ; **xác nhận có cần `ROLE_CALL_SCREENING`** |
| **M3** | `InCallService`, màn hình cuộc gọi, bàn phím số, thông báo, chọn SIM khi gọi đi | Gọi đi và nhận cuộc gọi trên cả hai SIM |
| **M4** | Tự trả lời: máy trạng thái, runner, `AudioController`, chế độ ẩn và có giao diện | Ma trận tình huống biên ở mục 7.4 |
| **M5** | Màn hình quy tắc, cài đặt, nhật ký, nhập/xuất, nhập danh sách chặn hệ thống | Thêm/sửa/xóa quy tắc có hiệu lực ngay |
| **M6** | Hoàn thiện: build release có R8, đo hiệu năng, dọn lỗi, tài liệu ngắn về kiến trúc | Chạy đủ ma trận thử trên bản release |

Quy mô ước lượng (dòng code, chỉ để định hướng): `:domain` khoảng 1.200, `data` khoảng 900, `telecom` khoảng 1.500, `ui` khoảng 2.000.

## Phụ lục A: Từ bản đầu sang bản mới

| Bản đầu | Bản mới |
|---|---|
| `CallRuleEngine` + `RuleIndex` | `:domain/engine` (thuần, thêm phạm vi SIM) |
| `PhoneNumberKeys` | `:domain/number` |
| `CallRuleJson` + `CallRuleStore` | `:domain/codec` + `data/FilterConfigStore` (cấu hình bất biến, `StateFlow`) |
| `DecisionRegistry` + `PendingDecision` | `telecom/CallRegistry` |
| `AutoAnswerController` | `:domain/session` (máy trạng thái) + `telecom/AutoAnswerRunner` |
| `CallRuleEvaluator` (`EngineEnv`) | Các triển khai `Facts` trong `data/` |
| `DecisionLog` | `data/DecisionLog` (thêm thời gian xử lý) |
| `EmergencyGuard` | `data/EmergencyTracker` |
| `CallHistoryChecker` | `data/RecentNumbers` (bộ nhớ) + `CallLogReader` (chỉ để nạp ban đầu) |
| `CallRuleTransfer` | `data/RulesTransfer` (đọc được định dạng cũ) |
| `ManageBlockedNumbersActivity` | `ui/rules` (Compose) |

## Phụ lục B: Lỗi đã gặp ở bản đầu, kiến trúc mới ngăn chặn thế nào

| Lỗi | Cách ngăn |
|---|---|
| Trạng thái "kẹt" làm tắt tự trả lời | `CallRegistry` tự dọn theo trạng thái thật của `Call`; phiên nằm trong scope coroutine bị hủy cùng service |
| Chuông bị tắt nhưng cuộc gọi hiện trong im lặng | Máy trạng thái có nhánh "phát chuông thay thế" bắt buộc khi tự trả lời không thực hiện được |
| Cuộc thứ hai làm cuộc đang chờ bị từ chối | Máy trạng thái phân biệt `Pending` và `Active` |
| Mất quy tắc âm thầm khi JSON hỏng hoặc bị minify | Tệp ghi nguyên tử, bản gốc dữ liệu hỏng được giữ lại; mã hóa không phản chiếu |
| Hẹn giờ ném ngoại lệ làm sập app | Mọi lệnh Telecom đi qua `CallHandle` có bắt ngoại lệ; hẹn giờ là coroutine có xử lý lỗi |
| Ghi nhật ký chặn đường phản hồi | Phản hồi trước, ghi nhật ký sau, ở luồng riêng |
| Kết quả đến muộn bị bỏ | `PendingDecision` hoàn tất sau vẫn được áp dụng bù |
| Quy tắc và cài đặt lệch phiên bản | Một `FilterConfig` bất biến duy nhất |
| Wildcard phụ thuộc quốc gia đang chọn | Wildcard lưu dạng tiền tố E.164 |
| Chặn nhầm cuộc gọi lại từ dịch vụ khẩn cấp | Ân hạn sau cuộc gọi khẩn cấp là lớp ngoài cùng của engine |
