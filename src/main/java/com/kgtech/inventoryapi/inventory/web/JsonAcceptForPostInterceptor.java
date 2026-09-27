package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * C3, U2, Y1: a POST whose Accept gives application/json q=0 is refused. Spring's produces condition matches ranges
 * without reading q, so this runs after handler lookup and before argument resolution, and throws the same
 * HttpMediaTypeNotAcceptableException the lookup throws (400 "Invalid request" on POST).
 *
 * <p>RFC 9110 §12.4.2: "A value of 0 means "not acceptable"." §12.5.1: "Media ranges can be overridden by more
 * specific media ranges or specific media types. If more than one media range applies to a given type, the most
 * specific reference has precedence." Ranges are matched to application/json with isCompatibleWith, as handler
 * lookup does, so a suffix range such as application/*+json counts. Among the matching ranges the most specific
 * (concrete type, then a wildcard subtype, then *&#47;*; parameters other than q are not ranked) decides; when
 * several share that rank, the highest q among them counts. GET is never checked (the filter already ignores Accept).
 */
@Component
final class JsonAcceptForPostInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(BASE_PATH, BASE_PATH + "/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws HttpMediaTypeNotAcceptableException {
        if (HttpMethod.POST.matches(request.getMethod()) && refusesJson(request)) {
            throw new HttpMediaTypeNotAcceptableException(List.of(APPLICATION_JSON));
        }
        return true;
    }

    /** No Accept header, or no range compatible with JSON, is left to handler lookup and accepted here. */
    private static boolean refusesJson(HttpServletRequest request) {
        List<MediaType> json = MediaType.parseMediaTypes(Collections.list(request.getHeaders(ACCEPT))).stream()
                .filter(range -> range.isCompatibleWith(APPLICATION_JSON))
                .toList();
        int mostSpecific = json.stream().mapToInt(JsonAcceptForPostInterceptor::specificity).max().orElse(0);
        return json.stream()
                .filter(range -> specificity(range) == mostSpecific)
                .mapToDouble(MediaType::getQualityValue)
                .max()
                .orElse(1) == 0;
    }

    /** 0 for *&#47;*, 1 for a wildcard subtype, 2 for a concrete type. */
    private static int specificity(MediaType range) {
        if (range.isWildcardType()) {
            return 0;
        }
        return range.isWildcardSubtype() ? 1 : 2;
    }
}
