/*
 * Drives ServerManagementController through a standalone MockMvc (no full Spring
 * context, no docker daemon, no real fork) over a faked registry: every endpoint's
 * happy path plus the failure-kind -> status mapping (missing type -> 400, unknown
 * id -> 404, no daemon -> 503) so the API never leaks a stack trace. Also pins the
 * live-port branch that reads the real web server port under a random test port.
 */
package org.jdesktop.lg3d.webserver.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.jdesktop.lg3d.webserver.deploy.CommandResult;
import org.jdesktop.lg3d.webserver.deploy.DeployFixtures;
import org.jdesktop.lg3d.webserver.deploy.DeploymentRegistry;
import org.jdesktop.lg3d.webserver.deploy.DeploymentRequest;
import org.jdesktop.lg3d.webserver.deploy.DeploymentType;
import org.jdesktop.lg3d.webserver.deploy.FakeChildProcessStarter;
import org.jdesktop.lg3d.webserver.deploy.FakeDockerCli;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServerManagementControllerTest {

    @TempDir
    Path tempDir;

    private FakeDockerCli docker;
    private WebServerProperties properties;
    private DeploymentRegistry registry;
    private ServerManagementController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        docker = new FakeDockerCli();
        properties = DeployFixtures.properties();
        registry = DeployFixtures.registry(
                tempDir, properties, docker, new FakeChildProcessStarter());
        controller = new ServerManagementController(registry, properties, mock(ApplicationContext.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DeploymentRequest dockerRequest(String name, String image) {
        return new DeploymentRequest(name, DeploymentType.DOCKER, image, null, null, null, null);
    }

    // ---- server endpoints -------------------------------------------------

    @Test
    void healthReportsUpWithCounts() throws Exception {
        mockMvc.perform(get("/api/server/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dockerAvailable").value(true))
                .andExpect(jsonPath("$.jarAvailable").value(true))
                .andExpect(jsonPath("$.deployments").value(0))
                .andExpect(jsonPath("$.running").value(0));
    }

    @Test
    void healthCountsDeployments() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        mockMvc.perform(get("/api/server/health"))
                .andExpect(jsonPath("$.deployments").value(1))
                .andExpect(jsonPath("$.running").value(1));
    }

    @Test
    void infoReportsVersionAndJava() throws Exception {
        mockMvc.perform(get("/api/server/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("dev"))
                .andExpect(jsonPath("$.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.metrics.threadCount").isNumber());
    }

    @Test
    void metricsReportsHeapAndProcessors() throws Exception {
        mockMvc.perform(get("/api/server/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heapUsedBytes").isNumber())
                .andExpect(jsonPath("$.availableProcessors").isNumber())
                .andExpect(jsonPath("$.uptimeMillis").isNumber());
    }

    @Test
    void infoReadsLivePortFromWebServerContext() {
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        WebServer server = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(8123);
        assertThat(new ServerManagementController(registry, properties, context).info().port())
                .isEqualTo(8123);
    }

    @Test
    void infoPortIsZeroWithoutWebServerContext() {
        assertThat(controller.info().port()).isZero();
    }

    // ---- application CRUD -------------------------------------------------

    @Test
    void appsStartsEmpty() throws Exception {
        mockMvc.perform(get("/api/apps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deployCreatesAppWith201() throws Exception {
        mockMvc.perform(post("/api/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shop\",\"type\":\"DOCKER\",\"image\":\"shop:1.0\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("shop"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.type").value("DOCKER"))
                .andExpect(jsonPath("$.url").value("http://localhost:9001/shop"));
    }

    @Test
    void deployWithoutTypeIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"shop:1.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getAppByIdReturnsIt() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        mockMvc.perform(get("/api/apps/shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("shop"))
                .andExpect(jsonPath("$.name").value("Shop"));
    }

    @Test
    void unknownAppIsNotFound() throws Exception {
        mockMvc.perform(get("/api/apps/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void logsReturnsTailedOutput() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        docker.on("logs", new CommandResult(0, "hello world", ""));
        mockMvc.perform(get("/api/apps/shop/logs").param("tail", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("shop"))
                .andExpect(jsonPath("$.logs").value("hello world"));
    }

    @Test
    void stopThenStartTransitionsState() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        mockMvc.perform(post("/api/apps/shop/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
        mockMvc.perform(post("/api/apps/shop/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    void undeployReturns204AndForgets() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        mockMvc.perform(delete("/api/apps/shop"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/apps"))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void startUnknownIsNotFound() throws Exception {
        mockMvc.perform(post("/api/apps/ghost/start"))
                .andExpect(status().isNotFound());
    }

    // ---- degradation ------------------------------------------------------

    @Test
    void deployWithNoDaemonIsServiceUnavailable() throws Exception {
        docker.available(false);
        mockMvc.perform(post("/api/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shop\",\"type\":\"DOCKER\",\"image\":\"shop:1.0\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("not available")));
    }

    @Test
    void appsListsDeploymentsWithPublicUrl() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        mockMvc.perform(get("/api/apps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value("shop"))
                .andExpect(jsonPath("$[0].url").value("http://localhost:9001/shop"));
    }

    @Test
    void healthCountsAStoppedDeploymentAsNotRunning() throws Exception {
        registry.deploy(dockerRequest("Shop", "shop:1.0"));
        registry.stop("shop");
        mockMvc.perform(get("/api/server/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployments").value(1))
                .andExpect(jsonPath("$.running").value(0));
    }

    @Test
    void infoPortIsZeroWhenTheWebServerIsNull() {
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        when(context.getWebServer()).thenReturn(null);
        assertThat(new ServerManagementController(registry, properties, context).info().port())
                .isZero();
    }

    @Test
    void versionOrDefaultKeepsARealVersionAndFallsBackToDev() {
        assertThat(ServerManagementController.versionOrDefault("1.69.0")).isEqualTo("1.69.0");
        assertThat(ServerManagementController.versionOrDefault(null)).isEqualTo("dev");
    }
}
