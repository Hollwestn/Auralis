package io.auralis.server;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;

@Configuration
class ServerTransport {
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> windowsAsyncTransport(){
        // NIO2 uses Windows IOCP. Some packaged Windows environments cannot create the
        // Unix-domain wakeup pipe used by the default JDK NIO Selector implementation.
        return factory->{if(System.getProperty("os.name","").startsWith("Windows"))factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");};
    }
}
