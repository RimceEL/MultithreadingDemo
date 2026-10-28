package MutiThread;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class EmployeeFileService {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition myTurn = lock.newCondition();

    private final int totalManagers;
    private int currentTurn = 1;
    private ObjectOutputStream out;

    public EmployeeFileService(String fileName, int totalManagers) throws IOException {
        this.totalManagers = totalManagers;
        this.out = new ObjectOutputStream(new FileOutputStream(fileName));
    }

    public void saveList(int managerId, List<Employee> list) {
        lock.lock();
        try {
            while (currentTurn != managerId) {
                System.out.println("Manager " + managerId + " đang chờ đến lượt...");
                myTurn.await();
            }

            System.out.println("Manager " + managerId + " ghi file");
            out.writeObject(new ArrayList<>(list));
            out.flush(); // Thực hiện việc đẩy dữ liệu trong buffer xuống ổ đĩa ngay lập tức, tránh
                         // trường hợp crash chương trình nhưng chưa ghi kịp dât xuống đĩa

            currentTurn++;
            if (currentTurn > totalManagers) {
                out.close();
            }
            myTurn.signalAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked") // Tắt cảnh báo của trình biên dịch cho dòng code số 54 (Việc ép kiẻu với
                                   // generic khiến JVM hiểu nhầm là không an toàn)
    public static void readAll(String fileName) {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(fileName))) {
            int count = 1;
            while (true) {
                List<Employee> list = (List<Employee>) in.readObject();
                System.out.println("Danh sách " + count++ + ":");
                for (Employee e : list) {
                    System.out.println("  " + e);
                }
            }
        } catch (EOFException e) {
            System.out.println("Đã đọc hết file.");
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
