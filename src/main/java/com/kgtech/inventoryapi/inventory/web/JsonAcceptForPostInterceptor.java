package com.kgtech.inventoryapi.inventory.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

/** C3, U2: stub so JsonAcceptForPostInterceptorTest compiles; not registered and accepts every request. */
final class JsonAcceptForPostInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        return true;
    }
}
