package com.logimarui.receivables.api.v1;

import com.logimarui.platform.web.exception.ApiErrorResponse;
import com.logimarui.receivables.core.ReceivableNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(basePackageClasses = ReceivablesController.class)
public class ReceivablesExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ReceivablesExceptionHandler.class);
    @ExceptionHandler({IllegalArgumentException.class, BindException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("INVALID_RECEIVABLES_REQUEST",
                "Filtros, datas ou identificação inválidos.", HttpStatus.BAD_REQUEST));
    }
    @ExceptionHandler(ReceivableNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> missing(ReceivableNotFoundException exception) {
        return ResponseEntity.status(404).body(ApiErrorResponse.of("RECEIVABLE_NOT_FOUND",
                exception.getMessage(), HttpStatus.NOT_FOUND));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> database(DataAccessException exception) {
        LOG.error("Receivables portfolio READ failed ({})", exception.getClass().getSimpleName());
        return ResponseEntity.status(503).body(ApiErrorResponse.of("RECEIVABLES_READ_UNAVAILABLE",
                "Não foi possível consultar a carteira. Tente atualizar novamente.", HttpStatus.SERVICE_UNAVAILABLE));
    }
}

