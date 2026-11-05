package uz.uzex.agent.web;

import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.uzex.agent.identity.AccountException;

/** Sends account refusals as a code, which the page turns into text in the reader's language. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<Map<String, String>> accountRefused(AccountException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID_USERNAME, WEAK_PASSWORD -> HttpStatus.BAD_REQUEST;
            case USERNAME_TAKEN -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.reason().name().toLowerCase(Locale.ROOT)));
    }
}
