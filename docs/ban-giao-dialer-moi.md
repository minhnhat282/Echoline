# Bản bàn giao: viết dialer mới tối giản (chặn và tự động trả lời)

Dành cho phiên làm việc mới. Đọc hết tài liệu này, rồi đọc `ke-hoach-dialer-toi-gian.md` (kế hoạch chi tiết), sau đó bắt đầu từ mốc M0.

## 0. Việc đầu tiên cần làm trong phiên mới

1. Nhờ người dùng **tải lên lại** các tệp ở mục 3 (tệp cũ có thể không còn trong phiên mới). Tối thiểu cần `ke-hoach-dialer-toi-gian.md`. Rất nên có `src-patched.zip` để đối chiếu hành vi.
2. Kiểm tra môi trường: chạy `which kotlinc kotlin gradle java` và thử mạng. Phiên trước **không có** Kotlin, Gradle hay mạng (chỉ có Java). Nếu phiên mới cũng vậy thì **không biên dịch hay chạy test được**; phải nói rõ điều đó trong mọi lần giao hàng.
3. Hỏi người dùng chốt ba quyết định ở mục 4 (hoặc xác nhận dùng mặc định), rồi làm **M0**.

## 1. Người dùng và cách làm việc

- Người dùng viết và nhận trả lời bằng **tiếng Việt**; trả lời bằng tiếng Việt.
- Làm việc trong **Android Studio**, tự build và chạy trên thiết bị thật. Họ sẽ báo lại lỗi biên dịch và kết quả thử.
- Muốn câu trả lời gọn, đi thẳng vào kết quả. Khi giao hàng: tệp đặt trong `/mnt/user-data/outputs/` rồi gọi `present_files`, mô tả ngắn, nói rõ điều chưa kiểm chứng.
- Người dùng đã bắt được một lỗi của mình: mình từng khẳng định "chỉ có một test" trong khi lần liệt kê zip bị cắt ở 300 dòng, nên bỏ sót các test khác. **Bài học:** liệt kê đầy đủ rồi mới kết luận; không khẳng định "không có X" khi chưa xem hết.
- Người dùng cũng nhờ một AI khác (Gemini) đánh giá các thay đổi. Phần lớn nhận xét đó chung chung hoặc sai so với code (ví dụ "Block luôn thắng" sẽ phá quy tắc cho phép riêng số). **Bài học:** đối chiếu mọi nhận xét với code trước khi đồng ý.

## 2. Mục tiêu

Một ứng dụng dialer **viết mới hoàn toàn**: tối giản, phản hồi chặn / tự trả lời nhanh nhất có thể, kiến trúc sạch dễ bảo trì, **Android 11 trở lên** (minSdk 30), **hỗ trợ hai SIM**. Tính năng trọng tâm: **chặn** và **tự động nghe**, tương đương bộ mã đầu tiên người dùng đưa (đặc tả hành vi ở mục 6). Kiến trúc do mình tự thiết kế (đã có trong kế hoạch).

**Ngoài phạm vi:** việc thêm tính năng vào "right dialer" (`Dialer-main`, Goodwy Dialer) đã được **tạm gác**, người dùng bảo bỏ qua. Không làm.

## 3. Các tệp và vai trò

| Tệp | Vai trò |
|---|---|
| `ke-hoach-dialer-toi-gian.md` | Kế hoạch đã soạn: kiến trúc hai module, đường nóng, quy tắc, tự trả lời, hai SIM, mốc M0 đến M6, rủi ro, quyết định |
| `src.zip` | Bộ mã đầu tiên (Fossify Phone, gói `com.minhnhat.mndialer`), bản gốc người dùng đưa |
| `src-patched.zip`, `changes.patch` | Bản mình đã vá (mục 7). **Chưa biên dịch hay chạy test.** Nguồn đối chiếu hành vi chính |
| `Dialer-main.zip` | Goodwy Dialer (`com.goodwy.dialer`). Chỉ để tham khảo cách dùng API Telecom. Không sao chép |
| thư viện goodwy-commons | Người dùng sẵn lòng gửi, chỉ để tham khảo nếu cần. Quyết định hiện tại là **không dùng** |

Các tệp mã nguồn quan trọng trong `src-patched.zip` (đường dẫn `src/main/kotlin/com/minhnhat/mndialer/`):
`helpers/CallRuleEngine.kt`, `PhoneNumberKeys.kt`, `CallRuleJson.kt`, `CallRuleStore.kt`, `CallRuleEvaluator.kt`, `AutoAnswerController.kt`, `DecisionRegistry.kt`, `DecisionLog.kt`, `EmergencyGuard.kt`, `CallHistoryChecker.kt`, `CallRuleTransfer.kt`; `services/SimpleCallScreeningService.kt`, `CallService.kt`; test trong `src/test/kotlin/.../helpers/`.

Giấy phép: bộ mã đầu và Goodwy xuất phát từ Fossify (GPLv3). Mã mới viết lại từ đầu, chỉ tham khảo hành vi API. Nếu sao chép hoặc chuyển thể gần nguyên văn thì phải tuân GPLv3.

## 4. Quyết định

### Đã chốt

- Viết app mới hoàn toàn, kiến trúc do mình thiết kế.
- Không dùng goodwy-commons (chỉ dùng AndroidX).
- minSdk 30, hai SIM, tập trung chặn và tự trả lời.
- Không làm: ngoại lệ "số vừa gọi đi", và phản chiếu chặn số cụ thể sang kho chặn của hệ thống. (Quyết định này áp dụng cho bộ mã cũ; ở app mới chỉ **đọc một lần** danh sách chặn hệ thống để nhập, không ghi lại.)

### Chưa chốt (mặc định đề xuất, hỏi người dùng lúc bắt đầu)

1. **UI toolkit**: Compose + Material 3 (mặc định) hay View XML.
2. **Màn hình nhật ký cuộc gọi (recents)** trong app: có bản rất gọn (mặc định) hay bỏ.
3. **applicationId, tên ứng dụng, icon**: người dùng đặt; nếu chưa có thì dùng tên tạm.

Các quyết định còn lại (ngôn ngữ vi và en, phát hành cá nhân, nhập tệp xuất bản cũ, nhập danh sách chặn hệ thống, cài đặt tự trả lời theo SIM chưa làm) xem mục 13 của kế hoạch.

## 5. Kiến trúc (tóm tắt, chi tiết trong kế hoạch)

- Hai module: `:domain` (Kotlin/JVM thuần: model, chuẩn hóa số, engine, codec JSON, máy trạng thái tự trả lời) và `:app` (data, telecom, ui).
- Engine là hàm thuần `evaluate(call, config, facts)`; các dữ kiện (danh bạ, cuộc khác, route riêng tư, lịch sử, khẩn cấp) tính lười.
- `FilterConfig` bất biến (quy tắc và cài đặt chung một đối tượng) giữ trong `StateFlow`, ghi tệp JSON nguyên tử.
- Android bọc sau giao diện (`CallHandle`, `AudioController`) để test máy trạng thái bằng thời gian ảo.
- Hẹn giờ bằng coroutine có cấu trúc, không dùng `Handler.postDelayed` rời rạc.
- JSON dùng `kotlinx.serialization`; số điện thoại dùng `libphonenumber`.
- Đường nóng của bộ lọc: đường nhanh đồng bộ trong bộ nhớ (`ContactsIndex`, `RecentNumbers`), đường chậm bất đồng bộ có đồng hồ canh 3 giây.

## 6. Đặc tả hành vi cần giữ (rút từ bộ mã cũ)

Mục này đủ để viết lại ngay cả khi không có mã cũ. Với chi tiết, đối chiếu `src-patched.zip`.

### 6.1 Mô hình

- `RuleAction`: `BLOCK`, `AUTO_ANSWER`, `ALLOW`.
- `CallRule`: `id`, `pattern`, `display` (chuỗi người dùng nhập, chỉ để hiển thị), `action`, `note?`, `createdAt`. Wildcard khi `pattern` kết thúc bằng `*`.
- `CallDecision`: `Ring`, `Block`, `AutoAnswer(delaySec)`. `delaySec <= 0` nghĩa là trả lời ngay và **ẩn hoàn toàn**; `> 0` nghĩa là đổ chuông bình thường rồi tự trả lời sau N giây.
- `DecisionResult(decision, reason)`: lý do bằng tiếng Việt, ghi vào nhật ký.

### 6.2 Giá trị mặc định cài đặt

- Tự trả lời: bật = `true`; độ trễ = 0 giây; tắt micro = `true`; tự cúp sau = 60 giây; cửa sổ lịch sử = 1440 phút (24 giờ).
- Công tắc chặn số ẩn và chặn số lạ: nên mặc định **tắt** (ở bản cũ chúng nằm trong thư viện nền; xác nhận lại khi cần).
- Ân hạn sau cuộc gọi khẩn cấp: 2 ngày.
- Nhật ký quyết định: tối đa 60 dòng. Số lượng quy tắc tối đa: 5000.

### 6.3 Chuẩn hóa số (`keysOf`)

- Đầu vào: chuỗi thô và `region` (mặc định `VN`). Lấy các chữ số; nếu bắt đầu bằng `+` thì coi là số quốc tế.
- Khóa chính (`exactKey`) là **E.164** nếu `libphonenumber` parse được và `isPossibleNumber`; ngược lại là chuỗi chữ số.
- Số thiếu `+` nhưng bắt đầu bằng mã quốc gia của region và đủ dài (từ mã quốc gia cộng 7 chữ số trở lên): thử thêm `+`; nếu `isValidNumber` thì coi là số quốc tế (ví dụ `842473001234`).
- Dạng nội địa (`national`, chỉ chữ số) chỉ có khi số cùng quốc gia với region.
- Mẫu nhập số cụ thể: tối thiểu 3 chữ số, cho phép dấu cách, gạch, chấm, ngoặc.
- Mẫu wildcard: đúng một dấu `*` ở cuối, tối thiểu 2 chữ số, chuẩn hóa thành **tiền tố E.164**:
  - `02*` thành `+842*`
  - `1900*` thành `+841900*`
  - `+84 24*` thành `+8424*`
  - Số `0` đầu (tiền tố trung kế của region) bị bỏ.
  - Dạng thiếu `+` mà bắt đầu bằng mã quốc gia (`8424*`) bị **từ chối** vì mơ hồ.
  - Dạng `+...` phải bắt đầu bằng mã quốc gia hợp lệ (không bắt đầu bằng 0) và còn ít nhất một chữ số sau mã quốc gia.
  - Cảnh báo "quá rộng" khi có ít hơn 3 chữ số có nghĩa (không tính mã quốc gia và số 0 đầu).

### 6.4 Thứ tự ưu tiên của engine

Dừng ở bước đầu tiên khớp:

1. **Số ẩn** (không có số, hoặc `PRESENTATION_RESTRICTED`): theo công tắc chặn số ẩn.
2. **Quy tắc số cụ thể** khớp: áp dụng, **kể cả khi số có trong danh bạ**.
3. **Wildcard** khớp (tiền tố dài nhất thắng): áp dụng, **trừ khi số có trong danh bạ** (khi đó đổ chuông).
4. Không khớp: nếu bật "chặn số lạ" thì chặn, trừ số trong danh bạ; ngược lại đổ chuông.
5. **Lớp ngoài cùng**: mọi quyết định **chặn** bị hủy (đổ chuông) nếu đang trong ân hạn sau cuộc gọi khẩn cấp.

`AUTO_ANSWER` chỉ thành quyết định khi: tính năng bật; không có cuộc gọi khác; có route riêng tư; số không trong danh bạ; và (chỉ với wildcard) số không xuất hiện trong lịch sử cuộc gọi trong cửa sổ lịch sử.

Danh bạ chỉ được hỏi **khi quyết định sắp áp dụng khác Ring** (chặn hoặc tự trả lời), vì số trong danh bạ luôn cho Ring. Mọi lỗi bất ngờ khi đánh giá dẫn tới **Ring** kèm lý do (không bao giờ chặn nhầm khi lỗi). Nếu không đọc được danh bạ (thiếu quyền) thì không áp dụng wildcard và "chặn số lạ", nhưng quy tắc số cụ thể vẫn áp dụng.

### 6.5 Bộ lọc cuộc gọi và quyết định dùng chung

- Android hỏi bộ lọc **trước khi đổ chuông** và phải trả lời trong 5 giây. Bộ lọc dùng đồng hồ canh 3 giây: chưa xong thì cho đổ chuông.
- Cuộc gọi đi luôn được cho qua.
- Phản hồi cho hệ thống: chặn = `setDisallowCall(true)`, `setRejectCall(true)`, `setSkipCallLog(false)`, `setSkipNotification(true)`; tự trả lời ẩn = `setSilenceCall(true)` (từ Android 10); còn lại để mặc định.
- Bộ lọc tạo một `PendingDecision` (khóa: số cộng `creationTimeMillis`), hoàn tất kết quả khi có, và ghi **điều đã trả lời hệ thống** (`responded`) **trước khi** gọi `respondToCall`. `InCallService` đón lần đánh giá đó, không tính lại.
- Nếu kết quả đến muộn hoặc khác điều đã trả lời, cuộc gọi đã đổ chuông và hiển thị bình thường; khi có kết quả thì **áp dụng bù** (từ chối, hoặc tự trả lời có giao diện).
- Sổ quyết định: hết hạn sau 30 giây; nếu khóa không khớp, chỉ dự phòng theo số khi có **đúng một** mục trong ngưỡng 3 giây, nếu nhiều hơn thì không đoán (trả về rỗng để đánh giá lại).
- Phản hồi hệ thống trước, ghi nhật ký sau (không chặn đường phản hồi).
- Nếu bộ lọc không chạy cho một cuộc gọi: đánh giá dự phòng ở luồng nền (sau khi cuộc gọi đã được đăng ký), rồi áp dụng bù.
- Theo hiểu biết của mình, `onScreenCall` chạy trên luồng chính, nên đường nhanh phải thuần trong bộ nhớ; **cần xác minh** khi code.

### 6.6 Tự động trả lời

- **Chế độ ẩn** (`delaySec <= 0`): cuộc gọi không được đưa vào giao diện, không thông báo; bộ lọc đã tắt chuông từ đầu. **Chế độ có giao diện** (`> 0`): đổ chuông bình thường, trả lời sau N giây.
- Điều kiện trả lời: cuộc gọi vẫn ở trạng thái `RINGING`.
- Sau khi nghe máy (`ACTIVE`): (1) tắt micro ngay nếu bật; (2) hẹn tự cúp sau N giây; (3) ép âm thanh về **loa trong hoặc tai nghe có dây**, xác minh theo sự kiện thay đổi âm thanh (có hạn chót 2 giây), không xác minh được thì cúp. Nếu chưa đọc được trạng thái âm thanh thì thử lại (300 ms, tối đa 6 lần), vẫn không được thì cúp.
- Cuộc **ẩn** mà âm thanh rời route riêng tư giữa chừng thì cúp ngay.
- Tắt micro cấp hệ thống (cuộc có giao diện): ghi cờ bền trước; khi hết cuộc gọi và tiến trình từng bị kill, khôi phục.
- Cuộc có giao diện khi tắt micro cũng phải cập nhật trạng thái mute mà UI hiển thị, nếu không nút micro hiện sai.
- Nhãn "Tự trả lời" trong nhật ký cuộc gọi: lưu danh sách thời điểm (giữ 200 mục gần nhất).

### 6.7 Tình huống biên (đều phải có test)

1. Có cuộc gọi thứ hai khi cuộc tự trả lời đang chạy: cuộc **đã nghe** thì cúp; cuộc **còn đang chờ trả lời** thì chỉ hủy hẹn giờ cho nó đổ chuông bình thường (không từ chối người gọi).
2. Chuông đã bị tắt nhưng không tự trả lời được (có cuộc khác, không có route riêng tư): **tự phát chuông thay thế** (ringtone mặc định khi chuông bình thường, rung nếu ở chế độ rung, dừng khi cuộc gọi hết đổ chuông hoặc sau 60 giây); không để cuộc gọi hiện ra trong im lặng.
3. Người gọi cúp trước giờ trả lời, hoặc người dùng tự nghe/từ chối: hủy sạch phiên.
4. Lệnh `answer`/`disconnect` ném ngoại lệ: bắt, ghi nhật ký, không sập app (mọi hẹn giờ đều phải có bắt ngoại lệ).
5. Người dùng bật lại micro trong cuộc có giao diện: hủy tự cúp, giữ cuộc gọi.
6. Kết quả đến sau khi hệ thống đã được trả lời "đổ chuông": áp dụng bù.
7. Mục còn sót ở trạng thái DISCONNECTED: tự dọn theo trạng thái thật, không làm tắt tính năng cho các cuộc sau.
8. Service bị hủy: dọn toàn bộ phiên.
9. Số ẩn bị chặn ngay cả khi bộ lọc không chạy cho loại cuộc gọi này: `InCallService` có lớp bảo đảm cuối.

### 6.8 Nhập / xuất

Tệp JSON: `{"rules":[{"pattern":..., "display":..., "action":..., "note":..., "createdAt":...}]}`. Phải **đọc được tệp xuất của bản cũ** (wildcard dạng nội địa `02*` được chuẩn hóa khi nhập; tên hành động không phân biệt hoa thường; mục sai bị bỏ qua; trùng `pattern` thì giữ một). Chi tiết ở `CallRuleTransfer.kt` trong bản mã cũ.

## 7. Những gì đã làm với bộ mã cũ (chỉ để hiểu bối cảnh)

Bộ mã đầu tiên đã được đánh giá và vá (chưa biên dịch): trạng thái kẹt của phiên tự trả lời, chuông bị tắt nhưng cuộc gọi hiện trong im lặng, cuộc thứ hai làm cuộc đang chờ bị từ chối, mất quy tắc âm thầm (JSON hỏng hoặc R8), tắt micro cấp hệ thống bị kẹt, ghi nhật ký chặn đường phản hồi, kết quả đến muộn bị bỏ, wildcard phụ thuộc quốc gia, hẹn giờ ném ngoại lệ gây sập, chặn nhầm cuộc gọi lại sau gọi khẩn cấp. Danh sách này chính là nguồn của bảng "lỗi và cách kiến trúc mới ngăn chặn" ở Phụ lục B trong kế hoạch.

Bộ mã Goodwy (`Dialer-main`) khác bộ mã đầu ở thư viện nền (`com.goodwy.commons` thay `org.fossify.commons`), có sẵn tính năng chặn riêng dựa trên danh sách chặn của hệ thống, và dùng `fallbackToDestructiveMigration()` trong Room. Không liên quan trực tiếp tới app mới, nhưng là tài liệu tham khảo tốt cho cấu trúc `CallService` và màn hình cuộc gọi.

## 8. Bắt đầu: mốc M0

Giao một zip gồm:

- Khung dự án hai module (`:domain` Kotlin/JVM, `:app` Android), Gradle Kotlin DSL, version catalog, minSdk 30.
- `:domain`: model, chuẩn hóa số và parser mẫu, `RuleIndex` và engine, codec JSON (kotlinx.serialization, có version schema), cùng giao diện `Facts`.
- Test JVM, **table-driven**, gồm tối thiểu:
  - chuẩn hóa số: các dạng `+84…`, `84…`, `0…`, có khoảng trắng, số không parse được; wildcard (`02*`, `1900*`, `+84 24*`, `8424*` bị từ chối, quá ngắn, quá rộng, số `+0…`)
  - engine: mọi nhánh ưu tiên; số cụ thể thắng danh bạ; danh bạ thắng wildcard; wildcard dài thắng ngắn; số ẩn; số lạ; AUTO_ANSWER khi có cuộc khác, không có route, có trong lịch sử; không tra danh bạ khi không cần; thiếu quyền danh bạ; ân hạn khẩn cấp hủy mọi quyết định chặn
  - codec: ghi rồi đọc lại; chuỗi rỗng; JSON hỏng; tên trường bị đổi thì báo lỗi chứ không coi là rỗng; mục sai bị bỏ; đọc được dữ liệu và tệp xuất của bản cũ
- Ghi chú "cần kiểm tra khi build" và lệnh nghiệm thu `./gradlew :domain:test`.

Các mốc tiếp theo (M1 đến M6) và tiêu chí nghiệm thu nằm ở mục 14 của kế hoạch. Mốc **M2** phải xác nhận sớm trên máy thật xem bộ lọc có được gọi khi app chỉ là dialer mặc định, hay cần thêm `ROLE_CALL_SCREENING`, **trước khi** làm phần tự trả lời.

## 9. Điều chưa kiểm chứng (đừng coi là chắc chắn)

- Toàn bộ mã đã viết (bản vá của bộ mã cũ) **chưa biên dịch, chưa chạy test**.
- `onScreenCall` chạy trên luồng chính; API nào cần cho việc chọn SIM (`TelephonyManager.getSubscriptionId(PhoneAccountHandle)` và các API liên quan); quyền `USE_FULL_SCREEN_INTENT` từ Android 14; giá trị 2 ngày của ân hạn khẩn cấp (nhớ từ cách hệ thống làm); cách các hãng máy xử lý `setSilenceCall`/`setSkipCallLog` — tất cả cần kiểm tra khi code và thử thật.
- Con số hiệu năng (dưới 10 ms ở p95 cho đường nhanh) là **mục tiêu**, chưa đo.
