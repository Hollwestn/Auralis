package io.auralis.server;

import org.springframework.http.HttpStatus;

final class ApiException extends RuntimeException {
    final HttpStatus status;
    ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "Объект не найден"); }
}
