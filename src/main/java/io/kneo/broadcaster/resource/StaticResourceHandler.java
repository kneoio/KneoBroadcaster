package io.kneo.broadcaster.resource;

import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteBase;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
@RouteBase(path = "")  // Changed from "/" to "" to avoid double slash
public class StaticResourceHandler {

    @Route(path = "js/:file", methods = Route.HttpMethod.GET)  // Removed leading slash
    void serveJavaScript(RoutingContext rc) {
        String fileName = rc.pathParam("file");
        Path filePath = Paths.get("src/main/resources/META-INF/resources/js/" + fileName);

        try {
            String content = new String(Files.readAllBytes(filePath));
            rc.response()
                    .putHeader("Content-Type", "application/javascript")
                    .putHeader("Cache-Control", "public, max-age=3600")
                    .putHeader("Access-Control-Allow-Origin", "*")  // Added CORS header
                    .end(content);
        } catch (IOException e) {
            rc.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "text/plain")
                    .end("JavaScript file not found: " + fileName);
        }
    }
}