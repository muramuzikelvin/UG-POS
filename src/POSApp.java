import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class POSApp {
    static String currentCashier = "Cashier1";
    public static void main(String[] args) {
        Database.createAdminTable();

        //SECURITY ON THE (x)/back button
        String cashierName = null;
        while(true)  {
            cashierName = JOptionPane.showInputDialog(null,
                    "Who is the cashier? (Required to start the till)");

            if (cashierName == null) {
                // incase they clicked (x)button or Cancel
                int choice = JOptionPane.showConfirmDialog(null,
                        "A cashier name is required to open the till. Exit the program?",
                        "Name Required", JOptionPane.YES_NO_OPTION);
                if (choice ==JOptionPane.YES_OPTION) {
                    System.exit(0);
                }

                //if "NO", the while loop just ask again
            } else if (cashierName.trim().isEmpty()) {
                JOptionPane.showMessageDialog(null, "Name cannot be blank. Try again");
            } else {
                currentCashier = cashierName.trim();
                break; // valid name entered - leave the loop, continue starting the app
            }
        }
        // --- SETUP DATA FIRST ---
        Database.createTables();
        // If products are empty, add 3 starter products
        if (Database.getAllProducts().isEmpty()) {
            // NEW: "" as the last argument means "doesn't expire" - these 3
            // starter products are just examples, with no real expiry tracked
            Database.addProduct(new Product(1, "Biscuits", 500, 5000, 60, 12, 12, "biscuit", "pack", ""));
            Database.addProduct(new Product(2, "Bread", 3000, 3000, 20, 1, 5, "loaf", "loaf", ""));
            Database.addProduct(new Product(3, "Soda", 2000, 24000, 48, 12, 12, "bottle", "crate", ""));
        }

        // --- BUILD THE WINDOW ---
        // JFrame = the whole window. Like a phone screen.
        JFrame frame = new JFrame("KampalaPOS - Cashier");
        frame.setSize(450, 550);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));
        JPanel inputPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        JPanel receiptPanel = new JPanel(new BorderLayout());

        // --- 1. PRODUCT DROPDOWN ---
        inputPanel.add(new JLabel("Product:"));
        JComboBox<String> productBox = new JComboBox<>();
        productBox.setEditable(true);


        // Fill dropdown with names from database
        ArrayList<Product> allProducts = Database.getAllProducts();
        for (Product p : allProducts) {
            productBox.addItem(p.name);
        }
        inputPanel.add(productBox);

        // --- 2. QUANTITY ---
        inputPanel.add(new JLabel("Quantity:"));
        JTextField qtyField = new JTextField("1");
        qtyField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) { qtyField.selectAll(); }
        });
        inputPanel.add(qtyField);

        // --- 3. PIECE OR PACK? ---
        inputPanel.add(new JLabel("Sell as:"));
        // JPanel groups the 2 radio buttons together
        JPanel radioPanel = new JPanel();
        JRadioButton pieceBtn = new JRadioButton("Piece", true); // true = selected by default
        JRadioButton packBtn = new JRadioButton("Pack");
        // ButtonGroup makes sure you can only pick ONE - Piece OR Pack, not both
        ButtonGroup group = new ButtonGroup();
        group.add(pieceBtn);
        group.add(packBtn);
        radioPanel.add(pieceBtn);
        radioPanel.add(packBtn);
        inputPanel.add(radioPanel);

        // --- 4. AMOUNT GIVEN ---
        inputPanel.add(new JLabel("Amount Given (UGX):"));
        JTextField amountField = new JTextField("0");
        amountField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) { amountField.selectAll(); }
        });
        inputPanel.add(amountField);

        // --- 5. PHONE ---
        inputPanel.add(new JLabel("Customer Phone:"));
        JTextField phoneField = new JTextField("");
        inputPanel.add(phoneField);

        // --- 6. WANTS SMS? ---
        inputPanel.add(new JLabel("SMS? ( +100 UGX ):"));
        JCheckBox smsCheck = new JCheckBox("Customer wants SMS");
        inputPanel.add(smsCheck);

        // --- NEW: PAYMENT METHOD ---
        inputPanel.add(new JLabel("Payment Method:"));
        JComboBox<String> paymentBox = new JComboBox<>(new String[]{"Cash", "Mobile Money", "Bank Transfer"});
        inputPanel.add(paymentBox);

        // --- CASHIER BUTTONS ---
        // NEW: SELL is renamed ADD TO CART - it no longer finalizes a sale by
        // itself. A separate CHECKOUT button finalizes the WHOLE cart at once.
        JButton sellBtn = new JButton("ADD TO CART");
        sellBtn.setBackground(Color.GREEN);
        inputPanel.add(sellBtn);

        JButton checkoutBtn = new JButton("CHECKOUT");
        checkoutBtn.setBackground(Color.CYAN);
        inputPanel.add(checkoutBtn);

        // NEW: lets the cashier fix a mistake before finalizing the sale.
        // Smaller font + grouped in ONE FlowLayout panel instead of two
        // separate grid cells - FlowLayout gives buttons their OWN natural
        // size, instead of GridLayout stretching each one to fill a full cell.
        JButton undoBtn = new JButton("UNDO");
        undoBtn.setBackground(Color.PINK);
        undoBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JButton clearCartBtn = new JButton("CLEAR");
        clearCartBtn.setBackground(Color.PINK);
        clearCartBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));

        // NEW: DISCOUNT button, same small-text style as UNDO/CLEAR
        JButton discountBtn = new JButton("DISCOUNT");
        discountBtn.setBackground(Color.PINK);
        discountBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JPanel cartControlsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        cartControlsPanel.add(undoBtn);
        cartControlsPanel.add(clearCartBtn);
        cartControlsPanel.add(discountBtn); // NEW
        inputPanel.add(cartControlsPanel); // ONE grid cell now holds all three buttons

        // NEW: START DAY lives on the cashier's own screen, no password -
        // opening the till is a normal daily task, not an admin-only action
        // like CLOSE DAY is.
        JButton startDayBtn = new JButton("START DAY");
        startDayBtn.setBackground(Color.GREEN.darker());
        startDayBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JPanel startDayWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        startDayWrapper.add(startDayBtn);
        inputPanel.add(startDayWrapper);

        JButton printBtn = new JButton("PRINT RECEIPT");
        printBtn.setBackground(Color.YELLOW);
        inputPanel.add(printBtn);

        // Everything that touches stock entry, new products or passwords now
        // lives behind this ONE password-protected button instead of sitting
        // loose on the cashier's screen.
        JButton adminBtn = new JButton("ADMIN PANEL");
        adminBtn.setBackground(Color.LIGHT_GRAY);
        adminBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JPanel adminPanelWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        adminPanelWrapper.add(adminBtn);
        inputPanel.add(adminPanelWrapper);
        inputPanel.add(new JLabel("")); // keeps the 2-column grid lined up

        // stockLabel must exist BEFORE refreshStockLabel (right below) can use it
        JLabel stockLabel = new JLabel("Stock: -");
        inputPanel.add(stockLabel);

        // NEW: purple warning shown when the selected product is close to expiry
        JLabel expiryWarningLabel = new JLabel(" ");
        expiryWarningLabel.setForeground(new Color(128, 0, 128)); // purple
        expiryWarningLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        inputPanel.add(new JLabel("")); // spacer so it lines up in the 2-column grid
        inputPanel.add(expiryWarningLabel);

        // --- UPDATE STOCK LABEL WHEN PRODUCT CHANGES ---
        // NEW: this logic is now a Runnable - a named block of code we can
        // trigger on demand from more than one place. Before, it only ran
        // inside productBox's own listener. Now the Admin Panel can call the
        // SAME code after a restock, so the cashier's screen updates instantly
        // instead of only refreshing when the dropdown selection changes.
        Runnable refreshStockLabel = () -> {
            String productName = (String) productBox.getSelectedItem();
            Product p = Database.getProductByName(productName); // already case-insensitive (COLLATE NOCASE)
            if (p != null) {
                stockLabel.setText("Stock: " + p.stock + " " + p.unit + "s | Price: " + p.price + " / Pack: " + p.packPrice);

                // NEW: purple warning if this product expires within 2 days
                if (p.isExpiringWithin(2)) {
                    expiryWarningLabel.setText("⚠ EXPIRES SOON: " + p.expiryDate + " - notify admin before selling!");
                } else {
                    expiryWarningLabel.setText(" ");
                }
            } else {
                stockLabel.setText("Stock: -");
                expiryWarningLabel.setText(" ");
            }
        };

        productBox.addActionListener(e -> refreshStockLabel.run());

        // Trigger it once at start so the label isn't empty
        if (productBox.getItemCount() > 0) {
            productBox.setSelectedIndex(0);
            refreshStockLabel.run();
        }

        // NOW that refreshStockLabel exists, wire up the Admin Panel button
        adminBtn.addActionListener(e -> {
            String pass = JOptionPane.showInputDialog(frame, "Enter Admin Password:");
            if (pass != null && Database.checkPassword(pass)) {
                openAdminPanel(frame, productBox, refreshStockLabel);
            } else if (pass != null) {
                JOptionPane.showMessageDialog(frame, "Wrong password!");
            }
        });



        // --- 8. RECEIPT BOX ---
        // JTextArea = big box for many lines of text, like receipt paper
        JTextArea receiptArea = new JTextArea("Receipt will appear here...\n");
        receiptArea.setEditable(false);
        receiptArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        receiptArea.setRows(11);
        receiptArea.setColumns(32);
        JScrollPane scroll = new JScrollPane(receiptArea);
        scroll.setPreferredSize(new Dimension(400, 200));
        // Span 2 columns: we add an empty label trick - just add scroll and let it fill
        receiptPanel.add(new JLabel("RECEIPT:"), BorderLayout.NORTH);
        receiptPanel.add(scroll, BorderLayout.CENTER);
        frame.add(inputPanel, BorderLayout.NORTH);
        frame.add(receiptPanel, BorderLayout.SOUTH);


        // NEW: the cart holds every item added before checkout - starts empty,
        // one per customer, cleared after a successful CHECKOUT
        ArrayList<CartItem> cart = new ArrayList<>();

        // NEW: holds the current discount, if any. Using a 1-element array
        // (instead of a plain double/String) is a standard Java trick - a
        // lambda can't reassign a normal local variable, but it CAN change
        // what's INSIDE an array, so this lets multiple button listeners
        // below all share and update the same discount value.
        double[] discountAmount = {0};
        String[] discountDescription = {""};


        // NEW: displays the current cart (or an "empty" message) in the
        // receipt box. Pulled out as its own reusable block because THREE
        // different buttons now need to refresh this same view: ADD TO CART,
        // UNDO LAST ITEM, and CLEAR CART.
        Runnable showCart = () -> {
            if (cart.isEmpty()) {
                receiptArea.setForeground(Color.BLACK);
                receiptArea.setText("Cart is empty. Add a product to begin a new sale.\n");
                return;
            }
            StringBuilder cartView = new StringBuilder();
            cartView.append("----- CURRENT CART -----\n");
            for (CartItem item : cart) {
                cartView.append(item.describeLine()).append("\n");
            }
            cartView.append("-------------------------\n");
            double subtotal = CartItem.cartTotal(cart);
            cartView.append("SUBTOTAL: ").append(String.format("%,.0f", subtotal)).append(" UGX\n");
            // NEW: show the discount and adjusted total, if one has been applied
            if (discountAmount[0] > 0) {
                cartView.append("DISCOUNT (").append(discountDescription[0]).append("): -")
                        .append(String.format("%,.0f", discountAmount[0])).append(" UGX\n");
                cartView.append("NEW TOTAL: ").append(String.format("%,.0f", subtotal - discountAmount[0])).append(" UGX\n");
            }
            cartView.append("(Add more items, or click CHECKOUT to finish)\n");
            receiptArea.setForeground(Color.BLACK);
            receiptArea.setText(cartView.toString());
        };

        // --- WHAT HAPPENS WHEN YOU CLICK "ADD TO CART" ---
        // This no longer finalizes a sale - it just adds one line to the
        // running cart. Money, phone, and SMS are only handled at CHECKOUT.
        sellBtn.addActionListener(e -> {
            try {
                String productName = (String) productBox.getSelectedItem();
                int qty = Integer.parseInt(qtyField.getText());
                boolean isPack = packBtn.isSelected();

                if (qty <= 0) {
                    JOptionPane.showMessageDialog(frame, "Quantity must be more than 0.");
                    return;
                }

                Product p = Database.getProductByName(productName);
                if (p == null) {
                    JOptionPane.showMessageDialog(frame, "Product not found: " + productName);
                    return;
                }

                // NEW: block selling something that's already expired
                if (p.isExpired()) {
                    JOptionPane.showMessageDialog(frame,
                            productName + " EXPIRED on " + p.expiryDate + ". This cannot be sold.",
                            "Expired Product", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                int piecesNeeded = isPack ? qty * p.packSize : qty;
                double unitPrice = isPack ? p.packPrice : p.price;

                // NEW: check stock against what's ALREADY sitting in the cart
                // too - stops adding the same product twice beyond real stock
                int piecesAlreadyInCart = 0;
                for (CartItem item : cart) {
                    if (item.productName.equalsIgnoreCase(productName)) {
                        piecesAlreadyInCart += item.piecesNeeded;
                    }
                }
                if (p.stock < piecesAlreadyInCart + piecesNeeded) {
                    JOptionPane.showMessageDialog(frame, "Not enough stock. Only "
                            + (p.stock - piecesAlreadyInCart) + " " + p.unit + "(s) available.");
                    return;
                }

                cart.add(new CartItem(productName, qty, isPack, unitPrice, piecesNeeded));
                showCart.run(); // NEW: use the shared cart display instead of repeating this code

            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Quantity must be a NUMBER.");
            }
        });

        // --- WHAT HAPPENS WHEN YOU CLICK "CHECKOUT" ---
        // Finalizes the WHOLE cart at once: one total, one payment, one receipt.
        checkoutBtn.addActionListener(e -> {
            try {
                if (cart.isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "Cart is empty. Add at least one product first.");
                    return;
                }

                double amountGiven = Double.parseDouble(amountField.getText());
                String phone = phoneField.getText().trim();
                boolean wantsSMS = smsCheck.isSelected();
                String paymentMethod = (String) paymentBox.getSelectedItem(); // NEW

                boolean success = Database.processCartSale(cart, amountGiven, phone, wantsSMS, paymentMethod,
                        discountAmount[0], discountDescription[0]);

                if (success) {
                    receiptArea.setForeground(Color.BLACK);
                    receiptArea.setText(Database.lastReceipt);

                    if (!Database.lastLowStockWarning.isEmpty()) {
                        receiptArea.append("\n*** " + Database.lastLowStockWarning + " ***\n");
                    }


                    // NEW: only attempt to print if a REAL printer is actually
                    // connected - PrintServiceLookup asks the operating system
                    // directly, rather than just trying to print and catching
                    // a failure after the fact.
                    PrintService[] availablePrinters = PrintServiceLookup.lookupPrintServices(null, null);
                    if (availablePrinters.length > 0) {
                        try {
                            // The "false" here SKIPS the print dialog box -
                            // it prints straight to the default printer
                            // silently, instead of interrupting the cashier
                            // with a dialog to confirm every single checkout.
                            boolean printed = receiptArea.print(null, null, false, null, null, false);
                            if (printed) {
                                receiptArea.append("\n--- Printed! ---\n");
                            }
                        } catch (Exception printEx) {
                            receiptArea.append("\nPrinter error: " + printEx.getMessage() + "\n");
                        }
                    }
                    // NEW: if NO printer is connected, we deliberately do
                    // NOTHING here - no dialog, no error message, no
                    // disruption. The receipt is already saved in the
                    // database regardless, so nothing is actually lost.

                    cart.clear(); // empty the cart - ready for the next customer
                    refreshStockLabel.run(); // stock just changed - update the label immediately

                    qtyField.setText("1");
                    pieceBtn.setSelected(true);
                    amountField.setText("0");
                    phoneField.setText("");
                    smsCheck.setSelected(false);
                    paymentBox.setSelectedIndex(0); // reset to "Cash" for the next customer
                    discountAmount[0] = 0; // NEW: clear the discount for the next customer
                    discountDescription[0] = "";
                } else {
                    receiptArea.setForeground(Color.RED);
                    receiptArea.setText(Database.lastCartError); // the EXACT reason, from Database.java
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Amount Given must be a NUMBER.");
            }
        });

        // --- UNDO LAST ITEM: removes only the most recently added cart line ---
        undoBtn.addActionListener(e -> {
            if (cart.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Cart is already empty - nothing to undo.");
                return;
            }
            CartItem removed = cart.remove(cart.size() - 1); // removes the LAST item added
            JOptionPane.showMessageDialog(frame, "Removed: " + removed.describeLine());
            showCart.run();
        });

        // --- CLEAR CART: wipes everything, starts fresh for a new customer ---
        clearCartBtn.addActionListener(e -> {
            if (cart.isEmpty()) return;
            int confirm = JOptionPane.showConfirmDialog(frame, "Clear the whole cart?",
                    "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                cart.clear();
                discountAmount[0] = 0; // NEW: a discount shouldn't carry over to a new customer
                discountDescription[0] = "";
                showCart.run();
            }
        });

        // --- DISCOUNT: password-gated, applies to the current cart's subtotal ---
        discountBtn.addActionListener(e -> {
            if (cart.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Cart is empty - add items before applying a discount.");
                return;
            }

            String pass = JOptionPane.showInputDialog(frame, "Enter Admin Password to apply a discount:");
            if (pass == null) return;
            if (!Database.checkPassword(pass)) {
                JOptionPane.showMessageDialog(frame, "Wrong password!");
                return;
            }

            String[] choices = {"Percentage (%)", "Fixed Amount (UGX)"};
            int choice = JOptionPane.showOptionDialog(frame, "What type of discount?", "Discount Type",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
            if (choice == JOptionPane.CLOSED_OPTION) return;

            try {
                double subtotal = CartItem.cartTotal(cart);
                if (choice == 0) { // Percentage
                    String pctStr = JOptionPane.showInputDialog(frame, "Enter percentage off (e.g. 10 for 10%):");
                    if (pctStr == null) return;
                    double pct = Double.parseDouble(pctStr);
                    if (pct <= 0 || pct > 100) {
                        JOptionPane.showMessageDialog(frame, "Percentage must be between 0 and 100.");
                        return;
                    }
                    discountAmount[0] = subtotal * (pct / 100.0);
                    discountDescription[0] = pct + "%";
                } else { // Fixed amount
                    String amtStr = JOptionPane.showInputDialog(frame, "Enter fixed discount amount (UGX):");
                    if (amtStr == null) return;
                    double amt = Double.parseDouble(amtStr);
                    if (amt <= 0 || amt > subtotal) {
                        JOptionPane.showMessageDialog(frame, "Discount must be more than 0 and no more than the subtotal.");
                        return;
                    }
                    discountAmount[0] = amt;
                    discountDescription[0] = "Fixed";
                }
                showCart.run(); // refresh the cart view to show the new discount
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Please enter a NUMBER.");
            }
        });


        // --- START DAY ---
        startDayBtn.addActionListener(e -> {
            String result = Database.startDay();
            JOptionPane.showMessageDialog(frame, result, "Start of Day", JOptionPane.INFORMATION_MESSAGE);
        });


        // --- WHAT HAPPENS WHEN YOU CLICK PRINT ---
        printBtn.addActionListener(e -> {
            try {
                // This is Java's built-in print - it opens the printer window
                // It prints whatever is in the RECEIPT box
                boolean printed = receiptArea.print();
                if (printed) {
                    receiptArea.append("\n--- Printed! ---\n");
                }
            } catch (Exception ex) {
                receiptArea.setText("No printer found! Receipt is still saved in system.\n");
            }
        });


        frame.setVisible(true);
    }

    // ===================================================================
    // ADMIN PANEL - only reachable after a correct password.
    // Holds everything that affects stock, new products, records or security.
    // ===================================================================
    static void openAdminPanel(JFrame parent, JComboBox<String> productBox, Runnable refreshStockLabel) {
        JFrame admin = new JFrame("Admin Panel");
        admin.setSize(300, 250);
        admin.setLayout(new GridLayout(0, 1, 5, 5));

        JButton addProductBtn = new JButton("ADD NEW PRODUCT");
        JButton addStockBtn = new JButton("ADD STOCK (Restock)");
        JButton recordsBtn = new JButton("CHECK RECORDS");
        JButton closeDayBtn = new JButton("CLOSE DAY");
        JButton refundBtn = new JButton("REFUND");
        JButton backupBtn = new JButton("BACKUP DATABASE");
        JButton expiryBtn = new JButton("CHECK EXPIRY"); // NEW
        JButton changePassBtn = new JButton("CHANGE PASSWORD");

        admin.add(addProductBtn);
        admin.add(addStockBtn);
        admin.add(recordsBtn);
        admin.add(closeDayBtn);
        admin.add(refundBtn);
        admin.add(backupBtn);
        admin.add(expiryBtn); // NEW
        admin.add(changePassBtn);
        // --- ADD NEW PRODUCT ---
        addProductBtn.addActionListener(e -> {
            try {
                String name = JOptionPane.showInputDialog(admin, "Product Name (e.g. Milk):");
                if (name == null || name.trim().isEmpty()) return;
                double price = Double.parseDouble(JOptionPane.showInputDialog(admin, "Price per Piece (e.g. 1500):"));
                int stock = Integer.parseInt(JOptionPane.showInputDialog(admin, "How many in stock now?:"));

                // NEW: ask whether this product even HAS a pack option at all -
                // not everything (a single candle, one bar of soap) is sold in bulk
                int hasPackChoice = JOptionPane.showConfirmDialog(admin,
                        "Does " + name.trim() + " have a pack/bulk option?",
                        "Pack Option", JOptionPane.YES_NO_OPTION);

                double packPrice;
                int packSize;
                if (hasPackChoice == JOptionPane.YES_OPTION) {
                    packPrice = Double.parseDouble(JOptionPane.showInputDialog(admin, "Price per Pack (e.g. 15000):"));
                    packSize = Integer.parseInt(JOptionPane.showInputDialog(admin, "How many pieces in 1 Pack? (e.g. 12):"));
                } else {
                    // NEW: piece-only product - "pack" becomes mathematically
                    // identical to "piece", so selecting Pack elsewhere in the
                    // app for this product just harmlessly behaves like Piece
                    packPrice = price;
                    packSize = 1;
                }

                String expiryDate = "";
                while (true) {
                    String expiryInput = JOptionPane.showInputDialog(admin,
                            "Expiry date (YYYY-MM-DD), or leave blank if this product doesn't expire:");
                    if (expiryInput == null) break; // admin cancelled - treat as "no expiry"
                    String trimmed = expiryInput.trim();
                    if (trimmed.isEmpty()) break; // blank is valid - means no expiry

                    // NEW: actually verify it's a REAL, valid date in the right
                    // format, instead of trusting whatever text was typed
                    try {
                        java.time.LocalDate.parse(trimmed); // throws if format/date is invalid
                        expiryDate = trimmed;
                        break;
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(admin,
                                "That's not a valid date. Please use the format YYYY-MM-DD (e.g. 2026-11-23).");
                        // loop back and ask again
                    }
                }
                boolean ok = Database.addProduct(name.trim(), price, packPrice, stock, packSize, expiryDate);
                if (ok) {
                    productBox.addItem(name.trim());
                    JOptionPane.showMessageDialog(admin, name + " added successfully.");
                } else {
                    JOptionPane.showMessageDialog(admin, "Failed to save product. Check the console for the error.");
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(admin, "Please enter NUMBERS for price/stock, not words.");
            }
        });

        // --- ADD STOCK (now supports restocking by PIECE or by PACK) ---
        addStockBtn.addActionListener(e -> {
            try {
                String name = JOptionPane.showInputDialog(admin, "Which product are you restocking?");
                if (name == null || name.trim().isEmpty()) return;

                Product existing = Database.getProductByName(name.trim());
                if (existing == null) {
                    JOptionPane.showMessageDialog(admin, "No product found with that name.");
                    return;
                }

                // NEW: ask whether this delivery is counted in pieces or packs -
                // same choice the SELL screen already offers the cashier.
                String[] choices = {existing.unit + "(s)", existing.packUnit + "(s)"};
                int choice = JOptionPane.showOptionDialog(admin,
                        "Current stock of " + existing.name + ": " + existing.stock + " " + existing.unit + "s.\n"
                                + "Are you adding stock by the " + existing.unit + " or by the " + existing.packUnit + "?",
                        "Restock Unit",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, choices, choices[0]);

                if (choice == JOptionPane.CLOSED_OPTION) return; // admin closed the dialog
                boolean isPack = (choice == 1);

                String amountStr = JOptionPane.showInputDialog(admin,
                        "How many " + (isPack ? existing.packUnit : existing.unit) + "s are you adding?");
                if (amountStr == null) return;
                int qty = Integer.parseInt(amountStr);
                if (qty <= 0) {
                    JOptionPane.showMessageDialog(admin, "Amount must be more than 0.");
                    return;
                }

                // Convert to pieces - SAME math processSale() already uses for selling
                int extraPieces = isPack ? qty * existing.packSize : qty;

                boolean ok = Database.addStock(existing.name, extraPieces);
                if (ok) {
                    refreshStockLabel.run();
                    JOptionPane.showMessageDialog(admin, "Stock updated: " + existing.name
                            + " now has " + (existing.stock + extraPieces) + " " + existing.unit + "s.");
                } else {
                    JOptionPane.showMessageDialog(admin, "Failed to update stock.");
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(admin, "Please enter a NUMBER for the stock amount.");
            }
        });



        // --- CHECK RECORDS ---
        recordsBtn.addActionListener(e -> showRecords());

        // --- CLOSE DAY ---
        closeDayBtn.addActionListener(e -> {
            String result = Database.closeDay();
            JTextArea area = new JTextArea(result);
            area.setEditable(false);
            area.setFont(new Font("Monospaced", Font.PLAIN, 13));
            JOptionPane.showMessageDialog(admin, area, "Close of Day", JOptionPane.INFORMATION_MESSAGE);
        });

        // --- REFUND ---
        refundBtn.addActionListener(e -> {
            try {
                String saleIdStr = JOptionPane.showInputDialog(admin,
                        "Enter the Sale ID to refund (see it in Check Records):");
                if (saleIdStr == null || saleIdStr.trim().isEmpty()) return;
                int saleId = Integer.parseInt(saleIdStr.trim());

                // Show the admin exactly what was in that sale before they decide what to refund
                String originalReceipt = Database.getReceiptForSale(saleId);
                if (originalReceipt == null) {
                    JOptionPane.showMessageDialog(admin, "No sale found with ID " + saleId + ".");
                    return;
                }
                JOptionPane.showMessageDialog(admin, originalReceipt, "Sale #" + saleId, JOptionPane.INFORMATION_MESSAGE);

                String productName = JOptionPane.showInputDialog(admin,
                        "Which product from this sale is being refunded?");
                if (productName == null || productName.trim().isEmpty()) return;

                Product p = Database.getProductByName(productName.trim());
                if (p == null) {
                    JOptionPane.showMessageDialog(admin, "No product found with that name.");
                    return;
                }

                String[] choices = {p.unit + "(s)", p.packUnit + "(s)"};
                int choice = JOptionPane.showOptionDialog(admin,
                        "Is the refunded quantity in " + p.unit + "s or " + p.packUnit + "s?",
                        "Refund Unit", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, choices, choices[0]);
                if (choice == JOptionPane.CLOSED_OPTION) return;
                boolean isPack = (choice == 1);

                String qtyStr = JOptionPane.showInputDialog(admin,
                        "How many " + (isPack ? p.packUnit : p.unit) + "(s) are being refunded?");
                if (qtyStr == null) return;
                int qty = Integer.parseInt(qtyStr.trim());
                if (qty <= 0) {
                    JOptionPane.showMessageDialog(admin, "Quantity must be more than 0.");
                    return;
                }

                String result = Database.processRefund(saleId, productName.trim(), qty, isPack);
                JOptionPane.showMessageDialog(admin, result, "Refund Result", JOptionPane.INFORMATION_MESSAGE);
                refreshStockLabel.run(); // stock just changed - update the cashier's screen

            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(admin, "Sale ID and quantity must be NUMBERS.");
            }
        });

        // --- BACKUP DATABASE ---
        backupBtn.addActionListener(e -> {
            try {
                // NEW: builds a filename stamped with the exact date+time, so
                // every backup gets its own unique file - nothing ever
                // silently overwrites an older backup.
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"));
                String backupFileName = "pos_backup_" + timestamp + ".db";

                // Ask the admin where to save it - a USB drive, a folder
                // they sync to Google Drive, wherever they choose
                JFileChooser chooser = new JFileChooser();
                chooser.setSelectedFile(new java.io.File(backupFileName));
                chooser.setDialogTitle("Choose where to save the backup");
                int result = chooser.showSaveDialog(admin);

                if (result == JFileChooser.APPROVE_OPTION) {
                    java.io.File destination = chooser.getSelectedFile();
                    // Files.copy actually duplicates "pos.db" byte-for-byte
                    // into the chosen location - REPLACE_EXISTING means if
                    // that exact file somehow already exists, overwrite it
                    // rather than silently failing.
                    Files.copy(Paths.get("pos.db"), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    JOptionPane.showMessageDialog(admin, "Backup saved to:\n" + destination.getAbsolutePath());
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(admin, "Backup failed: " + ex.getMessage());
            }
        });

        // --- CHECK EXPIRY ---
        expiryBtn.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            String today = java.time.LocalDate.now().toString();
            // "warn soon" = anything expiring within the next 7 days
            String soonCutoff = java.time.LocalDate.now().plusDays(7).toString();

            sb.append("===== EXPIRED =====\n");
            boolean anyExpired = false;
            for (Product p : Database.getAllProducts()) {
                if (p.isExpired()) {
                    anyExpired = true;
                    sb.append("- ").append(p.name).append(" (expired ").append(p.expiryDate).append(")\n");
                }
            }
            if (!anyExpired) sb.append("None.\n");

            sb.append("\n===== EXPIRING WITHIN 7 DAYS =====\n");
            boolean anySoon = false;
            for (Product p : Database.getAllProducts()) {
                if (!p.expiryDate.isEmpty() && !p.isExpired()
                        && p.expiryDate.compareTo(today) >= 0
                        && p.expiryDate.compareTo(soonCutoff) <= 0) {
                    anySoon = true;
                    sb.append("- ").append(p.name).append(" (expires ").append(p.expiryDate).append(")\n");
                }
            }
            if (!anySoon) sb.append("None.\n");

            JTextArea area = new JTextArea(sb.toString());
            area.setEditable(false);
            area.setFont(new Font("Monospaced", Font.PLAIN, 13));
            JOptionPane.showMessageDialog(admin, area, "Expiry Check", JOptionPane.INFORMATION_MESSAGE);
        });


        // --- CHANGE PASSWORD (moved here from the old records window) ---
        changePassBtn.addActionListener(e -> {
            String oldP = JOptionPane.showInputDialog(admin, "Old Password:");
            String newP = JOptionPane.showInputDialog(admin, "New Password:");
            if (oldP != null && newP != null && !newP.trim().isEmpty() && Database.changePassword(oldP, newP)) {
                JOptionPane.showMessageDialog(admin, "Password changed!");
            } else {
                JOptionPane.showMessageDialog(admin, "Could not change password - old password wrong or new one blank.");
            }
        });

        admin.setVisible(true);
    }

// ORGANISING THE VIEW RECORDS TABLE
// ORGANISING THE VIEW RECORDS TABLE
static void showRecords() {
    JFrame f = new JFrame("Sales Records - Read Only");
    f.setSize(680, 420);

    // Each entry here is either ONE SALE or ONE "DAY CLOSED" marker.
    // We keep them in a small helper class so we can sort sales and
    // closures together by time, instead of showing two separate lists.
    class RecordRow {
        String sortKey;   // the raw date/time text used ONLY for sorting
        Object[] display; // what actually shows in the table's 4 columns
        boolean isClosure; // true = this row is a "DAY CLOSED" marker
        boolean isLateSale; // true = this sale happened AFTER today's close
        boolean isRefunded; // NEW: true = at least part of this sale was refunded
        String receipt;    // null for closure rows - they have no receipt
    }

    java.util.ArrayList<RecordRow> combined = new java.util.ArrayList<>();

    // NEW: find the exact moment today was closed (if it has been). We'll
    // compare every sale's timestamp against this to flag "after hours" sales.
    String todaysCloseTime = Database.getTodaysCloseTimestamp(); // null = not closed yet today

    // NEW: collect every sale_id that has AT LEAST ONE refund against it,
    // so we can mark that sale's whole row red in the table below.
    java.util.HashSet<Integer> refundedSaleIds = new java.util.HashSet<>();
    try (java.sql.Connection refundConn = Database.connect()) {
        java.sql.ResultSet refundRs = refundConn.createStatement()
                .executeQuery("SELECT DISTINCT sale_id FROM refunds");
        while (refundRs.next()) {
            refundedSaleIds.add(refundRs.getInt("sale_id"));
        }
    } catch (Exception ignore) {
        // No refunds table yet = no refund has ever been processed. Fine.
    }

    try (java.sql.Connection conn = Database.connect()) {

        // 1. Load every individual sale
        java.sql.ResultSet rs = conn.createStatement()
                .executeQuery("SELECT * FROM sales ORDER BY id DESC");
        while (rs.next()) {
            RecordRow r = new RecordRow();
            int thisSaleId = rs.getInt("id");
            r.sortKey = rs.getString("date");
            r.display = new Object[]{
                    thisSaleId,
                    rs.getString("date"),
                    String.format("%,.0f", rs.getDouble("total")) + " UGX",
                    rs.getString("cashier"),
                    rs.getString("payment_method") // NEW - 5th column
            };
            r.isClosure = false;
            r.isLateSale = (todaysCloseTime != null) && (r.sortKey.compareTo(todaysCloseTime) > 0);
            r.isRefunded = refundedSaleIds.contains(thisSaleId); // NEW
            r.receipt = rs.getString("receipt_text");
            combined.add(r);
        }

        // 2. Load every "day closed" event (from Database.closeDay()).
        // Wrapped in its own try/catch because day_closures won't exist
        // yet if CLOSE DAY has never been clicked - that's fine, not an error.
        try {
            java.sql.ResultSet drs = conn.createStatement()
                    .executeQuery("SELECT * FROM day_closures ORDER BY id DESC");
            while (drs.next()) {
                RecordRow r = new RecordRow();
                r.sortKey = drs.getString("closed_at");
                r.display = new Object[]{
                        "—", // closure rows have no sale ID, so just a dash
                        drs.getString("closed_at"),
                        String.format("%,.0f", drs.getDouble("total_sales")) + " UGX",
                        "DAY CLOSED (" + drs.getInt("transaction_count")
                                + " sales) - by " + drs.getString("closed_by"),
                        "" // NEW - 5th column, blank for closure rows
                };
                r.isClosure = true;
                r.receipt = null;
                combined.add(r);
            }
        } catch (Exception ignore) {
            // No day_closures table yet = no day has ever been closed. Fine.
        }

    } catch (Exception e) {
        System.out.println(e.getMessage());
    }

    // 3. Sort EVERYTHING (sales + closures) together, newest first, so a
    // closure marker lands in its correct chronological spot among sales
    combined.sort((a, b) -> b.sortKey.compareTo(a.sortKey));

    // Convert into the plain arrays JTable actually needs
    Object[][] data = new Object[combined.size()][];
    String[] receiptTexts = new String[combined.size()];
    boolean[] isClosureRow = new boolean[combined.size()];
    boolean[] isLateSaleRow = new boolean[combined.size()];
    boolean[] isRefundedRow = new boolean[combined.size()]; // NEW
    for (int i = 0; i < combined.size(); i++) {
        data[i] = combined.get(i).display;
        receiptTexts[i] = combined.get(i).receipt;
        isClosureRow[i] = combined.get(i).isClosure;
        isLateSaleRow[i] = combined.get(i).isLateSale;
        isRefundedRow[i] = combined.get(i).isRefunded; // NEW
    }

    String[] cols = {"ID", "Date & Time", "Total", "Cashier", "Payment"}; // NEW column

    JTable table = new JTable(data, cols) {
        public boolean isCellEditable(int r, int c) { return false; }
    };
    table.setRowHeight(24);
    table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
    table.getColumnModel().getColumn(0).setPreferredWidth(40);
    table.getColumnModel().getColumn(1).setPreferredWidth(200);
    table.getColumnModel().getColumn(2).setPreferredWidth(100);
    table.getColumnModel().getColumn(3).setPreferredWidth(140);
    table.getColumnModel().getColumn(4).setPreferredWidth(120); // NEW

    // NEW: paint "DAY CLOSED" rows in yellow + bold so they stand out
    // visually from ordinary sale rows, instead of looking identical
    javax.swing.table.DefaultTableCellRenderer highlightRenderer =
            new javax.swing.table.DefaultTableCellRenderer() {
                public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                               boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                    if (isRefundedRow[row]) {
                        c.setBackground(new Color(255, 180, 180)); // soft red - REFUNDED sale
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if (isClosureRow[row]) {
                        c.setBackground(new Color(255, 243, 176)); // soft yellow - DAY CLOSED marker
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if (isLateSaleRow[row]) {
                        c.setBackground(new Color(255, 210, 160)); // soft orange - sale AFTER close
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else {
                        c.setBackground(Color.WHITE);
                        c.setFont(c.getFont().deriveFont(Font.PLAIN));
                    }
                    return c;
                }
            };
    for (int col = 0; col < cols.length; col++) {
        table.getColumnModel().getColumn(col).setCellRenderer(highlightRenderer);
    }

    // Double-click shows a sale's receipt - but closure rows have none,
    // so we skip them instead of showing an empty/confusing popup
    table.addMouseListener(new java.awt.event.MouseAdapter() {
        public void mouseClicked(java.awt.event.MouseEvent evt) {
            if (evt.getClickCount() == 2) {
                int row = table.rowAtPoint(evt.getPoint());
                if (row < 0 || row >= data.length) return;

                if (isClosureRow[row]) {
                    // NEW: a DAY CLOSED row now shows every receipt from
                    // that same calendar day, instead of saying "no receipt"
                    String closedAtFull = (String) data[row][1]; // e.g. "2026-11-20 18:05:00"
                    String dateOnly = closedAtFull.split(" ")[0]; // just "2026-11-20"
                    String dayReceipts = Database.getReceiptsForDate(dateOnly);

                    JTextArea area = new JTextArea(dayReceipts);
                    area.setEditable(false);
                    area.setFont(new Font("Monospaced", Font.PLAIN, 12));
                    JScrollPane scrollPane = new JScrollPane(area);
                    scrollPane.setPreferredSize(new Dimension(500, 500));
                    JOptionPane.showMessageDialog(f, scrollPane,
                            "All Sales for " + dateOnly, JOptionPane.INFORMATION_MESSAGE);

                } else if (row < receiptTexts.length) {
                    String receipt = receiptTexts[row];
                    JOptionPane.showMessageDialog(f,
                            receipt != null ? receipt : "No receipt text saved for this sale.",
                            "Receipt - Sale #" + data[row][0],
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
    });

    f.add(new JScrollPane(table), BorderLayout.CENTER);

    JPanel bottomPanel = new JPanel(new BorderLayout());

    // Combines EVERY saved receipt into one big scrollable view
    JButton viewAllBtn = new JButton("VIEW ALL RECEIPTS");
    viewAllBtn.addActionListener(ev -> {
        StringBuilder allReceipts = new StringBuilder();
        // Loop BACKWARDS (table is newest-first) so receipts read in
        // natural chronological order: oldest sale first, newest last
        for (int i = receiptTexts.length - 1; i >= 0; i--) {
            if (receiptTexts[i] != null) { // skip DAY CLOSED rows - they have none
                allReceipts.append("Sale #").append(data[i][0]).append("\n");
                allReceipts.append(receiptTexts[i]).append("\n");
                allReceipts.append("========================================\n\n");
            }
        }
        if (allReceipts.length() == 0) {
            allReceipts.append("No receipts recorded yet.");
        }

        JTextArea area = new JTextArea(allReceipts.toString());
        area.setEditable(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setPreferredSize(new Dimension(500, 500));
        JOptionPane.showMessageDialog(f, scrollPane, "All Receipts", JOptionPane.INFORMATION_MESSAGE);
    });

    bottomPanel.add(viewAllBtn, BorderLayout.NORTH);
    bottomPanel.add(new JLabel(
            "  Yellow = Day Closed | Orange = Sale AFTER close | Red = Refunded. Double-click a row for one receipt, or VIEW ALL RECEIPTS for everything.",
            SwingConstants.LEFT), BorderLayout.SOUTH);
    f.add(bottomPanel, BorderLayout.SOUTH);
    f.setVisible(true);
}
}
