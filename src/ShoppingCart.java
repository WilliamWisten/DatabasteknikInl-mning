import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

public class ShoppingCart {
    private final String sqlUrl = "jdbc:mysql://localhost:3306/WebShop";
    private String USER;
    private String PASSWORD;

    public void run() {
        loadDatabaseProperties();
        try (Connection con = DriverManager.getConnection(sqlUrl, USER, PASSWORD)) {
            System.out.println("Anslutning lyckades!");
            handleUserInteraction(con);
        } catch (SQLException e) {
            System.out.println("Kunde inte ansluta till databasen: " + e.getMessage());
        }
    }

    private void loadDatabaseProperties() {
        Properties prop = new Properties();
        try (FileInputStream input = new FileInputStream("src/database.properties")) {
            prop.load(input);
            USER = prop.getProperty("user");
            PASSWORD = prop.getProperty("password");
        } catch (IOException e) {
            System.out.println("Kunde inte ladda in properties.");
        }
    }

    private void handleUserInteraction(Connection con) {
        try (Scanner scan = new Scanner(System.in)) {
            System.out.println("Logga in för att fortsätta:");
            System.out.print("Namn: ");
            String username = scan.nextLine();
            System.out.print("Lösenord: ");
            String password = scan.nextLine();

            if (login(con, username, password)) {
                System.out.println("Inloggning lyckades!");
                int customerId = getCustomerId(con, username);

                System.out.println("Välj en skomodell:");
                List<String> availableModels = executeQuery(con, "SELECT DISTINCT model FROM shoe",
                        resultSet -> processStringResultSet(resultSet, "model"));
                availableModels.forEach(System.out::println);
                System.out.print("Ange namnet på skomodellen: ");
                String modelName = scan.nextLine();
                if (!isValidInput(availableModels, modelName)) {
                    System.out.println("Ogiltigt modellnamn.");
                    return;
                }

                System.out.println("Välj en skostorlek:");
                List<Integer> availableSizes = executeQuery(con, "SELECT DISTINCT si.sizeNr FROM shoe sh JOIN " +
                        "inventory inv ON sh.id = inv.shoeId JOIN size si ON inv.sizeId = si.id WHERE sh.model = '" +
                        modelName + "'", resultSet -> processIntegerResultSet(resultSet, "sizeNr"));
                availableSizes.forEach(System.out::println);
                System.out.print("Ange storlek: ");
                int size = scan.nextInt();
                scan.nextLine();
                if (!isValidInput(availableSizes, size)) {
                    System.out.println("Ogiltig storlek.");
                    return;
                }

                System.out.println("Tillgängliga färger för " + modelName + " i storlek " + size + ":");
                List<String> availableColors = executeQuery(con, "SELECT DISTINCT co.colorName FROM inventory " +
                        "inv JOIN shoe sh ON inv.shoeId = sh.id JOIN size si ON inv.sizeId = si.id JOIN color co ON " +
                        "inv.colorId = co.id WHERE sh.model = '" + modelName + "' AND si.sizeNr = " + size +
                        " AND inv.quantity > 0", resultSet -> processStringResultSet(resultSet, "colorName"));
                availableColors.forEach(System.out::println);
                System.out.print("Ange önskad färg: ");
                String color = scan.nextLine();

                if (!isValidInput(availableColors, color)) {
                    System.out.println("Ogiltig färg.");
                    return;
                }

                System.out.print("Ange ett orderId för att lägga till i en befintlig order, " +
                        "lämna tomt för att skapa en ny order: ");
                String orderIdInput = scan.nextLine();
                Optional<Integer> orderId = Optional.empty();
                if (!orderIdInput.isEmpty()) {
                    try {
                        orderId = Optional.of(Integer.parseInt(orderIdInput));
                    } catch (NumberFormatException e) {
                        System.out.println("Felaktigt orderId. En ny order kommer att skapas.");
                    }
                }
                orderId.ifPresentOrElse(
                        id -> addToCart(con, modelName, size, color, customerId, id),
                        () -> addToCart(con, modelName, size, color, customerId, null)
                );
            } else {
                System.out.println("Inloggning misslyckades. Fel användarnamn eller lösenord.");
            }
        }
    }

    private int getCustomerId(Connection con, String username) {
        String query = "SELECT id FROM customer WHERE name = ?";
        try (PreparedStatement statement = con.prepareStatement(query)) {
            statement.setString(1, username);
            ResultSet resultSet = statement.executeQuery();
            return resultSet.next() ? resultSet.getInt("id") : -1;
        } catch (SQLException e) {
            System.out.println("Kunde inte hämta customerId: " + e.getMessage());
            return -1;
        }
    }

    private boolean login(Connection con, String username, String password) {
        String query = "SELECT * FROM customer WHERE name = ? AND password = ?";
        try (PreparedStatement statement = con.prepareStatement(query)) {
            statement.setString(1, username);
            statement.setString(2, password);
            ResultSet resultSet = statement.executeQuery();
            return resultSet.next();
        } catch (SQLException e) {
            System.out.println("Kunde inte utföra inloggning: " + e.getMessage());
            return false;
        }
    }

    private <T> T executeQuery(Connection con, String query, Function<ResultSet, T> processResultSet) {
        try (PreparedStatement statement = con.prepareStatement(query);
             ResultSet resultSet = statement.executeQuery()) {
            return processResultSet.apply(resultSet);
        } catch (SQLException e) {
            System.out.println("Kunde inte utföra fråga: " + e.getMessage());
            return null;
        }
    }

    private List<String> processStringResultSet(ResultSet resultSet, String columnName) {
        List<String> results = new ArrayList<>();
        try {
            while (resultSet.next()) {
                results.add(resultSet.getString(columnName));
            }
        } catch (SQLException e) {
            System.out.println("Kunde inte processa resultat: " + e.getMessage());
        }
        return results;
    }

    private List<Integer> processIntegerResultSet(ResultSet resultSet, String columnName) {
        List<Integer> results = new ArrayList<>();
        try {
            while (resultSet.next()) {
                results.add(resultSet.getInt(columnName));
            }
        } catch (SQLException e) {
            System.out.println("Kunde inte processa resultat: " + e.getMessage());
        }
        return results;
    }

    private boolean isValidInput(List<?> availableOptions, Object userInput) {
        return availableOptions.stream().anyMatch(option -> option.equals(userInput));
    }

    private void addToCart(Connection con, String modelName, int size, String color, int customerId, Integer orderId) {
        try {
            String getInventoryIdQuery = "SELECT inv.id " +
                    "FROM inventory inv " +
                    "JOIN shoe sh ON inv.shoeId = sh.id " +
                    "JOIN size si ON inv.sizeId = si.id " +
                    "JOIN color co ON inv.colorId = co.id " +
                    "WHERE sh.model = ? AND si.sizeNr = ? AND co.colorName = ?";
            try (PreparedStatement getInventoryIdStatement = con.prepareStatement(getInventoryIdQuery)) {
                getInventoryIdStatement.setString(1, modelName);
                getInventoryIdStatement.setInt(2, size);
                getInventoryIdStatement.setString(3, color);
                try (ResultSet resultSet = getInventoryIdStatement.executeQuery()) {
                    if (resultSet.next()) {
                        int inventoryId = resultSet.getInt("id");
                        String addToCartQuery = "CALL AddToCart(?, ?, ?)";
                        try (CallableStatement addToCartStatement = con.prepareCall(addToCartQuery)) {
                            addToCartStatement.setInt(1, customerId);
                            if (orderId != null) {
                                addToCartStatement.setInt(2, orderId);
                            } else {
                                addToCartStatement.setNull(2, Types.INTEGER);
                            }
                            addToCartStatement.setInt(3, inventoryId);
                            addToCartStatement.execute();
                            System.out.println("Produkten har lagts till i din beställning.");
                        }
                    } else {
                        System.out.println("Kunde inte hitta produkten med angiven modell, storlek och färg.");
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Kunde inte lägga till produkt i kundvagnen: " + e.getMessage());
        }
    }
}

