package io.scalecube.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.rsocket.exceptions.RejectedSetupException;
import io.scalecube.services.Microservices.Context;
import io.scalecube.services.auth.Authenticator;
import io.scalecube.services.auth.PrincipalMapper;
import io.scalecube.services.discovery.ScalecubeServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscovery;
import io.scalecube.services.exceptions.UnauthorizedException;
import io.scalecube.services.sut.security.AnotherSecuredService;
import io.scalecube.services.sut.security.AnotherSecuredServiceImpl;
import io.scalecube.services.sut.security.PartiallySecuredService;
import io.scalecube.services.sut.security.PartiallySecuredServiceImpl;
import io.scalecube.services.sut.security.SecuredService;
import io.scalecube.services.sut.security.SecuredServiceImpl;
import io.scalecube.services.sut.security.UserProfile;
import io.scalecube.services.transport.rsocket.RSocketServiceTransport;
import io.scalecube.transport.netty.websocket.WebsocketTransportFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

final class ServiceAuthRemoteTest extends BaseTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final Map<String, String> CREDENTIALS = new HashMap<>();

  static {
    CREDENTIALS.put("username", "Alice");
    CREDENTIALS.put("password", "qwerty");
  }

  private static final Map<String, String> INVALID_CREDENTIALS = new HashMap<>();

  static {
    INVALID_CREDENTIALS.put("username", "Eve");
    INVALID_CREDENTIALS.put("password", "admin123");
  }

  private static final Authenticator<Map<String, String>> authenticator =
      headers -> {
        String username = headers.get("username");
        String password = headers.get("password");

        if ("Alice".equals(username) && "qwerty".equals(password)) {
          HashMap<String, String> authData = new HashMap<>();
          authData.put("name", "Alice");
          authData.put("role", "ADMIN");
          return Mono.just(authData);
        }

        return Mono.error(
            new UnauthorizedException("Authentication failed (username or password incorrect)"));
      };

  private static Microservices service;
  private static Microservices serviceWithoutAuthenticator;
  private static Microservices partiallySecuredService;
  public static PrincipalMapper<Map<String, String>, UserProfile> principalMapper;

  @BeforeAll
  static void beforeAll() {
    StepVerifier.setDefaultTimeout(TIMEOUT);

    principalMapper = authData -> new UserProfile(authData.get("name"), authData.get("role"));

    service =
        Microservices.start(
            new Context()
                .discovery(
                    serviceEndpoint ->
                        new ScalecubeServiceDiscovery()
                            .transport(cfg -> cfg.transportFactory(new WebsocketTransportFactory()))
                            .options(opts -> opts.metadata(serviceEndpoint)))
                .transport(() -> new RSocketServiceTransport().authenticator(authenticator))
                .services(
                    ServiceInfo.fromServiceInstance(new SecuredServiceImpl())
                        .principalMapper(principalMapper)
                        .build()));

    serviceWithoutAuthenticator =
        Microservices.start(
            new Context()
                .discovery(
                    serviceEndpoint ->
                        new ScalecubeServiceDiscovery()
                            .transport(cfg -> cfg.transportFactory(new WebsocketTransportFactory()))
                            .options(opts -> opts.metadata(serviceEndpoint)))
                .transport(RSocketServiceTransport::new)
                .services(
                    ServiceInfo.fromServiceInstance(new AnotherSecuredServiceImpl())
                        .principalMapper(principalMapper)
                        .build()));

    partiallySecuredService =
        Microservices.start(
            new Context()
                .discovery(
                    serviceEndpoint ->
                        new ScalecubeServiceDiscovery()
                            .transport(cfg -> cfg.transportFactory(new WebsocketTransportFactory()))
                            .options(opts -> opts.metadata(serviceEndpoint)))
                .transport(() -> new RSocketServiceTransport().authenticator(authenticator))
                .services(
                    ServiceInfo.fromServiceInstance(new PartiallySecuredServiceImpl())
                        .principalMapper(principalMapper)
                        .build()));
  }

  @AfterAll
  static void afterAll() {
    if (service != null) {
      service.close();
    }

    if (serviceWithoutAuthenticator != null) {
      serviceWithoutAuthenticator.close();
    }

    if (partiallySecuredService != null) {
      partiallySecuredService.close();
    }
  }

  @Test
  @DisplayName("Successful authentication")
  void successfulAuthentication() {
    try (Microservices caller = newCaller()) {
      SecuredService securedService = caller.call().api(SecuredService.class);

      StepVerifier.create(securedService.helloWithRequest("Bob"))
          .assertNext(response -> assertEquals("Hello, Bob", response))
          .verifyComplete();

      StepVerifier.create(securedService.helloWithPrincipal())
          .assertNext(response -> assertEquals("Hello, Alice", response))
          .verifyComplete();

      StepVerifier.create(securedService.helloWithRequestAndPrincipal("Bob"))
          .assertNext(response -> assertEquals("Hello, Bob and Alice", response))
          .verifyComplete();
    }
  }

  @Test
  @DisplayName("Authentication failed if authenticator not provided")
  void failedAuthenticationWhenAuthenticatorNotProvided() {
    try (Microservices caller = newCaller()) {
      AnotherSecuredService securedService = caller.call().api(AnotherSecuredService.class);

      Consumer<Throwable> verifyError =
          th -> {
            assertEquals(UnauthorizedException.class, th.getClass());
            assertEquals("Authentication failed", th.getMessage());
          };

      StepVerifier.create(securedService.helloWithRequest("Bob"))
          .expectErrorSatisfies(verifyError)
          .verify();

      StepVerifier.create(securedService.helloWithPrincipal())
          .expectErrorSatisfies(verifyError)
          .verify();

      StepVerifier.create(securedService.helloWithRequestAndPrincipal("Bob"))
          .expectErrorSatisfies(verifyError)
          .verify();
    }
  }

  @Test
  @DisplayName("Authentication failed with empty credentials")
  void failedAuthenticationWithEmptyCredentials() {
    try (Microservices caller = newEmptyCredentialsCaller()) {
      SecuredService securedService = caller.call().api(SecuredService.class);

      Consumer<Throwable> verifyError =
          th -> {
            assertEquals(UnauthorizedException.class, th.getClass());
            assertEquals("Authentication failed", th.getMessage());
          };

      StepVerifier.create(securedService.helloWithRequest("Bob"))
          .expectErrorSatisfies(verifyError)
          .verify();

      StepVerifier.create(securedService.helloWithPrincipal())
          .expectErrorSatisfies(verifyError)
          .verify();

      StepVerifier.create(securedService.helloWithRequestAndPrincipal("Bob"))
          .expectErrorSatisfies(verifyError)
          .verify();
    }
  }

  @Test
  @DisplayName("Authentication failed with invalid credentials")
  void failedAuthenticationWithInvalidCredentials() {
    try (Microservices caller = newInvalidCredentialsCaller()) {
      SecuredService securedService = caller.call().api(SecuredService.class);

      Consumer<Throwable> verifyError =
          th -> {
            assertEquals(RejectedSetupException.class, th.getClass());
            assertEquals("Authentication failed (username or password incorrect)", th.getMessage());
          };

      StepVerifier.create(securedService.helloWithRequest("Bob"))
          .expectErrorSatisfies(verifyError)
          .verify();

      StepVerifier.create(securedService.helloWithPrincipal())
          .expectErrorSatisfies(verifyError)
          .verify();

      StepVerifier.create(securedService.helloWithRequestAndPrincipal("Bob"))
          .expectErrorSatisfies(verifyError)
          .verify();
    }
  }

  @Test
  @DisplayName("Successful authentication of partially secured service")
  void successfulAuthenticationOnPartiallySecuredService() {
    try (Microservices caller = newCaller()) {
      StepVerifier.create(caller.call().api(PartiallySecuredService.class).securedMethod("Alice"))
          .assertNext(response -> assertEquals("Hello, Alice", response))
          .verifyComplete();
      StepVerifier.create(caller.call().api(PartiallySecuredService.class).publicMethod("Alice"))
          .assertNext(response -> assertEquals("Hello, Alice", response))
          .verifyComplete();
    }
  }

  @Test
  @DisplayName("Successful call public method of partially secured service without authentication")
  void successfulCallOfPublicMethodWithoutAuthentication() {
    try (Microservices caller = newCaller()) {
      StepVerifier.create(caller.call().api(PartiallySecuredService.class).publicMethod("Alice"))
          .assertNext(response -> assertEquals("Hello, Alice", response))
          .verifyComplete();

      Consumer<Throwable> verifyError =
          th -> {
            assertEquals(UnauthorizedException.class, th.getClass());
            assertEquals("Authentication failed", th.getMessage());
          };

      StepVerifier.create(caller.call().api(PartiallySecuredService.class).securedMethod("Alice"))
          .verifyErrorSatisfies(verifyError);
    }
  }

  private static Microservices newCaller() {
    return Microservices.start(
        new Context()
            .discovery(ServiceAuthRemoteTest::serviceDiscovery)
            .transport(
                () ->
                    new RSocketServiceTransport()
                        .credentialsSupplier(ServiceAuthRemoteTest::credentialsSupplier)));
  }

  private static Microservices newEmptyCredentialsCaller() {
    return Microservices.start(
        new Context()
            .discovery(ServiceAuthRemoteTest::serviceDiscovery)
            .transport(RSocketServiceTransport::new));
  }

  private static Microservices newInvalidCredentialsCaller() {
    return Microservices.start(
        new Context()
            .discovery(ServiceAuthRemoteTest::serviceDiscovery)
            .transport(
                () ->
                    new RSocketServiceTransport()
                        .credentialsSupplier(ServiceAuthRemoteTest::invalidCredentialsSupplier)));
  }

  private static Mono<Map<String, String>> credentialsSupplier(ServiceReference serviceReference) {
    switch (serviceReference.namespace()) {
      case "anotherSecured":
      case "secured":
        return Mono.just(CREDENTIALS);
    }

    switch (serviceReference.qualifier()) {
      case "partiallySecured/publicMethod":
        return Mono.empty();
      case "partiallySecured/securedMethod":
        return Mono.just(CREDENTIALS);
    }

    return Mono.just(Collections.emptyMap());
  }

  private static Mono<Map<String, String>> invalidCredentialsSupplier(
      ServiceReference serviceReference) {
    return Mono.just(INVALID_CREDENTIALS);
  }

  private static ServiceDiscovery serviceDiscovery(ServiceEndpoint endpoint) {
    return new ScalecubeServiceDiscovery()
        .transport(cfg -> cfg.transportFactory(new WebsocketTransportFactory()))
        .options(opts -> opts.metadata(endpoint))
        .membership(
            opts ->
                opts.seedMembers(
                    service.discoveryAddress().toString(),
                    serviceWithoutAuthenticator.discoveryAddress().toString(),
                    partiallySecuredService.discoveryAddress().toString()));
  }
}
