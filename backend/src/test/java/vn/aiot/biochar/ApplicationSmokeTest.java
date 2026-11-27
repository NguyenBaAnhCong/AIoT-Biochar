package vn.aiot.biochar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

    @Autowired
    private WebServerApplicationContext applicationContext;

    @Test
    void servesStaticFrontendAndProtectsAuthenticatedEndpoints() throws Exception {
        int port = applicationContext.getWebServer().getPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> home = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("Edge-AIoT Biochar"));

        HttpResponse<String> me = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode());
        assertTrue(me.body().contains("UNAUTHORIZED"));
    }
}
