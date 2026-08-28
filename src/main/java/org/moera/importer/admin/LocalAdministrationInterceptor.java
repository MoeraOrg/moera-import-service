package org.moera.importer.admin;

import java.net.InetAddress;
import java.net.UnknownHostException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LocalAdministrationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                return true;
            }
        } catch (UnknownHostException e) {
            // A servlet peer address is expected to be numeric. Treat malformed values as non-local.
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return false;
    }

}
