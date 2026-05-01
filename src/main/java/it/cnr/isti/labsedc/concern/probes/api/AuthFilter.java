package it.cnr.isti.labsedc.concern.probes.api;

import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;

public class AuthFilter {

    private final String adminToken;

    public AuthFilter(String adminToken) { this.adminToken = adminToken; }

    public void register(Javalin app) {
        if (adminToken == null || adminToken.isBlank()) {
			return;
		}
        app.before("/api/*", ctx -> {
            String auth = ctx.header("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")
                    || !adminToken.equals(auth.substring(7).trim())) {
				throw new UnauthorizedResponse("admin token required");
			}
        });
    }
}
