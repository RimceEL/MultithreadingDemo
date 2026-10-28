package MutiThread;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        String fileName = "employees.dat";
        int totalManagers = 3;
        EmployeeFileService service = new EmployeeFileService(fileName, totalManagers);

        List<Thread> threads = new ArrayList<>();

        for (int i = totalManagers; i >= 1; i--) {
            final int managerId = i;

            List<Employee> list = new ArrayList<>();
            for (int j = 1; j <= 3; j++) {
                int empId = managerId * 100 + j;
                list.add(new Employee(empId, "NV" + empId, 1000 * j));
            }

            Thread t = new Thread(() -> service.saveList(managerId, list), "Manager-" + managerId);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) { // Đảm bảo các luồng trong threads đã chạy xong thì Main Thread mới được tiếp
                                   // tục xuống với bước đọc file
            t.join();
        }

        System.out.println("---- Đọc lại file ----");
        EmployeeFileService.readAll(fileName);
    }
}
