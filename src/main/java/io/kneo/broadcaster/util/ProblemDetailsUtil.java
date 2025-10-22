package io.kneo.broadcaster.util;

import io.kneo.broadcaster.server.EnvConst;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.List;
import java.util.Map;

public class ProblemDetailsUtil {
    public static void respondValidationError(RoutingContext rc, String detail, Map<String, List<String>> fieldErrors) {
        JsonObject problem = new JsonObject()
                .put("type", EnvConst.VALIDATION_ERROR_PAGE)
                .put("title", "Constraint Violation")
                .put("status", 400)
                .put("detail", detail)
                .put("instance", rc.request().path())
                .put("errors", fieldErrors);
        rc.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/problem+json")
                .end(problem.encode());
    }
}
