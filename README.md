# Điện thoại (Echoline)

Ứng dụng điện thoại mặc định cho Android có **chặn cuộc gọi** và **tự trả lời** theo quy tắc.
applicationId / package: `com.minhnhat.echoline`. Tên trên launcher: "Điện thoại" (tiếng Việt), "Phone" (tiếng Anh).
Giao diện: Jetpack Compose + Material 3. minSdk 30, compileSdk/targetSdk 36.

> Trạng thái: đã build và chạy thử trên máy thật (Samsung SM-A207F, Android 11); test JVM của module `domain` đều qua; Logcat sạch.
> Các thay đổi ở mục 11 (bản sửa sau đánh giá tĩnh) **chưa được biên dịch trong môi trường viết mã** (không có Kotlin/Gradle/mạng):
> sau khi sync, nếu báo lỗi biên dịch hãy gửi lại **nguyên văn** thông báo lỗi, tôi sửa ngay.

## 1. Build

Yêu cầu (theo tài liệu AGP 9.4): **Android Studio Quail 4 (2026.1.4) trở lên**, JDK 17, Gradle 9.6.0, SDK Platform 36.

1. Giải nén, mở thư mục `echoline` bằng Android Studio, đợi Gradle sync (tự tải Gradle 9.6.0 theo `gradle-wrapper.properties`).
2. Nếu Studio báo thiếu `gradle-wrapper.jar` hoặc `gradlew`: chép 3 tệp `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
   từ một dự án Android khác của bạn vào đúng thư mục tương ứng (chỉ cần khi build bằng dòng lệnh).
3. Chọn thiết bị thật có SIM (trình giả lập không cho thử đầy đủ), bấm Run.
4. Bản `release` ký bằng khóa debug để cài thử được; không dùng để phát hành.

Phiên bản đang dùng (`gradle/libs.versions.toml`): AGP 9.4.0, Kotlin 2.4.10, Compose BOM 2026.04.01, coroutines 1.10.2,
kotlinx-serialization-json 1.9.0, libphonenumber 8.13.50.
- AGP 9 có sẵn Kotlin nên **không** dùng plugin `kotlin-android`.
- Compose BOM 2026.04.01 (Compose 1.11) chạy với compileSdk 36. Bản Compose 1.12 trở đi đòi compileSdk 37: chỉ nâng BOM
  cùng lúc với nâng `compileSdk` lên 37.
- Nếu một phiên bản không tìm thấy khi sync, dùng bản Studio gợi ý (Alt+Enter trên dòng đó).

## 2. Thiết lập lần đầu trên máy

Mở ứng dụng, làm theo màn hình thiết lập (cũng có trong Cài đặt, "Quyền và vai trò hệ thống"):
1. **Ứng dụng điện thoại mặc định**: bắt buộc.
2. **Sàng lọc cuộc gọi**: khuyến nghị. Nếu không có vai trò này, ứng dụng vẫn lọc được ở bước InCallService nhưng chậm hơn
   một chút và cuộc gọi chặn sẽ thoáng hiện trước khi bị từ chối. Trong "Nhật ký quyết định", cột nguồn `incall-fallback`
   nghĩa là bộ lọc sàng lọc không chạy.
3. **Quyền**: Danh bạ, Nhật ký cuộc gọi, Điện thoại, Trả lời cuộc gọi, Thông báo.
4. **Thông báo toàn màn hình** (Android 14 trở lên): để thấy cuộc gọi đến khi máy khóa.

## 3. Cách hoạt động (tóm tắt)

Thứ tự ưu tiên, dừng ở bước khớp đầu tiên (mọi lỗi đều dẫn tới "đổ chuông bình thường"):
1. Số ẩn: theo công tắc "Chặn số ẩn".
2. Quy tắc cho **số cụ thể** khớp: áp dụng, **kể cả khi số có trong danh bạ**.
3. Quy tắc **mẫu có dấu \*** khớp (tiền tố dài nhất thắng): áp dụng, trừ khi số có trong danh bạ.
4. Không khớp quy tắc nào: nếu bật "Chặn số lạ" thì chặn (trừ số trong danh bạ).
5. Lớp ngoài cùng: sau khi bạn gọi số khẩn cấp, mọi quyết định chặn bị hủy trong số ngày ân hạn.

Quy tắc có thể giới hạn theo SIM (Mọi SIM / SIM 1 / SIM 2); quy tắc đúng SIM thắng quy tắc "Mọi SIM" cùng số.

**Tự trả lời** chỉ chạy khi: tính năng bật, không có cuộc gọi khác, có đường âm thanh riêng tư (**không có thiết bị Bluetooth hoặc trợ thính đang kết nối**, và có loa trong hoặc tai nghe có dây), và
(với mẫu có dấu \*) số không trong danh bạ và chưa có trong nhật ký cuộc gọi trong khoảng đã đặt.
Quy tắc cho **số cụ thể** là quyết định có chủ đích của bạn nên **ưu tiên hơn danh bạ** (kể cả Chặn và Tự trả lời); chỉ mẫu có dấu \* mới bỏ qua số trong danh bạ.
- Chờ 0 giây: nghe ngay, **ẩn hoàn toàn** (không chuông, không giao diện, không thông báo).
- Chờ từ 1 giây: đổ chuông bình thường rồi tự nghe.
- Sau khi nghe: tắt micro (nếu bật), ép âm thanh về loa trong/tai nghe có dây, tự cúp sau N giây.
- Nếu không nghe được (có cuộc khác, đang kết nối Bluetooth, quá hạn...) thì cuộc gọi đổ chuông bình thường (với cuộc ẩn: hiện giao diện và phát chuông thay thế).

Sàng lọc chạy **trước khi đổ chuông**: đường nhanh dùng dữ liệu trong bộ nhớ ngay ở luồng chính. Nếu có dữ kiện chưa chắc chắn
(xem bên dưới), đánh giá lại ở luồng nền: xác minh dữ kiện rồi đánh giá lại, tối đa 1,2 giây (một hạn chót chung, không chặn luồng nào),
và đồng hồ canh của bộ lọc là 1,5 giây; hết giờ thì trả lời "đổ chuông" và `InCallService` áp dụng bù khi có kết quả đầy đủ.
Quyết định chặn đến muộn chỉ được áp dụng khi cuộc gọi còn đang đổ chuông: cuộc gọi người dùng đã nghe không bị từ chối.

Để cuộc gọi đầu tiên sau khi tiến trình khởi động lại không phải chờ nạp danh bạ/lịch sử, ứng dụng lưu **bản chụp** trên đĩa và nạp ngay
khi khởi động, rồi làm mới từ hệ thống ở nền. Bản chụp có thể cũ (danh bạ hay nhật ký đổi khi tiến trình đã bị tắt), nên:
- Câu trả lời **"có"** (có trong danh bạ, có lịch sử gần đây) từ bản chụp luôn được tin.
- Câu trả lời **"không"** chỉ được tin khi chỉ mục đã được làm mới trong tiến trình này. Nếu chưa, đường chậm tra trực tiếp ContactsProvider
  (đúng số đó) hoặc nhật ký cuộc gọi (chỉ trong cửa sổ thời gian cần xét, thường vài dòng), mất khoảng vài chục ms, chỉ với cuộc gọi cần dữ kiện đó
  (mẫu có `*`, chặn số lạ, tự trả lời cần kiểm lịch sử). Quy tắc số cụ thể không cần dữ kiện nào nên không bị chậm.
- Bản chụp bị bỏ nếu tập vùng (theo SIM) khác lúc ghi.

Số được chuẩn hóa theo vùng của **SIM thực sự nhận cuộc gọi**. Danh bạ và lịch sử được chuẩn hóa theo vùng của mọi SIM đang cắm, nên
cuộc gọi từ SIM nào cũng tra được.

## 4. Hệ thống nhật ký lỗi (dễ đổi, dễ gỡ)

Mọi nơi trong ứng dụng chỉ gọi `Diag.d/i/w/e` hoặc `Diag.guard` (tệp `diag/Diag.kt`). Phần còn lại nằm gọn trong 2 thư mục:
- `diag/impl/`: ghi ra Logcat (lọc `Echo/`), ghi tệp xoay vòng (tối đa khoảng 1 MB), bắt báo cáo sập ứng dụng, đọc/xóa nhật ký.
- `diag/ui/DiagScreen.kt`: màn hình xem, lưu ra tệp, xóa. Mở bằng Cài đặt, "Nhật ký lỗi".

**Đổi nơi ghi**: viết thêm một lớp `DiagSink` rồi thêm vào danh sách trong `diag/impl/DiagSetup.kt`.

**Tắt** (không xóa mã): xóa dòng `DiagSetup.install(this)` trong `App.kt`. Mọi lệnh log thành "không làm gì".

**Gỡ hẳn**:
1. Xóa thư mục `diag/impl` và `diag/ui` (giữ lại `diag/Diag.kt`).
2. `App.kt`: xóa dòng `DiagSetup.install(this)` và import tương ứng.
3. `ui/AppRoot.kt`: xóa import `DiagSetup`, `DiagScreen`, giá trị `DIAG` của `Overlay`, nhánh `Overlay.DIAG ->`, và tham số `onOpenDiag = ...`.
4. `ui/settings/SettingsScreen.kt`: xóa tham số `onOpenDiag` và dòng `LinkRow(... link_diag ...)`.
5. (Tùy chọn) xóa các chuỗi `link_diag`, `diag_*` trong hai tệp `strings.xml`.

Ngoài nhật ký lỗi còn có **Nhật ký quyết định** (Cài đặt, "Nhật ký quyết định"): đây là tính năng chính, ghi 60 quyết định gần nhất
của bộ lọc kèm lý do, thời gian xử lý và nguồn.

## 4b. Dữ liệu cá nhân lưu trên máy

Tất cả nằm trong bộ nhớ riêng của ứng dụng, không có kết nối mạng (Manifest không xin quyền INTERNET):
`filter-config.json` (quy tắc, cài đặt), `decision-log.json` (60 quyết định gần nhất, có số điện thoại), `contacts-cache.txt` và
`recents-cache.txt` (bản chụp số danh bạ/lịch sử để cuộc gọi đầu tiên sau khi khởi động lại xử lý nhanh; bị xóa nếu thu hồi quyền).
Nhật ký lỗi (`diag/`) **không ghi số điện thoại**.

## 4c. Kiểm thử JVM

Logic quyết định (`DecisionEngine`), chuẩn hóa số, đọc/ghi dữ liệu và **máy trạng thái tự trả lời** (`domain/session/AutoAnswerMachine.kt`) đều nằm
trong module `domain` (Kotlin/JVM thuần) và có test. Chạy: `./gradlew :domain:test`. Lớp `telecom/AutoAnswerRunner.kt` chỉ là adapter mỏng:
dịch sự kiện Android thành sự kiện máy trạng thái và thực hiện các việc máy yêu cầu (nghe máy, hẹn giờ, tắt micro, ép route).
Các kịch bản đã có test: nghe ngay, chờ rồi nghe, người dùng tự nghe trong lúc chờ, có cuộc khác, không có route riêng tư, hết giờ chờ
nghe (vẫn đổ chuông / đã ACTIVE / đã kết thúc), lệnh nghe lỗi, ép route lỗi, tự cúp, âm thanh rời route riêng tư, người dùng bật lại micro,
cuộc gọi thứ hai, và bỏ qua mọi sự kiện sau khi kết thúc.

## 5. Danh sách thử nghiệm đề nghị

Thử bằng hai máy hoặc nhờ người khác gọi. Sau mỗi tình huống, mở "Nhật ký quyết định" xem lý do.

1. Gọi đi, nhận cuộc gọi đến bình thường, cúp máy, tắt mic, loa ngoài, bàn phím DTMF.
2. Quy tắc **Chặn** một số cụ thể: gọi từ số đó; cuộc gọi phải bị từ chối, không đổ chuông, không thông báo cuộc nhỡ.
3. Quy tắc **Chặn** nhưng số đó có trong danh bạ: vẫn bị chặn (số cụ thể thắng danh bạ). Với mẫu có dấu \* thì số trong danh bạ vẫn đổ chuông.
4. **Tự trả lời** số cụ thể, chờ 0 giây, cắm tai nghe có dây hoặc để ở loa trong: nghe ẩn, không chuông, tự cúp sau N giây.
5. Như trên nhưng **đang kết nối tai nghe/loa xe Bluetooth** (kể cả loại chỉ phát nhạc): phải đổ chuông bình thường (không tự trả lời), lý do trong nhật ký quyết định ghi "không có đường âm thanh riêng tư". Loa ngoài chỉ có thể bật khi đã trong cuộc gọi, nên được kiểm sau khi nghe (ép route, giám sát âm thanh).
6. **Tự trả lời** với chờ 5 giây: đổ chuông, hiện giao diện, tự nghe sau 5 giây; thử bấm Từ chối/Trả lời trước khi hết giờ.
7. Đang có một cuộc gọi, có cuộc thứ hai gọi tới số có quy tắc Tự trả lời: không tự nghe. Ngược lại, đang trong cuộc tự trả lời mà có cuộc thứ hai tới: cuộc tự trả lời bị cúp, cuộc thứ hai đổ chuông bình thường.
8. Chặn số ẩn và Chặn số lạ (bật từng công tắc).
9. Gọi số khẩn cấp (hoặc `112` ở chế độ máy không có SIM thì không thử được: dùng số khẩn cấp thật chỉ khi cần), sau đó thử cuộc chặn: trong ân hạn không bị chặn.
10. Khởi động lại máy rồi gọi ngay: cuộc đầu tiên đi qua "đường chậm", xem nguồn `screening-async` trong nhật ký.
    Thử thêm: lưu một số mới vào danh bạ khi ứng dụng chưa chạy (vừa buộc dừng), bật "chặn số lạ", rồi gọi từ số đó: không được bị chặn.
13. Giao diện tối: mở Nhật ký quyết định, Nhật ký lỗi và màn hình Thiết lập ban đầu: chữ phải sáng, đọc được trên nền tối.
11. Máy hai SIM: quy tắc riêng cho SIM 1 / SIM 2, chọn SIM khi gọi đi.
12. Nhập/xuất quy tắc, nhập danh sách chặn của hệ thống.

Khi báo lỗi, gửi kèm: (a) tình huống, (b) ảnh/dòng "Nhật ký quyết định", (c) tệp lưu từ "Nhật ký lỗi".

## 6. Điểm chưa chắc chắn (sẽ lộ ra khi thử trên máy)

- **Sàng lọc có chạy khi chỉ là ứng dụng điện thoại mặc định hay cần thêm vai trò Sàng lọc?** Ứng dụng xin cả hai vai trò để chắc chắn.
  Cột nguồn trong nhật ký quyết định cho biết thực tế.
- **Hiện giao diện cuộc gọi đến**: dựa vào thông báo toàn màn hình (cần quyền ở Android 14+). Một số hãng có thêm giới hạn chạy nền/khởi động.
- **`TelecomManager.silenceRinger()`** (dùng khi áp dụng bù kiểu "chỉ tắt chuông") cho phép ứng dụng điện thoại mặc định gọi, nhưng một số hãng
  có thể chặn. Thất bại được ghi vào cả nhật ký quyết định ("tắt chuông thất bại: …"), không chỉ Logcat.
- **Hẹn giờ tự cúp** dùng `delay()` của coroutine, không giữ wake lock hay alarm. Chưa kiểm chứng được CPU có ngủ làm tự cúp trễ khi tắt màn hình
  trong cuộc gọi hay không. Cách thử: đặt tự cúp 30 giây, tắt màn hình, đối chiếu thời điểm cúp thực tế trong Nhật ký lỗi. Nếu trễ, giữ một
  `PARTIAL_WAKE_LOCK` có thời hạn suốt phiên tự trả lời (cần quyền `WAKE_LOCK`).
- **Cửa sổ rất hẹp khi có cuộc thứ hai ngay lúc vừa gửi lệnh nghe** (tối đa 4 giây, cuộc còn đang đổ chuông): máy trạng thái bỏ cuộc và trả về đổ chuông
  thay vì cúp (cúp lúc này là từ chối người gọi). Nếu lệnh nghe kịp có hiệu lực ngay sau đó, cuộc gọi đã nghe sẽ hiện giao diện bình thường (không còn ẩn).
- **Ép âm thanh về loa trong** dùng API `setAudioRoute` (đã bị đánh dấu lỗi thời từ Android 14 nhưng vẫn chạy). Nếu máy không chịu đổi route, cuộc tự trả lời bị cúp (an toàn).
- **Tắt micro**: dùng `InCallService.setMuted`. Có cờ lưu bền và tự khôi phục micro nếu tiến trình bị kill giữa chừng.
- Nhãn "Tự trả lời" trong Gần đây dựa vào thời điểm cuộc gọi, khớp trong khoảng 20 giây.
- libphonenumber (bản chạy trên JVM) nạp dữ liệu lần đầu hơi chậm trên Android; ứng dụng nạp sẵn lúc khởi động tiến trình.
- Thông điệp lý do trong nhật ký quyết định viết bằng tiếng Việt (cả khi giao diện tiếng Anh).

## 6b. Lưu ý kỹ thuật cho sau này

- **API âm thanh Android 14 trở lên**: ứng dụng dùng `onCallAudioStateChanged`, `callAudioState`, `setAudioRoute`, `setMuted` (Android 11 đến 13 là API chuẩn).
  Từ Android 14 chúng bị đánh dấu lỗi thời nhưng vẫn chạy. Phương án thay thế là `onAvailableCallEndpointsChanged`, `onCallEndpointChanged`,
  `onMuteStateChanged`, `requestCallEndpointChange`. Khi cần, làm một lớp tương thích: API cũ cho Android 11 đến 13, Call Endpoint cho Android 14+.
  Chỉ làm khi thấy lỗi thật trên máy Android 14+ (chưa thử trên máy nào như vậy). Các điểm cần đổi: `EcholineInCallService.onCallAudioStateChanged`,
  `AutoAnswerRunner.ensurePrivateRoute/muteIfNeeded`, `CallRegistry.setMuted/setRoute`, `ui/incall/InCallScreen`.
- **Thông báo trên màn hình khóa** đang hiện tên/số người gọi (`VISIBILITY_PUBLIC`): tạm giữ nguyên có chủ ý. Muốn ẩn: đổi sang `VISIBILITY_PRIVATE` và thêm `publicVersion`
  trong `CallNotifications.kt`.
- **Chỉ hỗ trợ 2 khe SIM** (quy tắc theo SIM chỉ có "Mọi SIM / SIM 1 / SIM 2").
- Thiết bị Bluetooth được phát hiện qua danh sách thiết bị đầu ra của `AudioManager` (`AudioProbe.hasExternalAudioDevice`): SCO, A2DP, trợ thính,
  và (Android 12+) BLE. Kiểm tra này chạy lúc sàng lọc, trước khi nghe máy.
- Khe SIM và vùng của SIM chỉ được nhớ (60 giây) khi đọc thành công; lần đọc thất bại sẽ được thử lại ở cuộc gọi sau.
- Bản sao cấu hình (`filter-config.skipped-*.json`, `filter-config.corrupt-*.json`) được dùng lại nếu nội dung không đổi, không tạo mới mỗi lần khởi động.
- Dòng nhật ký "Danh sách SIM thay đổi" chỉ xuất hiện khi danh sách SIM thật sự đổi. (Android gọi listener một lần ngay lúc đăng ký; bản trước ghi nhầm
  lần gọi đó thành "thay đổi".)

## 7. Cấu trúc mã

```
domain/   Kotlin/JVM thuần: chuẩn hóa số, quy tắc, engine quyết định, máy trạng thái tự trả lời, đọc/ghi JSON, nhập/xuất (có test JVM)
app/      Android
  AppGraph.kt, App.kt        tạo và giữ các thành phần dùng chung
  data/                      cấu hình, danh bạ, nhật ký cuộc gọi, SIM, nhật ký quyết định
  telecom/                   sàng lọc, InCallService, tự trả lời, thông báo, chuông thay thế
  ui/                        các màn hình Compose
  diag/                      hệ thống nhật ký lỗi (tách biệt, xem mục 4)
docs/                        kế hoạch và bàn giao ban đầu
```

## 8. Đo tốc độ tự trả lời

Mỗi dòng trong "Nhật ký quyết định" có nguồn và thời gian sàng lọc; với cuộc tự trả lời còn có ghi chú:
- `tiến trình vừa khởi động N ms trước`: cuộc gọi này làm tiến trình khởi động nguội (chi phí này nằm TRƯỚC mọi dòng đo của ứng dụng).
- `tốc độ: nhận cuộc gọi → lệnh nghe X ms, lệnh nghe → ACTIVE Y ms`: X là phần ứng dụng kiểm soát được, Y là phần Telecom/nhà mạng.
Nhật ký lỗi có thêm dòng "onCallAdded sau N ms kể từ khi sàng lọc trả lời" (khoảng thời gian hệ thống mất để kết nối `InCallService`).

Cách đọc: tổng thời gian từ lúc người gọi nghe tiếng chờ đến lúc được nghe máy = (khởi động tiến trình, nếu nguội) + (sàng lọc) +
(hệ thống kết nối InCallService) + X + Y + (báo hiệu mạng tới người gọi). Ứng dụng chỉ kiểm soát được sàng lọc, một phần khởi động nguội và X.

Để đo đúng: dùng bản **release** (đã ký bằng khóa debug, cài được) và, nếu muốn loại bỏ chi phí biên dịch JIT khi khởi động nguội,
chạy một lần: `adb shell cmd package compile -m speed -f com.minhnhat.echoline`. Bản debug chạy chậm hơn đáng kể ở cuộc gọi đầu tiên.

## 9. Giao diện: bảng màu nâu

Màu nhấn là **nâu** trên nền kem ấm (sáng) hoặc nâu đen (tối). Không dùng "màu động" theo hình nền của Android 12+ để màu nâu luôn giữ nguyên.

| Vai trò | Sáng | Tối |
|---|---|---|
| Nhấn chính (`primary`) | `#7A4A2B` | `#FFB68C` |
| Nền chính (`container`) của nút/thẻ chính | `#FFDCC6` | `#63391D` |
| Màu phụ nâu trầm (`secondary`) | `#765848` | `#E5BFA9` |
| Bổ trợ ô-liu nhạt (`tertiary`) | `#5F6134` | `#C8CA93` |
| Nền (`background/surface`) | `#FFF8F5` | `#181210` |

Biểu tượng ứng dụng: nền nâu `#6F4426`, ống nghe màu kem `#FFF1E4`, có lớp đơn sắc cho biểu tượng chủ đề của Android 13+.
Màu nút Trả lời (xanh lá) và Từ chối/Cúp (đỏ) của màn hình cuộc gọi giữ nguyên vì mang nghĩa, không theo bảng màu.
Thông báo cuộc gọi có màu nhấn nâu.

Đổi màu: bảng màu ở `ui/theme/Theme.kt`; màu biểu tượng và màu cửa sổ ở `res/values/colors.xml` (và `res/values-night/colors.xml`).

## 10. Kết quả kiểm tra tĩnh (Inspect Code)

Đã sửa: lỗi Lint `LocalActivity`; các lỗi `MissingPermission` (đã bảo vệ bằng kiểm tra quyền + try/catch, thêm chú thích cho Lint);
`silenceRinger` (có thể bị từ chối trên một số máy, đã bắt lỗi); chuỗi số nhiều (`plurals`); quy tắc sao lưu dữ liệu
(`dataExtractionRules`: không sao lưu, không chuyển máy); lớp đơn sắc cho biểu tượng; KTX (`edit`, `toUri`); `Enum.entries`;
import, ký hiệu và tham số thừa; liên kết KDoc hỏng.

Cố ý không sửa:
- **Nâng phiên bản thư viện** (Kotlin 2.4.21, coroutines 1.11, serialization 1.12, libphonenumber 9.x, Compose BOM 2026.09, compileSdk 37, AGP 9.4.1/9.5.1, Gradle 9.9):
  bản hiện tại đang chạy tốt; nâng nhiều thứ cùng lúc mà chưa có cách kiểm thử tự động toàn bộ phần Android là rủi ro không cần thiết.
  Nếu muốn nâng, làm từng nhóm nhỏ và chạy lại `:domain:test` (đặc biệt libphonenumber 9.x vì dữ liệu số có thể thay đổi). Compose BOM 2026.09 và
  core-ktx 1.19 đi kèm yêu cầu compileSdk 37.
- **`targetSdk` mới nhất**: đổi targetSdk kéo theo thay đổi hành vi của hệ thống, cần thử lại toàn bộ.
- **`android.hardware.telephony required="false"`**: ứng dụng vô dụng nếu không có điện thoại, giữ `required="true"`.
- **Cảnh báo Play Policy** (toàn màn hình, nhật ký cuộc gọi): chỉ liên quan khi phát hành lên Google Play; ứng dụng điện thoại mặc định được phép dùng cho chức năng cốt lõi.
- **Chuyển `delay(Long)` sang `Duration`** và các gợi ý kiểu mã (destructuring, accessor thành thuộc tính): chỉ là phong cách, không đổi hành vi.
- **Cảnh báo Gradle `@Incubating`** trong `settings.gradle.kts`: là cách khai báo kho chuẩn hiện nay.

## 11. Bản sửa sau đánh giá tĩnh (đợt 1)

1. **Tự trả lời và Bluetooth**: `AudioProbe.hasPrivateRoute()` trước đây chỉ kiểm tra có loa trong (luôn có), nên vẫn tự nghe khi đang kết nối Bluetooth.
   Nay không tự nghe khi có thiết bị Bluetooth/trợ thính kết nối (xem mục 6b). Hệ quả: tai nghe chỉ phát nhạc đang kết nối cũng làm cuộc gọi đổ chuông bình thường.
2. **Nhật ký quyết định ở đường chậm**: dòng nhật ký và `loggedAtMs` nay được ghi trước khi trả lời hệ thống, nên các ghi chú ("tốc độ: …", "áp dụng bù") không còn bị mất
   ở cuộc gọi đầu tiên sau khi tiến trình khởi động. Hệ quả nhỏ: thời gian sàng lọc ghi trong nhật ký không còn gồm thời gian gọi `respondToCall`.
3. **Bản sao cấu hình**: không tạo thêm tệp sao lưu mỗi lần khởi động khi nội dung không đổi (mục 6b).
4. **SIM**: không nhớ kết quả đọc khe SIM/vùng SIM thất bại (mục 6b).
5. **Chuẩn hóa số**: lần parse thứ hai (số quốc tế thiếu dấu `+`) lỗi không còn làm mất kết quả parse đầu.
6. **Cuộc thứ hai khi vừa gửi lệnh nghe**: `SessionEvent.OtherCallArrived` nay mang trạng thái của cuộc đang tự trả lời; cuộc còn đang đổ chuông thì không bị cúp (mục 6).
   Test tương ứng trong `AutoAnswerMachineTest` đã đổi theo.

## 12. Bản sửa sau đánh giá tĩnh (đợt 2)

Độ tin cậy:
1. **Quyết định chặn đến muộn** (`EcholineInCallService.applyDecision`): chỉ áp dụng khi cuộc gọi còn RINGING. Cuộc đã nghe (ACTIVE) hoặc đã đổi trạng thái thì bỏ qua, ghi chú vào nhật ký quyết định.
2. **Xác minh route âm thanh** (`AutoAnswerRunner.ensureRoute`): ngoại lệ được chuyển thành kết quả thất bại gửi cho máy trạng thái, không còn để phiên kẹt không ai giám sát.
3. **`FallbackRinger`**: giữ tham chiếu chuông/rung trước khi phát; nếu một bước phát lỗi thì dừng những gì đã bắt đầu (trước đây chuông lặp có thể không dừng được).
4. **Micro**: phiên tự trả lời trả lại micro ngay khi kết thúc (không chờ "hết mọi cuộc gọi", tránh tắt nhầm micro của cuộc đến sau); lần tắt micro thất bại được reset đầy đủ để lần thử sau vẫn được ghi nhận quyền sở hữu; lệnh khôi phục thất bại thì giữ cờ `micMutedByUs`
   cho đường dự phòng (`AudioProbe.restoreMicIfNeeded`, qua `AudioManager`, chỉ xóa cờ khi thành công). Cờ được ghi đồng bộ (`commit`) để không mất nếu tiến trình bị kill ngay sau đó.
5. **Bản chụp danh bạ/lịch sử**: xác minh kết quả âm tính khi chưa làm mới và bỏ bản chụp lệch vùng (xem mục 3).
6. **Không còn `runBlocking`**: `LiveFacts` không bao giờ chặn luồng; đường chậm dùng hàm suspend `evaluateResolving`, mỗi lần chờ là suspend có hạn chót chung. Không còn nguy cơ chiếm luồng của `Dispatchers.Default` khi có nhiều cuộc gọi cùng lúc.

Hiệu năng và gọn gàng:
7. `AudioProbe.hasPrivateRoute()` lấy danh sách thiết bị một lần và tính mọi điều kiện trên cùng bản chụp.
8. Chờ route âm thanh bằng luồng trạng thái (`CallRegistry.audio`) thay vì ngủ cố định 300 ms; kết quả luôn được kiểm lại bằng trạng thái thật.

Giao diện:
9. **Chữ đen trên nền tối** ở Nhật ký quyết định, Nhật ký lỗi và Thiết lập ban đầu: ba màn hình này không nằm trong `Scaffold` nên chữ không đặt màu riêng lấy màu mặc định (đen). `EcholineTheme` nay bọc nội dung trong `Surface` nên mọi màn hình nhận đúng màu chữ theo chủ đề.
