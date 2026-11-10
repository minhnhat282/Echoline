# Quy tắc và cấu hình đọc/ghi bằng cây JSON (kotlinx.serialization.json), không dùng phản chiếu theo tên trường,
# nên R8 đổi tên trường không làm mất dữ liệu. Chỉ giữ lại tên lớp hệ thống gọi theo tên trong Manifest
# (AGP tự thêm luật cho Activity/Service/Receiver khai báo trong Manifest).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
