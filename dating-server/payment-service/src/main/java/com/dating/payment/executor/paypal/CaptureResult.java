package com.dating.payment.executor.paypal;

public class CaptureResult {
    private final boolean completed;
    private final String transactionId;
    private final String paypalOrderId;
    private final String errorMessage;

    public CaptureResult(boolean completed, String transactionId, String paypalOrderId) {
        this.completed = completed;
        this.transactionId = transactionId;
        this.paypalOrderId = paypalOrderId;
        this.errorMessage = null;
    }

    public CaptureResult(String errorMessage) {
        this.completed = false;
        this.transactionId = null;
        this.paypalOrderId = null;
        this.errorMessage = errorMessage;
    }

    public boolean isCompleted() { return completed; }
    public String getTransactionId() { return transactionId; }
    public String getPaypalOrderId() { return paypalOrderId; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isSuccess() { return errorMessage == null; }
}
