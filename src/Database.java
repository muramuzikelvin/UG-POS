import java.sql.*;
import java.util.ArrayList;

public class Database {

    // NEW: Remember last sale so the window can show it
    public static double lastChange = 0;
    public static double lastTotal = 0;
    public static String lastReceipt = "";
    public static String lastLowStockWarning = "";
    public static String lastCartError = ""; // NEW: Explains exactly why a cart checkout failed

    // PART 1: Connect
    public static Connection connect() {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:pos.db");
            return conn;
        } catch (Exception e) {
            System.out.println("Database error: " + e.getMessage());
            return null;
        }
    }

    // PART 2: Create Tables
    public static void createTables() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS products (" +
                    "id INTEGER PRIMARY KEY, name TEXT, price REAL, pack_price REAL, " +
                    "stock INTEGER, pack_size INTEGER, low_stock INTEGER, unit TEXT, pack_unit TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sales (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, total REAL, cashier TEXT, " +
                    "phone TEXT, receipt_text TEXT, amount_given REAL, change_given REAL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sale_items (" +
                    "sale_id INTEGER, product_id INTEGER, quantity INTEGER, price REAL)");
            System.out.println("Tables ready!");

            // NEW: add payment_method to an EXISTING sales table. ALTER TABLE
            // fails if the column already exists, so we catch and ignore that
            // one specific case - this makes it safe to run every time the
            // app starts, without crashing on the 2nd, 3rd, 100th run.
            try {
                stmt.execute("ALTER TABLE sales ADD COLUMN payment_method TEXT DEFAULT 'Cash'");
            } catch (Exception alreadyExists) {
                // Column already exists from a previous run - nothing to do
            }

            // NEW: same ALTER TABLE pattern, this time adding expiry_date
            // to the EXISTING products table
            try {
                stmt.execute("ALTER TABLE products ADD COLUMN expiry_date TEXT DEFAULT ''");
            } catch (Exception alreadyExists) {
                // Column already exists from a previous run - nothing to do
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    // PART 3: Add Product
    public static boolean addProduct(Product p) {
        String sql = "INSERT OR REPLACE INTO products VALUES (?,?,?,?,?,?,?,?,?,?)"; // NEW: 10th ?
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, p.id);
            ps.setString(2, p.name);
            ps.setDouble(3, p.price);
            ps.setDouble(4, p.packPrice);
            ps.setInt(5, p.stock);
            ps.setInt(6, p.packSize);
            ps.setInt(7, p.lowStockLevel);
            ps.setString(8, p.unit);
            ps.setString(9, p.packUnit);
            ps.setString(10, p.expiryDate); // NEW
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            System.out.println("Error saving product: " + e.getMessage());
            return false;
        }
    }

    // RESTOCKING A PRODUCT
    public static boolean addStock(String productName, int extraPieces) {
        // stock = stock + ? ADDS to the existing value, instead of replacing it
        String sql = "UPDATE products SET stock = stock + ? WHERE name = ? COLLATE NOCASE";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, extraPieces);
            ps.setString(2, productName);
            int rows = ps.executeUpdate();
            return rows > 0; // 0 rows changed means no product had that name
        } catch (Exception e) {
            System.out.println("Error adding stock: " + e.getMessage());
            return false;
        }
    }

    // NEW: permanently records that a day was closed - date, total, how many
// sales, and who closed it. This is what makes a closed day "identified"
// later: you can always look back and see exactly when and by whom.
    public static String closeDay() {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = connect()) {

            // Create the table the first time this is ever called
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS day_closures (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, close_date TEXT, " +
                            "total_sales REAL, transaction_count INTEGER, closed_by TEXT, closed_at TEXT)");

            // Don't allow closing the same calendar day twice
            ResultSet already = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) as c FROM day_closures WHERE close_date = date('now')");
            if (already.next() && already.getInt("c") > 0) {
                return "Today has ALREADY been closed. See Check Records for details.";
            }

            // Work out today's totals from the sales table
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) as cnt, COALESCE(SUM(total),0) as total " +
                            "FROM sales WHERE date(date) = date('now')");
            int count = 0;
            double total = 0;
            if (rs.next()) {
                count = rs.getInt("cnt");
                total = rs.getDouble("total");
            }

            // Save this closure permanently
            PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO day_closures (close_date, total_sales, transaction_count, closed_by, closed_at) " +
                            "VALUES (date('now'), ?, ?, ?, datetime('now'))");
            insert.setDouble(1, total);
            insert.setInt(2, count);
            insert.setString(3, POSApp.currentCashier);
            insert.executeUpdate();

            sb.append("===== DAY CLOSED =====\n");
            sb.append("Transactions: ").append(count).append("\n");
            sb.append("Total sales: ").append(String.format("%,.0f", total)).append(" UGX\n");
            sb.append("Closed by: ").append(POSApp.currentCashier).append("\n");

        } catch (Exception e) {
            sb.append("Error closing day: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // NEW: reverses PART or ALL of a past sale - restores stock for the
    // refunded quantity, and records the refund as its own permanent event
    // (separate from the original sale, which is NEVER modified or deleted).
    public static String processRefund(int saleId, String productName, int qty, boolean isPack) {
        try (Connection conn = connect()) {

            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS refunds (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER, product_name TEXT, " +
                            "quantity INTEGER, amount_refunded REAL, refunded_by TEXT, refunded_at TEXT)");

            // Confirm the sale actually exists before refunding anything
            PreparedStatement checkSale = conn.prepareStatement("SELECT id FROM sales WHERE id = ?");
            checkSale.setInt(1, saleId);
            ResultSet saleRs = checkSale.executeQuery();
            if (!saleRs.next()) {
                return "No sale found with ID " + saleId + ".";
            }

            Product p = getProductByName(productName);
            if (p == null) {
                return "Product not found: " + productName;
            }

            int piecesToRestore = isPack ? qty * p.packSize : qty;
            double unitPrice = isPack ? p.packPrice : p.price;
            double amountRefunded = unitPrice * qty;

            // Give the stock back
            PreparedStatement restock = conn.prepareStatement(
                    "UPDATE products SET stock = stock + ? WHERE name = ? COLLATE NOCASE");
            restock.setInt(1, piecesToRestore);
            restock.setString(2, productName);
            restock.executeUpdate();

            // Record the refund as its OWN event - the original sale stays untouched
            PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO refunds (sale_id, product_name, quantity, amount_refunded, refunded_by, refunded_at) " +
                            "VALUES (?, ?, ?, ?, ?, datetime('now'))");
            insert.setInt(1, saleId);
            insert.setString(2, productName);
            insert.setInt(3, qty);
            insert.setDouble(4, amountRefunded);
            insert.setString(5, POSApp.currentCashier);
            insert.executeUpdate();

            return "Refund processed: " + qty + " " + (isPack ? p.packUnit : p.unit)
                    + "(s) of " + productName + " - " + String.format("%,.0f", amountRefunded) + " UGX refunded.";

        } catch (Exception e) {
            return "Error processing refund: " + e.getMessage();
        }
    }

    // NEW: fetches one specific sale's saved receipt text, by its ID -
    // used by the REFUND button so the admin can see what was actually
    // bought before deciding what to refund.
    public static String getReceiptForSale(int saleId) {
        try (Connection conn = connect()) {
            PreparedStatement ps = conn.prepareStatement("SELECT receipt_text FROM sales WHERE id = ?");
            ps.setInt(1, saleId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("receipt_text");
            }
        } catch (Exception e) {
            System.out.println("Error fetching receipt: " + e.getMessage());
        }
        return null; // no sale with that ID
    }

    // NEW: fetches every sale's receipt text for ONE calendar day, combined
    // into one block of text - used when an admin double-clicks a
    // "DAY CLOSED" row in Check Records, so they can see everything that
    // was sold that day, not just a dead-end summary.
    public static String getReceiptsForDate(String date) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = connect()) {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM sales WHERE date(date) = ? ORDER BY date ASC");
            ps.setString(1, date);
            ResultSet rs = ps.executeQuery();
            boolean foundAny = false;
            while (rs.next()) {
                foundAny = true;
                sb.append("Sale #").append(rs.getInt("id")).append("\n");
                sb.append(rs.getString("receipt_text")).append("\n");
                sb.append("========================================\n\n");
            }
            if (!foundAny) {
                sb.append("No sales recorded for ").append(date).append(".");
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // NEW: records that a day was formally STARTED - the counterpart to
    // closeDay(). Kept in its OWN table (day_opens) rather than reusing
    // day_closures, so we never risk corrupting your existing close history.
    public static String startDay() {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = connect()) {

            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS day_opens (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, open_date TEXT, " +
                            "opened_by TEXT, opened_at TEXT)");

            // Don't allow starting the same calendar day twice
            ResultSet already = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) as c FROM day_opens WHERE open_date = date('now')");
            if (already.next() && already.getInt("c") > 0) {
                return "Today has ALREADY been started.";
            }

            PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO day_opens (open_date, opened_by, opened_at) " +
                            "VALUES (date('now'), ?, datetime('now'))");
            insert.setString(1, POSApp.currentCashier);
            insert.executeUpdate();

            sb.append("===== DAY STARTED =====\n");
            sb.append("Opened by: ").append(POSApp.currentCashier).append("\n");
            sb.append("Time: ").append(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))).append("\n");

        } catch (Exception e) {
            sb.append("Error starting day: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // NEW: finds the exact timestamp of TODAY's most recent CLOSE DAY event,
    // or null if today hasn't been closed yet. POSApp will use this to check
    // whether a given sale happened BEFORE or AFTER that moment.
    public static String getTodaysCloseTimestamp() {
        try (Connection conn = connect()) {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT closed_at FROM day_closures WHERE close_date = date('now') " +
                            "ORDER BY closed_at DESC LIMIT 1");
            if (rs.next()) {
                return rs.getString("closed_at");
            }
        } catch (Exception e) {
            System.out.println("Error checking close time: " + e.getMessage());
        }
        return null; // today hasn't been closed yet
    }

    // PART 4: Get Product by ID
    public static Product getProduct(int id) {
        try {
            Connection conn = connect();
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM products WHERE id = ?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Product p = new Product(rs.getInt("id"), rs.getString("name"), rs.getDouble("price"), rs.getDouble("pack_price"), rs.getInt("stock"), rs.getInt("pack_size"), rs.getInt("low_stock"), rs.getString("unit"), rs.getString("pack_unit"), rs.getString("expiry_date"));
                conn.close();
                return p;
            }
            conn.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return null;
    }

    // PART 5: Get Product by NAME - for cashier typing name
    public static Product getProductByName(String name) {
        try {
            Connection conn = connect();
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM products WHERE name = ? COLLATE NOCASE");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Product p = new Product(rs.getInt("id"), rs.getString("name"), rs.getDouble("price"), rs.getDouble("pack_price"), rs.getInt("stock"), rs.getInt("pack_size"), rs.getInt("low_stock"), rs.getString("unit"), rs.getString("pack_unit"), rs.getString("expiry_date"));
                conn.close();
                return p;
            }
            conn.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return null;
    }


    // PART 6: Sell with Change + SMS Fee - Option A: Block if money not enough
    public static boolean processSale(String productName, int qty, boolean isPack, double amountGiven, String customerPhone, boolean wantsSMS) {
        try {
            Product p = getProductByName(productName);
            if (p == null) {
                System.out.println("Product not found: " + productName);
                return false;
            }

            // 1. CHECK STOCK
            int neededPieces = isPack ? qty * p.packSize : qty;
            if (p.stock < neededPieces) {
                System.out.println("BLOCKED! Only " + p.stock + " left. Tried to sell " + neededPieces);
                return false;
            }

            // 2. CALCULATE TOTAL
            double goodsTotal = isPack ? qty * p.packPrice : qty * p.price;
            String saleDescription = isPack ? qty + " pack (" + neededPieces + " biscuits)" : qty + " biscuits";

            // 3. SMS FEE - Customer pays 100 if they want SMS
            double smsFee = 0;
            if (wantsSMS) {
                smsFee = 100;
                if (customerPhone == null || customerPhone.isEmpty()) {
                    System.out.println("BLOCKED! Customer wants SMS but no phone number given. Ask for number!");
                    return false;
                }
            }
            double totalToPay = goodsTotal + smsFee;

            // 4. CHECK MONEY - Block if not enough
            if (amountGiven < totalToPay) {
                double stillOwed = totalToPay - amountGiven;
                System.out.println("=== NOT ENOUGH MONEY! BLOCKED ===");
                System.out.println("Goods: " + goodsTotal + " + SMS Fee: " + smsFee + " = Total: " + totalToPay + " UGX");
                System.out.println("Customer gave: " + amountGiven + " UGX");
                System.out.println("DEMAND MORE: " + stillOwed + " UGX");
                System.out.println("Do NOT give goods yet!");
                return false;
            }
            // 5. CALCULATE CHANGE
            double change = amountGiven - totalToPay;
            lastChange = change;
            lastTotal = totalToPay;

            // 6. REDUCE STOCK
            Connection conn = connect();
            PreparedStatement update = conn.prepareStatement("UPDATE products SET stock = stock - ? WHERE name = ?");
            update.setInt(1, neededPieces);
            update.setString(2, productName);
            update.executeUpdate();

            // 7. SAVE SALE + RECEIPT
            String receiptText;
            if (wantsSMS) {
                receiptText = "KampalaPOS: " + productName + " " + saleDescription + " = " + goodsTotal + " + SMS 100 = " + totalToPay + " UGX. Given " + amountGiven + ". Balance " + change + ". Thank you!";
            } else {
                receiptText = "KampalaPOS: " + productName + " " + saleDescription + " = " + totalToPay + " UGX. Given " + amountGiven + ". Balance " + change + ". Receipt saved in shop system.";
            }
            lastReceipt = receiptText;

            PreparedStatement sale = conn.prepareStatement("INSERT INTO sales(date, total, cashier, phone, receipt_text, amount_given, change_given) VALUES(datetime('now'), ?, ?, ?, ?, ?, ?)");
            sale.setDouble(1, totalToPay);
            sale.setString(2, POSApp.currentCashier);
            sale.setString(3, wantsSMS ? customerPhone : "NO SMS");
            sale.setString(4, receiptText);
            sale.setDouble(5, amountGiven);
            sale.setDouble(6, change);
            sale.executeUpdate();
            lastChange = change;
            lastTotal = totalToPay;
            lastReceipt = receiptText;
            conn.close();

            // 8. PRINT + SMS
            System.out.println("------ RECEIPT SAVED ------");
            System.out.println(receiptText);
            System.out.println("Stock left: " + (p.stock - neededPieces));
            if (change > 0) {
                System.out.println(">>> GIVE CUSTOMER BALANCE: " + change + " UGX <<<");
            } else {
                System.out.println(">>> EXACT AMOUNT. No balance. <<<");
            }
            if (wantsSMS) {
                System.out.println("SMS SENT to " + customerPhone + ": " + receiptText);
                System.out.println("SMS Cost to shop: 50 UGX. Charged to customer: 100 UGX. Shop profit: 50 UGX");
            } else {
                System.out.println("No SMS. Customer saved 100 UGX. Receipt stored in system only.");
                System.out.println("Paper receipt? Only if customer asks.");
            }
            System.out.println("----------------------------");
            int stockLeft = p.stock - neededPieces; // how many will remain after this sale
            if (stockLeft <= p.lowStockLevel) {
                lastLowStockWarning = "LOW STOCK WARNING: " + productName + " has only "
                        + stockLeft + " " + p.unit + "(s) left! Please restock soon.";
            } else {
                lastLowStockWarning = ""; // clear it so an old warning doesn't linger on later sales
            }
            return true;

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return false;
        }
    }
    // Get all products for the dropdown
    public static java.util.ArrayList<Product> getAllProducts() {
        java.util.ArrayList<Product> list = new java.util.ArrayList<>();
        try {
            Connection conn = connect();
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT * FROM products");
            while (rs.next()) {
                Product p = new Product(rs.getInt("id"), rs.getString("name"), rs.getDouble("price"), rs.getDouble("pack_price"), rs.getInt("stock"), rs.getInt("pack_size"), rs.getInt("low_stock"), rs.getString("unit"), rs.getString("pack_unit"), rs.getString("expiry_date"));
                list.add(p);
            }
            conn.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return list;
    }
    public static boolean addProduct(String name, double price, double packPrice, int stock, int packSize, String expiryDate) {
        //Column name here MUST exactly match the ones in CREATE TABLE above:
        // pack_price (not packPrice), pack_size (not packSize), low_stock (not lowStockLevel)

        // NEW: if packSize is 1, there's no REAL pack option for this product -
        // calling it "piece" instead of the literal word "pack" stops dialogs
        // elsewhere (like ADD STOCK) from confusingly asking "piece or pack?"
        // when pack was never a real choice to begin with.
        String packUnitValue = (packSize == 1) ? "piece" : "pack";

        String sql = "INSERT INTO products (name, price, pack_price, stock, pack_size, " +
                "low_stock, unit, pack_unit, expiry_date) VALUES (?, ?, ?, ?, ?, 5, 'piece', ?, ?)";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, name);
            ps.setDouble(2, price);
            ps.setDouble(3, packPrice);
            ps.setInt(4, stock);
            ps.setInt(5, packSize);
            ps.setString(6, packUnitValue); // NEW
            ps.setString(7, expiryDate);

            int rows = ps.executeUpdate();
            return rows > 0;

        } catch (Exception e){
            System.out.println("Error adding product: " + e.getMessage());
            return false;
        }

    }


    public static void createAdminTable() {
        try {
            Connection conn = connect();
            Statement s = conn.createStatement();
            s.execute("CREATE TABLE IF NOT EXISTS admin(password TEXT)");
            // if empty, set default 1234
            ResultSet rs = s.executeQuery("SELECT COUNT(*) as c FROM admin");
            if (rs.next() && rs.getInt("c") == 0) {
                s.execute("INSERT INTO admin VALUES('1234')");
            }
            conn.close();
        } catch (Exception e) { System.out.println(e.getMessage()); }
    }

    public static boolean checkPassword(String pass) {
        try {
            Connection conn = connect();
            ResultSet rs = conn.createStatement().executeQuery("SELECT password FROM admin");
            if (rs.next()) {
                boolean ok = rs.getString("password").equals(pass);
                conn.close();
                return ok;
            }
            conn.close();
        } catch (Exception e) {}
        return false;
    }

    public static boolean changePassword(String oldPass, String newPass) {
        if (!checkPassword(oldPass)) return false;
        try {
            Connection conn = connect();
            PreparedStatement ps = conn.prepareStatement("UPDATE admin SET password = ?");
            ps.setString(1, newPass);
            ps.executeUpdate();
            conn.close();
            return true;
        } catch (Exception e) { return false; }
    }
    // ===================================================================
    // NEW: Processes an ENTIRE CART in one go - one customer's full basket
    // of different products - instead of one product per sale. This is
    // what finally uses the "sale_items" table that existed since Day 1
    // but was never actually written to.
    //
    // Design: ONE row goes into "sales" (the whole transaction's total,
    // cashier, amount given, change). MANY rows go into "sale_items" -
    // one per product in the cart - each linked back by sale_id.
    // ===================================================================
    public static boolean processCartSale(ArrayList<CartItem> cart, double amountGiven,
                                          String customerPhone, boolean wantsSMS, String paymentMethod,
                                          double discountAmount, String discountDescription) {
        lastCartError = ""; // clear any previous error before trying again

        if (cart == null || cart.isEmpty()) {
            lastCartError = "Cart is empty - add at least one product before checking out.";
            return false;
        }

        try (Connection conn = connect()) {

            // STEP A: Check stock for EVERY item BEFORE changing anything.
            // Checking everything first (instead of item-by-item as we go)
            // stops a "half sale" where item 1 gets sold but item 3 fails -
            // either the WHOLE cart succeeds, or NOTHING is changed.
            for (CartItem item : cart) {
                Product p = getProductByName(item.productName);
                if (p == null) {
                    lastCartError = "Product not found: " + item.productName;
                    return false;
                }
                if (p.stock < item.piecesNeeded) {
                    lastCartError = "OUT OF STOCK: " + item.productName + " only has "
                            + p.stock + " " + p.unit + "(s) left, cart needs " + item.piecesNeeded + ".";
                    return false;
                }
            }

            // STEP B: Add up the whole cart's cost + any SMS fee
            double goodsTotal = CartItem.cartTotal(cart);
            double smsFee = 0;
            if (wantsSMS) {
                smsFee = 100;
                if (customerPhone == null || customerPhone.isEmpty()) {
                    lastCartError = "SMS was requested but no phone number was entered.";
                    return false;
                }
            }
            double totalBeforeDiscount = goodsTotal + smsFee;
            // NEW: subtract the discount, but never let the total go below 0
            double totalToPay = totalBeforeDiscount - discountAmount;
            if (totalToPay < 0) totalToPay = 0;

            // STEP C: Check the money covers the WHOLE cart, not one item
            if (amountGiven < totalToPay) {
                lastCartError = "INSUFFICIENT FUNDS. Needed " + String.format("%,.0f", totalToPay) + " UGX.";
                return false;
            }
            double change = amountGiven - totalToPay;

            // STEP D: Build ONE combined receipt listing every item bought
            StringBuilder receiptBuilder = new StringBuilder();
            receiptBuilder.append("KampalaPOS Receipt\n");
            receiptBuilder.append("------------------------------\n");
            for (CartItem item : cart) {
                receiptBuilder.append(item.describeLine()).append("\n");
            }
            if (wantsSMS) receiptBuilder.append("SMS fee: 100 UGX\n");
            receiptBuilder.append("------------------------------\n");
            if (discountAmount > 0) { // NEW: only show these lines if a discount was actually applied
                receiptBuilder.append("Subtotal: ").append(String.format("%,.0f", totalBeforeDiscount)).append(" UGX\n");
                receiptBuilder.append("Discount (").append(discountDescription).append("): -")
                        .append(String.format("%,.0f", discountAmount)).append(" UGX\n");
            }
            receiptBuilder.append("Payment: ").append(paymentMethod).append("\n");
            receiptBuilder.append("TOTAL:  ").append(String.format("%,.0f", totalToPay)).append(" UGX\n");
            receiptBuilder.append("PAID:   ").append(String.format("%,.0f", amountGiven)).append(" UGX\n");
            receiptBuilder.append("CHANGE: ").append(String.format("%,.0f", change)).append(" UGX\n");
            String receiptText = receiptBuilder.toString();

            // STEP E: Save ONE row in "sales" for the whole transaction
            PreparedStatement sale = conn.prepareStatement(
                    "INSERT INTO sales(date, total, cashier, phone, receipt_text, amount_given, change_given, payment_method) " +
                            "VALUES(datetime('now'), ?, ?, ?, ?, ?, ?, ?)");
            sale.setDouble(1, totalToPay);
            sale.setString(2, POSApp.currentCashier);
            sale.setString(3, wantsSMS ? customerPhone : "NO SMS");
            sale.setString(4, receiptText);
            sale.setDouble(5, amountGiven);
            sale.setDouble(6, change);
            sale.setString(7, paymentMethod); // NEW
            sale.executeUpdate();

            // Find the ID SQLite just assigned to that row
            ResultSet idrs = conn.createStatement().executeQuery("SELECT last_insert_rowid() AS id");
            int saleId = -1;
            if (idrs.next()) saleId = idrs.getInt("id");

            // NEW: permanently record the discount, linked to this sale - so
            // "how much did we give away in discounts this month?" is answerable later
            if (discountAmount > 0) {
                conn.createStatement().execute(
                        "CREATE TABLE IF NOT EXISTS discounts (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER, description TEXT, " +
                                "amount REAL, authorized_by TEXT, applied_at TEXT)");

                PreparedStatement discInsert = conn.prepareStatement(
                        "INSERT INTO discounts (sale_id, description, amount, authorized_by, applied_at) " +
                                "VALUES (?, ?, ?, ?, datetime('now'))");
                discInsert.setInt(1, saleId);
                discInsert.setString(2, discountDescription);
                discInsert.setDouble(3, discountAmount);
                discInsert.setString(4, POSApp.currentCashier);
                discInsert.executeUpdate();
            }

            // STEP F: For EACH item - reduce stock, save its sale_items row
            StringBuilder lowStockMessages = new StringBuilder();
            for (CartItem item : cart) {
                PreparedStatement update = conn.prepareStatement(
                        "UPDATE products SET stock = stock - ? WHERE name = ? COLLATE NOCASE");
                update.setInt(1, item.piecesNeeded);
                update.setString(2, item.productName);
                update.executeUpdate();

                Product p = getProductByName(item.productName); // re-fetch for its id + updated stock
                PreparedStatement itemInsert = conn.prepareStatement(
                        "INSERT INTO sale_items(sale_id, product_id, quantity, price) VALUES (?, ?, ?, ?)");
                itemInsert.setInt(1, saleId);
                itemInsert.setInt(2, p != null ? p.id : -1);
                itemInsert.setInt(3, item.qty);
                itemInsert.setDouble(4, item.unitPrice);
                itemInsert.executeUpdate();

                if (p != null && p.stock <= p.lowStockLevel) {
                    lowStockMessages.append("LOW STOCK: ").append(item.productName)
                            .append(" has only ").append(p.stock).append(" ").append(p.unit).append("(s) left!\n");
                }
            }

            // Remember results, same pattern as the old processSale()
            lastTotal = totalToPay;
            lastChange = change;
            lastReceipt = receiptText;
            lastLowStockWarning = lowStockMessages.toString();

            return true;

        } catch (Exception e) {
            lastCartError = "Error: " + e.getMessage();
            return false;
        }
    }

}