import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DataRetriever {

    Order findOrderByReference(String reference) {
        DBConnection dbConnection = new DBConnection();
        try (Connection connection = dbConnection.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("""
                    select o.id, o.reference, o.creation_datetime, o.installation_datetime, o.departure_datetime,
                      t.id as table_id, t.number as table_number
                    from "order" o
                    left join restaurant_table t on o.id_table = t.id
                    where o.reference like ?""");
            preparedStatement.setString(1, reference);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Order order = new Order();
                Integer idOrder = resultSet.getInt("id");
                order.setId(idOrder);
                order.setReference(resultSet.getString("reference"));
                order.setCreationDatetime(resultSet.getTimestamp("creation_datetime").toInstant());

                Timestamp installDate = resultSet.getTimestamp("installation_datetime");
                if (installDate != null) {
                    order.setInstallationDate(installDate.toInstant());
                }
                Timestamp departDate = resultSet.getTimestamp("departure_datetime");
                if (departDate != null) {
                    order.setDepartureDate(departDate.toInstant());
                }

                int tableId = resultSet.getInt("table_id");
                if (!resultSet.wasNull()) {
                   RestaurantTable table = new RestaurantTable();
                   table.setId(tableId);
                   table.setNumber(resultSet.getInt("table_number"));
                   order.setTable(table);
                }

                order.setDishOrderList(findDishOrderByIdOrder(idOrder));
                return order;
            }
            throw new RuntimeException("Order not found with reference " + reference);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<DishOrder> findDishOrderByIdOrder(Integer idOrder) {
        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        List<DishOrder> dishOrders = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select id, id_dish, quantity from dish_order where dish_order.id_order = ?
                            """);
            preparedStatement.setInt(1, idOrder);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                Dish dish = findDishById(resultSet.getInt("id_dish"));
                DishOrder dishOrder = new DishOrder();
                dishOrder.setId(resultSet.getInt("id"));
                dishOrder.setQuantity(resultSet.getInt("quantity"));
                dishOrder.setDish(dish);
                dishOrders.add(dishOrder);
            }
            dbConnection.closeConnection(connection);
            return dishOrders;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    Dish findDishById(Integer id) {
        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select dish.id as dish_id, dish.name as dish_name, dish_type, dish.selling_price as dish_price
                            from dish
                            where dish.id = ?;
                            """);
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Dish dish = new Dish();
                dish.setId(resultSet.getInt("dish_id"));
                dish.setName(resultSet.getString("dish_name"));
                dish.setDishType(DishTypeEnum.valueOf(resultSet.getString("dish_type")));
                dish.setPrice(resultSet.getObject("dish_price") == null
                        ? null : resultSet.getDouble("dish_price"));
                dish.setDishIngredients(findIngredientByDishId(id));
                return dish;
            }
            dbConnection.closeConnection(connection);
            throw new RuntimeException("Dish not found " + id);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    Ingredient saveIngredient(Ingredient toSave) {
        String upsertIngredientSql = """
                    INSERT INTO ingredient (id, name, price, category)
                    VALUES (?, ?, ?, ?::dish_type)
                    ON CONFLICT (id) DO UPDATE
                    SET name = EXCLUDED.name,
                        category = EXCLUDED.category,
                        price = EXCLUDED.price
                    RETURNING id
                """;

        try (Connection conn = new DBConnection().getConnection()) {
            conn.setAutoCommit(false);
            Integer ingredientId;
            try (PreparedStatement ps = conn.prepareStatement(upsertIngredientSql)) {
                if (toSave.getId() != null) {
                    ps.setInt(1, toSave.getId());
                } else {
                    ps.setInt(1, getNextSerialValue(conn, "ingredient", "id"));
                }
                if (toSave.getPrice() != null) {
                    ps.setDouble(2, toSave.getPrice());
                } else {
                    ps.setNull(2, Types.DOUBLE);
                }
                ps.setString(3, toSave.getName());
                ps.setString(4, toSave.getCategory().name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ingredientId = rs.getInt(1);
                }
            }

            insertIngredientStockMovements(conn, toSave);

            conn.commit();
            return findIngredientById(ingredientId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void insertIngredientStockMovements(Connection conn, Ingredient ingredient) {
        List<StockMovement> stockMovementList = ingredient.getStockMovementList();
        String sql = """
                insert into stock_movement(id, id_ingredient, quantity, type, unit, creation_datetime)
                values (?, ?, ?, ?::movement_type, ?::unit, ?)
                on conflict (id) do nothing
                """;
        try {
            PreparedStatement preparedStatement = conn.prepareStatement(sql);
            for (StockMovement stockMovement : stockMovementList) {
                if (ingredient.getId() != null) {
                    preparedStatement.setInt(1, ingredient.getId());
                } else {
                    preparedStatement.setInt(1, getNextSerialValue(conn, "stock_movement", "id"));
                }
                preparedStatement.setInt(2, ingredient.getId());
                preparedStatement.setDouble(3, stockMovement.getValue().getQuantity());
                preparedStatement.setObject(4, stockMovement.getType());
                preparedStatement.setObject(5, stockMovement.getValue().getUnit());
                preparedStatement.setTimestamp(6, Timestamp.from(stockMovement.getCreationDatetime()));
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    Ingredient findIngredientById(Integer id) {
        DBConnection dbConnection = new DBConnection();
        try (Connection connection = dbConnection.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("select id, name, price, category from ingredient where id = ?;");
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int idIngredient = resultSet.getInt("id");
                String name = resultSet.getString("name");
                CategoryEnum category = CategoryEnum.valueOf(resultSet.getString("category"));
                Double price = resultSet.getDouble("price");
                return new Ingredient(idIngredient, name, category, price, findStockMovementsByIngredientId(idIngredient));
            }
            throw new RuntimeException("Ingredient not found " + id);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    List<StockMovement> findStockMovementsByIngredientId(Integer id) {

        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        List<StockMovement> stockMovementList = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select id, quantity, unit, type, creation_datetime
                            from stock_movement
                            where stock_movement.id_ingredient = ?;
                            """);
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                StockMovement stockMovement = new StockMovement();
                stockMovement.setId(resultSet.getInt("id"));
                stockMovement.setType(MovementTypeEnum.valueOf(resultSet.getString("type")));
                stockMovement.setCreationDatetime(resultSet.getTimestamp("creation_datetime").toInstant());

                StockValue stockValue = new StockValue();
                stockValue.setQuantity(resultSet.getDouble("quantity"));
                stockValue.setUnit(Unit.valueOf(resultSet.getString("unit")));
                stockMovement.setValue(stockValue);

                stockMovementList.add(stockMovement);
            }
            dbConnection.closeConnection(connection);
            return stockMovementList;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    Dish saveDish(Dish toSave) {
        String upsertDishSql = """
                    INSERT INTO dish (id, selling_price, name, dish_type)
                    VALUES (?, ?, ?, ?::dish_type)
                    ON CONFLICT (id) DO UPDATE
                    SET name = EXCLUDED.name,
                        dish_type = EXCLUDED.dish_type,
                        selling_price = EXCLUDED.selling_price
                    RETURNING id
                """;

        try (Connection conn = new DBConnection().getConnection()) {
            conn.setAutoCommit(false);
            Integer dishId;
            try (PreparedStatement ps = conn.prepareStatement(upsertDishSql)) {
                if (toSave.getId() != null) {
                    ps.setInt(1, toSave.getId());
                } else {
                    ps.setInt(1, getNextSerialValue(conn, "dish", "id"));
                }
                if (toSave.getPrice() != null) {
                    ps.setDouble(2, toSave.getPrice());
                } else {
                    ps.setNull(2, Types.DOUBLE);
                }
                ps.setString(3, toSave.getName());
                ps.setString(4, toSave.getDishType().name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    dishId = rs.getInt(1);
                }
            }

            List<DishIngredient> newDishIngredients = toSave.getDishIngredients();
            detachIngredients(conn, newDishIngredients);
            attachIngredients(conn, newDishIngredients);

            conn.commit();
            return findDishById(dishId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Ingredient> createIngredients(List<Ingredient> newIngredients) {
        if (newIngredients == null || newIngredients.isEmpty()) {
            return List.of();
        }
        List<Ingredient> savedIngredients = new ArrayList<>();
        DBConnection dbConnection = new DBConnection();
        Connection conn = dbConnection.getConnection();
        try {
            conn.setAutoCommit(false);
            String insertSql = """
                        INSERT INTO ingredient (id, name, category, price)
                        VALUES (?, ?, ?::ingredient_category, ?)
                        RETURNING id
                    """;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (Ingredient ingredient : newIngredients) {
                    if (ingredient.getId() != null) {
                        ps.setInt(1, ingredient.getId());
                    } else {
                        ps.setInt(1, getNextSerialValue(conn, "ingredient", "id"));
                    }
                    ps.setString(2, ingredient.getName());
                    ps.setString(3, ingredient.getCategory().name());
                    ps.setDouble(4, ingredient.getPrice());

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        int generatedId = rs.getInt(1);
                        ingredient.setId(generatedId);
                        savedIngredients.add(ingredient);
                    }
                }
                conn.commit();
                return savedIngredients;
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            dbConnection.closeConnection(conn);
        }
    }


    private void detachIngredients(Connection conn, List<DishIngredient> dishIngredients) {
        Map<Integer, List<DishIngredient>> dishIngredientsGroupByDishId = dishIngredients.stream()
                .collect(Collectors.groupingBy(dishIngredient -> dishIngredient.getDish().getId()));
        dishIngredientsGroupByDishId.forEach((dishId, dishIngredientList) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM dish_ingredient where id_dish = ?")) {
                ps.setInt(1, dishId);
                ps.executeUpdate(); // TODO: must be a grouped by batch
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void attachIngredients(Connection conn, List<DishIngredient> ingredients)
            throws SQLException {

        if (ingredients == null || ingredients.isEmpty()) {
            return;
        }
        String attachSql = """
                    insert into dish_ingredient (id, id_ingredient, id_dish, quantity_required, unit)
                    values (?, ?, ?, ?, ?::unit)
                """;

        try (PreparedStatement ps = conn.prepareStatement(attachSql)) {
            for (DishIngredient dishIngredient : ingredients) {
                ps.setInt(1, getNextSerialValue(conn, "dish_ingredient", "id"));
                ps.setInt(2, dishIngredient.getIngredient().getId());
                ps.setInt(3, dishIngredient.getDish().getId());
                ps.setDouble(4, dishIngredient.getQuantity());
                ps.setObject(5, dishIngredient.getUnit());
                ps.addBatch(); // Can be substitute ps.executeUpdate() but bad performance
            }
            ps.executeBatch();
        }
    }

    private List<DishIngredient> findIngredientByDishId(Integer idDish) {
        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        List<DishIngredient> dishIngredients = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select ingredient.id, ingredient.name, ingredient.price, ingredient.category, di.quantity_required, di.unit
                            from ingredient join dish_ingredient di on di.id_ingredient = ingredient.id where id_dish = ?;
                            """);
            preparedStatement.setInt(1, idDish);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                Ingredient ingredient = new Ingredient();
                ingredient.setId(resultSet.getInt("id"));
                ingredient.setName(resultSet.getString("name"));
                ingredient.setPrice(resultSet.getDouble("price"));
                ingredient.setCategory(CategoryEnum.valueOf(resultSet.getString("category")));

                DishIngredient dishIngredient = new DishIngredient();
                dishIngredient.setIngredient(ingredient);
                dishIngredient.setQuantity(resultSet.getObject("quantity_required") == null ? null : resultSet.getDouble("quantity_required"));
                dishIngredient.setUnit(Unit.valueOf(resultSet.getString("unit")));

                dishIngredients.add(dishIngredient);
            }
            dbConnection.closeConnection(connection);
            return dishIngredients;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    private String getSerialSequenceName(Connection conn, String tableName, String columnName)
            throws SQLException {

        String sql = "SELECT pg_get_serial_sequence(?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    private int getNextSerialValue(Connection conn, String tableName, String columnName)
            throws SQLException {

        String sequenceName = getSerialSequenceName(conn, tableName, columnName);
        if (sequenceName == null) {
            throw new IllegalArgumentException(
                    "Any sequence found for " + tableName + "." + columnName
            );
        }
        updateSequenceNextValue(conn, tableName, columnName, sequenceName);

        String nextValSql = "SELECT nextval(?)";

        try (PreparedStatement ps = conn.prepareStatement(nextValSql)) {
            ps.setString(1, sequenceName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void updateSequenceNextValue(Connection conn, String tableName, String columnName, String sequenceName) throws SQLException {
        String sql = String.format("SELECT COALESCE(MAX(%s), 0) FROM %s", columnName, tableName);
        int maxId = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    maxId = rs.getInt(1);
                }
            }
        }

        String setValSql;
        if (maxId == 0) {
            setValSql = String.format("SELECT setval('%s', 1, false)", sequenceName);
        } else {
            setValSql = String.format("SELECT setval('%s', %d, true)", sequenceName, maxId);
        }

        try (PreparedStatement ps = conn.prepareStatement(setValSql)) {
            ps.executeQuery();
        }
    }

    public Order saveOrder(Order orderToSave) {
        if (orderToSave.getTable() == null) {
             throw new RuntimeException("Table is mandatory for an order");
        }
        if (orderToSave.getInstallationDate() == null || orderToSave.getDepartureDate() == null) {
            throw new RuntimeException("Installation and departure dates are mandatory");
        }

        List<RestaurantTable> availableTables = findAvailableTables(orderToSave.getInstallationDate(), orderToSave.getDepartureDate());

        boolean isSelectedTableAvailable = availableTables.stream()
            .anyMatch(t -> t.getId().equals(orderToSave.getTable().getId()));

        if (!isSelectedTableAvailable) {
            String availableMsg = availableTables.isEmpty()
                ? "aucun table n'est disponible"
                : "les tables numéro " + availableTables.stream()
                      .map(t -> t.getNumber().toString())
                      .collect(Collectors.joining(" et ")) + " sont actuellement libres";

            throw new RuntimeException("La table numéro " + orderToSave.getTable().getNumber() + " n'est pas disponible, " + availableMsg);
        }

        // Proceed to save
        String upsertOrderSql = """
            INSERT INTO "order" (id, reference, creation_datetime, id_table, installation_datetime, departure_datetime)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET reference = EXCLUDED.reference,
                creation_datetime = EXCLUDED.creation_datetime,
                id_table = EXCLUDED.id_table,
                installation_datetime = EXCLUDED.installation_datetime,
                departure_datetime = EXCLUDED.departure_datetime
            RETURNING id
        """;

        try (Connection conn = new DBConnection().getConnection()) {
             conn.setAutoCommit(false);
             Integer orderId;
             try (PreparedStatement ps = conn.prepareStatement(upsertOrderSql)) {
                 if (orderToSave.getId() != null) {
                     ps.setInt(1, orderToSave.getId());
                 } else {
                     ps.setInt(1, getNextSerialValue(conn, "\"order\"", "id"));
                 }
                 ps.setString(2, orderToSave.getReference());
                 ps.setTimestamp(3, Timestamp.from(orderToSave.getCreationDatetime()));
                 ps.setInt(4, orderToSave.getTable().getId());
                 ps.setTimestamp(5, Timestamp.from(orderToSave.getInstallationDate()));
                 ps.setTimestamp(6, Timestamp.from(orderToSave.getDepartureDate()));

                 try (ResultSet rs = ps.executeQuery()) {
                     rs.next();
                     orderId = rs.getInt(1);
                 }
             }

             // Save DishOrders
             // First delete existing
             try (PreparedStatement psDelete = conn.prepareStatement("DELETE FROM dish_order WHERE id_order = ?")) {
                 psDelete.setInt(1, orderId);
                 psDelete.executeUpdate();
             }

             // Then insert new
             if (orderToSave.getDishOrderList() != null && !orderToSave.getDishOrderList().isEmpty()) {
                 String insertDishOrder = "INSERT INTO dish_order (id, id_order, id_dish, quantity) VALUES (?, ?, ?, ?)";
                 try (PreparedStatement psDish = conn.prepareStatement(insertDishOrder)) {
                     for (DishOrder dishOrder : orderToSave.getDishOrderList()) {
                          psDish.setInt(1, getNextSerialValue(conn, "dish_order", "id"));
                          psDish.setInt(2, orderId);
                          psDish.setInt(3, dishOrder.getDish().getId());
                          psDish.setInt(4, dishOrder.getQuantity());
                          psDish.addBatch();
                     }
                     psDish.executeBatch();
                 }
             }

             conn.commit();
             return findOrderByReference(orderToSave.getReference()); // Re-fetch to return complete object
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<RestaurantTable> findAvailableTables(Instant from, Instant to) {
        // A table is available if it does NOT have any overlapping order.
        // Overlap: (StartA <= EndB) and (EndA >= StartB)
        // So we want tables where NOT EXISTS (conflict)

        String sql = """
            SELECT t.id, t.number
            FROM restaurant_table t
            WHERE NOT EXISTS (
                SELECT 1 FROM "order" o
                WHERE o.id_table = t.id
                AND (o.installation_datetime < ? AND o.departure_datetime > ?)
            )
        """;
        /*
          Overlap logic corrected:
          Existing interval: [o.install, o.depart]
          New interval: [from, to]
          Overlap if: o.install < to AND o.depart > from
        */

        DBConnection dbConnection = new DBConnection();
        try (Connection connection = dbConnection.getConnection()) {
             PreparedStatement ps = connection.prepareStatement(sql);
             ps.setTimestamp(1, Timestamp.from(to));
             ps.setTimestamp(2, Timestamp.from(from));

             List<RestaurantTable> tables = new ArrayList<>();
             ResultSet rs = ps.executeQuery();
             while (rs.next()) {
                 tables.add(new RestaurantTable(rs.getInt("id"), rs.getInt("number")));
             }
             return tables;
        } catch (SQLException e) {
             throw new RuntimeException(e);
        }
    }
}
