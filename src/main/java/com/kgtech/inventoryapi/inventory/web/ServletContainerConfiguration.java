package com.kgtech.inventoryapi.inventory.web;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Host;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Container settings that keep errors outside Spring MVC on the text/plain contract (C1): Tomcat's error page is the
 * text valve, an encoded slash reaches the controller, and TRACE is handled by Spring MVC like any unsupported method.
 */
@Configuration(proxyBeanMethods = false)
class ServletContainerConfiguration {

    @Bean
    TomcatTextErrors tomcatTextErrors() {
        return new TomcatTextErrors();
    }

    /** Boot's dispatcherServlet bean (4.1.x) with TRACE routed to handler lookup; Boot backs off for this name. */
    @Bean(name = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
    DispatcherServlet dispatcherServlet(WebMvcProperties webMvc) {
        DispatcherServlet servlet = new TraceAsUnsupportedDispatcherServlet();
        servlet.setDispatchOptionsRequest(webMvc.isDispatchOptionsRequest());
        servlet.setPublishEvents(webMvc.isPublishRequestHandledEvents());
        servlet.setEnableLoggingRequestDetails(webMvc.isLogRequestDetails());
        return servlet;
    }

    /**
     * Runs after Boot's TomcatWebServerFactoryCustomizer (order 0), whose context customizer installs the stock
     * ErrorReportValve on the host, so this one can replace it.
     */
    static final class TomcatTextErrors
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addConnectorCustomizers(connector -> {
                connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue());
                connector.setAllowTrace(true);
            });
            factory.addContextCustomizers(context -> {
                Host host = (Host) context.getParent();
                Pipeline pipeline = host.getPipeline();
                for (Valve valve : pipeline.getValves()) {
                    if (valve instanceof ErrorReportValve) {
                        pipeline.removeValve(valve);
                    }
                }
                if (host instanceof StandardHost standardHost) {
                    // Otherwise StandardHost.startInternal adds the stock valve back.
                    standardHost.setErrorReportValveClass(TextErrorReportValve.class.getName());
                }
                pipeline.addValve(new TextErrorReportValve());
            });
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /** A TRACE goes through handler lookup like PUT (405 with Allow, or 404), never HttpServlet's echo. */
    static final class TraceAsUnsupportedDispatcherServlet extends DispatcherServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doTrace(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            processRequest(request, response);
        }
    }
}
