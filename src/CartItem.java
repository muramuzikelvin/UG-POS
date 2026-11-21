import java.util.ArrayList;


// Why a separate small class, instead of just reusing Product? Product describes
// a product’s general info — its price, its stock, its pack size. A CartItem describes
// something different: this specific line, in this specific sale — “2 packs, right now,
// at this price.” Keeping them separate means a cart item doesn’t accidentally carry around
// (or let you modify) the product’s actual database stock numbers while you’re just building up a pending sale.
// Holds ONE line of an in-progress sale - e.g. "2 packs of Biscuits".
// A full shopping cart is just a list of these, built up before checkout.
public class CartItem {
    public String productName;
    public int qty;
    public boolean isPack;
    public double unitPrice;   // price used for THIS line (piece or pack price)
    public int piecesNeeded;   // qty converted to pieces, for stock checking later

    public CartItem(String productName, int qty, boolean isPack, double unitPrice, int piecesNeeded) {
        this.productName = productName;
        this.qty = qty;
        this.isPack = isPack;
        this.unitPrice = unitPrice;
        this.piecesNeeded = piecesNeeded;
    }

    // How much this ONE line costs - used to build the running cart total
    public double getLineTotal() {
        return qty * unitPrice;
    }

    // A readable line for the receipt, e.g. "2 pack Biscuits - 10,000 UGX"
    public String describeLine() {
        return qty + " " + (isPack ? "pack" : "piece") + " " + productName
                + " - " + String.format("%,.0f", getLineTotal()) + " UGX";
    }

    // Adds up every line's total in a cart - the subtotal before any SMS fee
    public static double cartTotal(ArrayList<CartItem> cart) {
        double sum = 0;
        for (CartItem item : cart) {
            sum += item.getLineTotal();
        }
        return sum;
    }
}