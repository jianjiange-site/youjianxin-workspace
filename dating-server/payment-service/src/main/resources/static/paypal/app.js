window.paypal.Buttons({

  async createOrder() {
    try {
      const response = await fetch("/v1/payments/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          product_id: "premium_monthly",
          payment_method: "PAYPAL",
          currency: "USD",
          platform: "web",
        }),
      });

      const orderData = await response.json();

      if (orderData.extOrderId) {
        // extOrderId = PayPal order ID (protobuf JSON → camelCase)
        return orderData.extOrderId;
      }

      throw new Error(orderData?.base?.message || "Failed to create order");
    } catch (error) {
      console.error(error);
      showResult("Could not initiate PayPal Checkout...<br>" + error, "error");
    }
  },

  async onApprove(data) {
    try {
      // data.orderID = PayPal order ID (same as extOrderId returned above)
      // Use unified verify endpoint with ext_order_id + payment_method
      const response = await fetch("/v1/payments/verify", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ext_order_id: data.orderID,
          payment_method: "PAYPAL",
          order_id: "",
          receipt_data: "",
        }),
      });

      const verifyData = await response.json();

      if (verifyData?.base?.code === 0) {
        showResult(
          "Payment successful!<br>Your order has been verified.",
          "success"
        );
        console.log("Verify result:", verifyData);
      } else {
        throw new Error(verifyData?.base?.message || "Verification failed");
      }
    } catch (error) {
      console.error(error);
      showResult("Sorry, your transaction could not be processed.<br>" + error, "error");
    }
  },

}).render("#paypal-button-container");

function showResult(message, type) {
  const container = document.querySelector("#result-message");
  container.innerHTML = message;
  container.className = type;
}
