public class Product {
    public int id;
    public String name;
    public double price;       // Price for ONE small piece, e.g. 1 biscuit = 500
    public double packPrice;   // Price for ONE pack, e.g. 1 pack = 5000
    public int stock;          // Total smallest pieces in stock (always counted in pieces, not packs)
    public int packSize;       // How many pieces make 1 pack. Bread = 1, Biscuits = 12
    public int lowStockLevel;  // When stock falls to/below this, we warn the cashier/admin
    public String unit;        // "biscuit", "piece", "sachet" - the small unit name
    public String packUnit;    // "pack", "box", "carton" - the bulk unit name
    public String expiryDate;  // NEW: "YYYY-MM-DD", or "" if this product doesn't expire

    public Product(int id, String name, double price, double packPrice, int stock,
                   int packSize, int lowStockLevel, String unit, String packUnit, String expiryDate) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.packPrice = packPrice;
        this.stock = stock;
        this.packSize = packSize;
        this.lowStockLevel = lowStockLevel;
        this.unit = unit;
        this.packUnit = packUnit;
        this.expiryDate = expiryDate; // NEW
    }
    public boolean isExpired() {
        if (expiryDate == null || expiryDate.isEmpty()) return false;
        return expiryDate.compareTo(java.time.LocalDate.now().toString()) < 0;
    }
    // NEW: true if this product has a real expiry date, isn't expired YET,
    // but will expire within the given number of days (e.g. 2)
    public boolean isExpiringWithin(int days) {
        if (expiryDate == null || expiryDate.isEmpty()) return false;
        if (isExpired()) return false; // already expired is a DIFFERENT warning
        String cutoff = java.time.LocalDate.now().plusDays(days).toString();
        return expiryDate.compareTo(cutoff) <= 0;
    }

    public boolean isOutOfStock() {
        return stock <= 0;
    }

    public boolean isLowStock() {
        return stock <= lowStockLevel;
    }
}