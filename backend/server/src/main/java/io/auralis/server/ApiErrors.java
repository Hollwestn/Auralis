package io.auralis.server;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Проверьте формат и допустимые значения запроса")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> upload() { return ResponseEntity.status(413).body(Map.of("error", "Максимальный размер файла — 300 МБ")); }
}
