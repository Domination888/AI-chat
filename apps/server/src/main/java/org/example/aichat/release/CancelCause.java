package org.example.aichat.release;
/** Fixed attribution only; never stores free-form error or user text. */
public enum CancelCause {
 USER_STOP("user_interrupted"), ACCOUNT_SUSPENDED("account_suspended"), ACCOUNT_DELETED("account_deleted"), CLIENT_DISCONNECTED("client_disconnected"), REQUEST_TIMEOUT("request_timeout"), APPLICATION_SHUTDOWN("application_shutdown"), OTHER_OPERATION("operation_interrupted");
 private final String category;
 CancelCause(String category){this.category=category;}
 public String category(){return category;}
}
