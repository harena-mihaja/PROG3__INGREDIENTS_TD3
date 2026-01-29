
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        resetData();
        DataRetriever dataRetriever = new DataRetriever();

        // 1. Initial cleanup (if needed, typically Main relies on existing data or creates it)
        // Here we assume tables 1, 2, 3 exist. If not, one might need to insert them.
        // Since we are running on top of existing DB from previous migrations/runs:

        System.out.println("=== TEST SCENARIO: Restaurant Table Availability ===");

        ensureTablesExist();

        // Fetch tables (Assuming Tables 1, 2, 3 exist from migration/setup)
        // We find availables in the past to get reference objects
        List<RestaurantTable> allTables = dataRetriever.findAvailableTables(
                Instant.now().minus(300, ChronoUnit.DAYS),
                Instant.now().minus(299, ChronoUnit.DAYS));

        RestaurantTable table1 = allTables.stream().filter(t -> t.getNumber() == 1).findFirst().orElseThrow(() -> new RuntimeException("Table 1 missing"));

        Instant now = Instant.now();
        Instant oneHourLater = now.plus(1, ChronoUnit.HOURS);

        // Scenario 1: Successful Booking on Table 1
        System.out.println("\n[1] Attempting to book Table 1 for 1 hour...");
        Order order1 = new Order();
        order1.setReference("CMD-001");
        order1.setCreationDatetime(now);
        order1.setTable(table1);
        order1.setInstallationDate(now);
        order1.setDepartureDate(oneHourLater);

        try {
            dataRetriever.saveOrder(order1);
            System.out.println("SUCCESS: Order CMD-001 saved on Table 1.");
        } catch (Exception e) {
            System.err.println("FAILURE: " + e.getMessage());
        }

        // Scenario 2: Overlapping Booking on Table 1 (Should Fail & Suggest Tables)
        System.out.println("\n[2] Attempting to book Table 1 AGAIN (Overlapping)...");
        Order order2 = new Order();
        order2.setReference("CMD-002");
        order2.setCreationDatetime(now);
        order2.setTable(table1);
        order2.setInstallationDate(now.plus(10, ChronoUnit.MINUTES));
        order2.setDepartureDate(now.plus(40, ChronoUnit.MINUTES));

        try {
            dataRetriever.saveOrder(order2);
            System.err.println("FAILURE: Order CMD-002 should have failed but succeeded!");
        } catch (RuntimeException e) {
            System.out.println("SUCCESS (Caught Exception): " + e.getMessage());
            // Expected: "La table numéro 1 n'est pas disponible, les tables numéro 2 et 3 sont actuellement libres"
        }

        // Scenario 3: Saturation (Fill remaining tables)
        System.out.println("\n[3] Filling all tables to test saturation...");
        // Fill Table 2
        RestaurantTable table2 = allTables.stream().filter(t -> t.getNumber() == 2).findFirst().orElseThrow();
        Order orderTable2 = new Order();
        orderTable2.setReference("CMD-003");
        orderTable2.setCreationDatetime(now);
        orderTable2.setTable(table2);
        orderTable2.setInstallationDate(now);
        orderTable2.setDepartureDate(oneHourLater);
        dataRetriever.saveOrder(orderTable2);
        System.out.println("Table 2 occupied.");

        // Fill Table 3
        RestaurantTable table3 = allTables.stream().filter(t -> t.getNumber() == 3).findFirst().orElseThrow();
        Order orderTable3 = new Order();
        orderTable3.setReference("CMD-004");
        orderTable3.setCreationDatetime(now);
        orderTable3.setTable(table3);
        orderTable3.setInstallationDate(now);
        orderTable3.setDepartureDate(oneHourLater);
        dataRetriever.saveOrder(orderTable3);
        System.out.println("Table 3 occupied.");

        // Try booking Table 1 again (All full)
        System.out.println("\n[4] Attempting to book any table when ALL are full...");
        Order order3 = new Order();
        order3.setReference("CMD-005");
        order3.setCreationDatetime(now);
        order3.setTable(table1);
        order3.setInstallationDate(now.plus(10, ChronoUnit.MINUTES));
        order3.setDepartureDate(now.plus(20, ChronoUnit.MINUTES));

        try {
            dataRetriever.saveOrder(order3);
            System.err.println("FAILURE: Order CMD-005 should have failed!");
        } catch (RuntimeException e) {
            System.out.println("SUCCESS (Caught Exception): " + e.getMessage());
            // Expected: "... aucun table n'est disponible"
        }
    }

    private static void resetData() {
        DBConnection db = new DBConnection();
        try (Connection conn = db.getConnection()) {
            conn.createStatement().execute("TRUNCATE TABLE dish_order, \"order\" RESTART IDENTITY CASCADE");
             System.out.println("[INFO] Data reset complete.");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void ensureTablesExist() {
        DBConnection db = new DBConnection();
        try (Connection conn = db.getConnection()) {
            conn.createStatement().execute("INSERT INTO restaurant_table (number) VALUES (1), (2), (3) ON CONFLICT (number) DO NOTHING");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
