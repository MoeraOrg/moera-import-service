package org.moera.importer.admin;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdministrationController.class)
public class AdministrationExceptionHandler {

    @ExceptionHandler(AdministrationException.class)
    public ProblemDetail handle(AdministrationException exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    }

}
