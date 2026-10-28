# Nhiều manager ghi danh sách Employee vào cùng một file nhị phân

Mỗi manager (phòng ban) là một luồng, mỗi luồng ghi một `List<Employee>` vào **cùng một file** `employees.dat`.
Các danh sách phải được ghi **theo đúng thứ tự phòng ban 1 → 2 → 3**, được đảm bảo bằng `ReentrantLock` + `Condition`.

```
Employee.java             // đối tượng cần lưu
EmployeeFileService.java  // lock, condition, ghi file, đọc file
Main.java                 // tạo các luồng manager, chạy, đọc lại file
```

---

## 1. Ghi object vào file nhị phân

- `ObjectOutputStream.writeObject(...)` ghi nguyên object xuống file, `ObjectInputStream.readObject()` đọc lại.
- Class phải `implements Serializable`. `Employee` đã làm vậy, còn `ArrayList` vốn serializable sẵn, nên ghi được **cả danh sách trong một lần `writeObject`**.
- Đọc lại: mỗi lần `readObject()` trả về đúng một danh sách theo thứ tự đã ghi, đọc đến khi gặp `EOFException` thì dừng.

**Vì sao chỉ mở một `ObjectOutputStream` cho cả file (trong constructor)?**
Mỗi `ObjectOutputStream` mới ghi một *header* vào stream. Nếu mỗi luồng tự mở stream mới để ghi nối vào file, giữa file sẽ có nhiều header và lúc đọc lại bị `StreamCorruptedException`. Vì vậy cả 3 luồng dùng chung một stream, và stream đóng khi manager cuối ghi xong.

---

## 2. Cơ chế đồng bộ: Lock + Condition

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition myTurn = lock.newCondition();
private int currentTurn = 1;
```

| Thành phần | Vai trò |
|------------|---------|
| `lock` | Mỗi lúc chỉ **một luồng** được đụng vào `out` và `currentTurn`, tránh dữ liệu các danh sách bị xen vào nhau. |
| `currentTurn` | Cho biết **manager số mấy đang được ghi**. Bắt đầu bằng 1, ghi xong thì tăng 1. |
| `myTurn` (Condition) | Hàng đợi cho các luồng **chưa đến lượt**. `await()` nhả lock và ngủ, `signalAll()` đánh thức mọi luồng đang ngủ. |

Quy tắc: chỉ được gọi `await()` / `signalAll()` khi **đang giữ lock**, nếu không sẽ bị `IllegalMonitorStateException`.

### Hàm `saveList` chạy như thế nào

```java
lock.lock();
try {
    while (currentTurn != managerId) {   // chưa đến lượt?
        myTurn.await();                  // nhả lock, đi ngủ
    }
    out.writeObject(new ArrayList<>(list));  // đến lượt: ghi
    out.flush();
    currentTurn++;                       // chuyển lượt
    if (currentTurn > totalManagers) out.close();  // manager cuối thì đóng stream
    myTurn.signalAll();                  // đánh thức các luồng đang chờ
} ... finally {
    lock.unlock();                       // luôn nhả lock
}
```

1. Xin lock. Nếu luồng khác đang giữ thì đợi.
2. Chưa đến lượt thì `await()`: nhả lock để luồng khác vào được, rồi ngủ.
3. Được đánh thức thì phải **giành lại lock**, rồi quay lại kiểm tra điều kiện.
4. Đến lượt thì ghi, tăng `currentTurn`, `signalAll()` rồi nhả lock.

**Vì sao dùng `while` mà không dùng `if`?**
- `signalAll()` đánh thức *tất cả* luồng, nhưng chỉ một luồng thật sự đến lượt. Các luồng còn lại phải kiểm tra lại và ngủ tiếp.
- Luồng có thể tự thức dậy mà không ai signal (spurious wakeup).

Dùng `while` thì sau mỗi lần thức dậy luồng đều kiểm tra lại điều kiện nên luôn an toàn.

**`out.flush()`** đẩy dữ liệu trong buffer xuống file ngay sau mỗi lần ghi. Không bắt buộc, vì `close()` cũng tự flush. Nhưng có flush thì danh sách của manager nào đã ghi xong sẽ nằm chắc trong file, không mất nếu chương trình bị dừng đột ngột giữa chừng.

**`@SuppressWarnings("unchecked")`** trên `readAll`: dòng `(List<Employee>) in.readObject()` ép kiểu từ `Object` sang `List<Employee>`. Do generic bị xóa kiểu lúc chạy, JVM chỉ kiểm tra được đó là `List` chứ không biết bên trong có phải `Employee`, nên trình biên dịch cảnh báo. Vì ta biết file chỉ chứa các danh sách do `saveList` ghi, annotation này chỉ để tắt cảnh báo, không làm phép ép kiểu an toàn hơn.

---

## 3. Hàm `Main`

```java
for (int i = totalManagers; i >= 1; i--) {
    final int managerId = i;
    ...
    Thread t = new Thread(() -> service.saveList(managerId, list), "Manager-" + managerId);
    threads.add(t);
    t.start();
}
for (Thread t : threads) t.join();
EmployeeFileService.readAll(fileName);
```

- **Chỉ có một `service`** dùng chung, nên chỉ có một lock, một condition, một stream. Đây là thứ các luồng tranh nhau.
- **Vòng lặp chạy ngược 3 → 1** có chủ đích: manager 3 được start trước, nên chạm `saveList` khi chưa đến lượt và phải `await()`, nhờ đó thấy rõ cơ chế chờ lượt.
- **`final int managerId = i`**: lambda chỉ dùng được biến không đổi giá trị, mà `i` đổi sau mỗi vòng, nên phải chép sang biến mới.
- **`join()`**: bắt `main` đợi tất cả luồng ghi xong. Nếu không, `main` đọc file khi dữ liệu chưa đủ hoặc stream chưa đóng.

### Ví dụ chạy

| Bước | Diễn biến | `currentTurn` |
|------|-----------|---------------|
| 1 | Manager 3: `1 != 3` nên `await()` | 1 |
| 2 | Manager 2: `1 != 2` nên `await()` | 1 |
| 3 | Manager 1: đến lượt, **ghi**, `signalAll()` | 2 |
| 4 | Manager 3 thức dậy, `2 != 3` nên ngủ lại | 2 |
| 5 | Manager 2: **ghi**, `signalAll()` | 3 |
| 6 | Manager 3: **ghi**, `currentTurn = 4` nên đóng stream | 4 |

Các dòng "đang chờ" có thể xuất hiện khác nhau giữa các lần chạy, nhưng trong file luôn là **danh sách 1, rồi 2, rồi 3**.

---

## 4. Điểm yếu: một phòng ban không ghi được thì các phòng ban sau chờ mãi

Các luồng **phụ thuộc dây chuyền**: manager 2 chờ manager 1, manager 3 chờ manager 2. Nếu manager đứng trước không bao giờ làm xong lượt của mình, `currentTurn` không bao giờ tăng, mọi manager phía sau ngủ ở `await()` mãi mãi:

- `join()` trong `Main` chờ vô hạn, chương trình treo.
- `out` không bao giờ được `close()`.

Trong code hiện tại, điều này xảy ra khi:

| Tình huống | Vì sao các phòng sau bị kẹt |
|------------|-----------------------------|
| Một manager không bao giờ gọi `saveList` (ví dụ thiếu id, luồng chết trước đó) | Không ai tăng `currentTurn` ở lượt đó. |
| `writeObject` ném `IOException` | Lỗi bị `catch`, nhưng `currentTurn++` và `signalAll()` nằm sau `writeObject` nên **bị bỏ qua**. |
| Luồng bị `interrupt` khi đang chờ | Luồng thoát khỏi `saveList` mà không ghi và không chuyển lượt. |

Hướng khắc phục (chưa có trong code):
- Dùng `await(thời_gian, đơn_vị)` thay cho `await()` để chờ có thời hạn, hết giờ thì báo lỗi thay vì treo.
- Đưa phần chuyển lượt (`currentTurn++` + `signalAll()`) vào `finally`, để manager lỗi vẫn nhường lượt cho người sau.
- Ở `Main`, dùng `join(timeout)` để không bị treo vô hạn.

---

## 5. Chạy

```bash
javac MutiThread/*.java
java MutiThread.Main
```

Chạy xong sẽ có file `employees.dat` trong thư mục hiện tại.
