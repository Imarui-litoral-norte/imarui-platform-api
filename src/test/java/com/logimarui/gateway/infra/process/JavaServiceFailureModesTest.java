package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JavaServiceFailureModesTest {
    @TempDir Path directory;

    @Test @EnabledOnOs(OS.WINDOWS)
    void occupiedNativePortIsDetectedAndAllocatorChoosesAnother() throws Exception {
        try (var socket=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))) {
            var inspector=new WindowsPortInspector();
            assertEquals(ProcessHandle.current().pid(),inspector.findListeningPidByPort(socket.getLocalPort()).orElseThrow());
            var allocator=new ProcessPortAllocator(inspector);
            int allocated=allocator.allocateNearestAvailablePort(socket.getLocalPort());
            assertNotEquals(socket.getLocalPort(),allocated);
            assertTrue(allocator.isAvailable(allocated));
        }
    }

    @Test void missingSigningKeyIsRejectedBeforeAnyProcessLaunch() throws Exception {
        var configuration=new Properties();
        configuration.setProperty("DB_URL","jdbc:postgresql://localhost:5432/test");
        configuration.setProperty("DB_USERNAME","test");configuration.setProperty("DB_PASSWORD","test-only");
        configuration.setProperty("JWT_PRIVATE_KEY_FILE",directory.resolve("absent.pem").toString());
        Path file=directory.resolve("probe.properties");
        try(var writer=Files.newBufferedWriter(file)){configuration.store(writer,null);}
        var runner=new JavaServiceProcessRunner(null,null,new MockEnvironment().withProperty("SERVICE_IIAM_ENV_FILE",file.toString()));
        assertThrows(IllegalStateException.class,()->runner.isolatedEnvironment(Map.of()));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void jvmExitingBeforeJwksNeverProducesReadyRuntime() throws Exception {
        Path artifact=Files.writeString(directory.resolve("not-an-application.jar"),"invalid-jar-probe");
        Path key=Files.writeString(directory.resolve("exists.pem"),"not-used-by-invalid-jar");
        Properties child=new Properties();child.setProperty("DB_URL","jdbc:postgresql://127.0.0.1:1/probe");
        child.setProperty("DB_USERNAME","probe");child.setProperty("DB_PASSWORD","probe-only");
        child.setProperty("JWT_PRIVATE_KEY_FILE",key.toString());
        Path file=directory.resolve("probe.properties");try(var writer=Files.newBufferedWriter(file)){child.store(writer,null);}
        assertFailedProcess(directory,artifact,file);
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    @EnabledIfEnvironmentVariable(named="IIAM_LIVE_TEST_RUNTIME",matches=".+")
    void unavailablePostgresFailsStartupWithoutTouchingTheSharedServer() throws Exception {
        Path runtime=Path.of(System.getenv("IIAM_LIVE_TEST_RUNTIME"));
        // The private directory inherits its ACL; configuration is never copied into the system temp folder.
        Path probe=Files.createTempDirectory(runtime,"failure-probe-");
        Path file=probe.resolve("iiam.properties");
        try {
            Properties child=new Properties();
            try(var reader=Files.newBufferedReader(runtime.resolve("iiam.properties"))){child.load(reader);}
            child.setProperty("DB_URL","jdbc:postgresql://127.0.0.1:1/unavailable_probe?connectTimeout=1");
            child.setProperty("DB_USERNAME","unavailable_probe");child.setProperty("DB_PASSWORD","probe-only-not-a-real-password");
            child.setProperty("SPRING_FLYWAY_CONNECT_RETRIES","0");
            child.setProperty("SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT","1000");
            try(var writer=Files.newBufferedWriter(file)){child.store(writer,null);}
            Path build=runtime.resolve("build-v1");
            assertFailedProcess(build,build.resolve("target/imarui-iam-service-0.0.1-SNAPSHOT.jar"),file);
        } finally { Files.deleteIfExists(file);Files.deleteIfExists(probe); }
    }

    private void assertFailedProcess(Path workdir,Path artifact,Path file) throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var allocator=mock(ProcessPortAllocator.class);when(allocator.allocateNearestAvailablePort(port)).thenReturn(port);
        var terminator=spy(new ProcessTreeTerminator());
        var environment=new MockEnvironment().withProperty("SERVICE_IIAM_JAR",artifact.toAbsolutePath().toString())
                .withProperty("SERVICE_IIAM_ENV_FILE",file.toAbsolutePath().toString()).withProperty("SERVICE_IIAM_STARTUP_TIMEOUT_SECONDS","30");
        var service=new ManagedService("iiam","/api/iam",ServiceType.JAVA,workdir.toAbsolutePath().toString(),"java -jar",port,false);
        var runner=new JavaServiceProcessRunner(terminator,allocator,environment);
        var error=assertThrows(IllegalStateException.class,()->runner.start(service));
        assertEquals("IIAM startup failed; inspect its private runtime log",error.getMessage());
        assertNotNull(error.getCause());
        assertEquals("IIAM exited before readiness",error.getCause().getMessage(),"Probe must exit, not merely time out");
        var stopped=ArgumentCaptor.forClass(Long.class);verify(terminator).terminate(stopped.capture());
        assertFalse(ProcessHandle.of(stopped.getValue()).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(new WindowsPortInspector().findListeningPidByPort(port).isEmpty());
    }
}
