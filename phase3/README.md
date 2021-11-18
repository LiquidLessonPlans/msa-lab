# Phase 3: Circuit-Breaking

In this phase we will address the lack of fallback support in our microservice application. One of the primary benefits of Microservices is that they have high availability and resiliency. However, the current state of our application does not provide this. If our flashcard-service fails, then our quiz-service should still be able to gracefully inform  others instead of also failing. We will accomplish that with Netflix Hystrix, which is their solution for Circuit-Breaking. Hystrix will allow us to create fallbacks to provide a clear and graceful response when interservice communication fails for any reason.

Additionally, we can demonstrate the use of Ribbon, Netflix's client-side load balancer, with RestTemplate and then [OpenFeign](https://spring.io/projects/spring-cloud-openfeign).

The end result of this phase will be provided for comparison.

# Step 1: Demonstrate Ribbon

We'll start by creating a `/port` endpoint in our flashcard-service that will provide the port that it is running on. Then we will create a copy of our flashcard service and have them running on different ports. Our quiz-service will then explicitly leverage Ribbon to load balance across instances of that service using the `@RibbonClient` annotation.

Update the `FlashcardController` of the flashcard-service, according to the following snippet:

```java
@Autowired
Environment env;

@GetMapping("/port")
public String getPort() {
  String serverPort = env.getProperty("local.server.port");

  return "Hello, this came from port " + serverPort;
}
```

Note: The Environment interface should be imported from `org.springframework.core.env`.

Now we want to create a copy of our flashcard-service.

Right click the flashcard-service and copy-paste it in the IDE. Name the copy `flashcard-service2`.

Then update the `application.properties` file of `flashcard-service2` according to the following snippet:

```properties
server.port=8088
```

Note: Since the `spring.application.name` property is still just `flashcard`, Eureka will recognize this application as a second instance of the same service.

This does introduce consistency issues with the H2 databases, but that will be dealt with later in [Phase 5](../phase5).

At this point, we simply need to create an endpoint in our quiz-service to access the `/port` endpoint in our flashcard-services.

1. Right click on the quiz-service project and choose `Spring > Edit Starters`
    * Add the `Ribbon [Maintenance]` dependency.

2. Add the `@RibbonClient(name = "flashcard")` annotation to the `QuizServiceApplication` class, like the below snippet:

```java
@EnableDiscoveryClient
@RibbonClient(name = "flashcard")
@SpringBootApplication
public class QuizServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QuizServiceApplication.class, args);
  }
}
```

Note: The `@RibbonClient` annotation should be imported from `org.springframework.cloud.netflix.ribbon`

Now we'll just create an endpoint in the `QuizController` that will use our `RestTemplate` to load balance the flashcard-service.

Update the `QuizController` according to the following snippet:

```java
@GetMapping("/port")
public ResponseEntity<String> retrievePort() {
  String info = this.restTemplate.getForObject("http://flashcard/port", String.class);

  return ResponseEntity.ok(info);
}
```

Now when we send several GET requests to `http://localhost:8080/quiz/port`, we will see responses from both port `8088` and `8089` in a round-robin fashion.

Note: You might initially see an exception in the zuul-service such as:

```java
com.netflix.zuul.exception.ZuulException:
  at org.springframework.cloud.netflix.zuul.filters.post.SendErrorFilter.findZuulException(SendErrorFilter.java:118) ~[spring-cloud-netflix-zuul-2.2.3.RELEASE.jar:2.2.3.RELEASE]
  at org.springframework.cloud.netflix.zuul.filters.post.SendErrorFilter.run(SendErrorFilter.java:78) ~[spring-cloud-netflix-zuul-2.2.3.RELEASE.jar:2.2.3.RELEASE]
  at com.netflix.zuul.ZuulFilter.runFilter(ZuulFilter.java:117) [zuul-core-1.3.1.jar:1.3.1]
  at com.netflix.zuul.FilterProcessor.processZuulFilter(FilterProcessor.java:193) [zuul-core-1.3.1.jar:1.3.1]
  ...
```

This just means that the zuul-service is still processing the routes that it obtain from Eureka. Wait a few moments and it should be resolved.

## Step 2: Convert RestTemplate to Feign Client

Up until this point we have been using `RestTemplate` very well. However, Runtime Exceptions will occur if we ever have a typo in the url with `getForObject`, and the specificity of the `@LoadBalanced` annotation seems unnecessary.

We instead will be transitioning to use `Feign Clients` from OpenFeign. This will allow us to create interfaces to define all of the endpoints that are available to us, with type-safety. Additionally, Feign Clients will also automatically use Ribbon for us, so we will not need to keep using the `Ribbon [Maintenance]` dependency.

We'll first remove the Spring Starter Dependency and the Ribbon annotation on our `QuizServiceApplication` class.

Right click on the quiz-service project and choose `Spring > Edit Starters`
  * Remove the `Ribbon [Maintenance]` dependency
  * Add the `OpenFeign` dependency

```java
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class QuizServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QuizServiceApplication.class, args);
  }
}
```

Note: The `@EnableFeignClients` annotation should be imported from `org.springframework.cloud.openfeign`

In quiz-service, create a package called `com.revature.clients` with an interface called `FlashcardClient` according to the below snippet:

```java
@FeignClient(name = "flashcard")
public interface FlashcardClient {

  @GetMapping
  public List<Flashcard> findAll();

  @GetMapping("/port")
  public String retrievePort();
}
```

The `@FeignClient` annotation will handle all of the complex logic needed to communicate with the flashcard-service through Eureka. No implementation class needed, similar to Spring Data JPA.

Lastly, in our `QuizController`, we can remove the old RestTemplate and instead use our convenient Feign Client:

```java
@Autowired
private FlashcardClient flashcardClient;

@GetMapping("/port")
public ResponseEntity<String> retrievePort() {
  String info = flashcardClient.retrievePort();

  return ResponseEntity.ok(info);
}

@GetMapping("/cards")
public ResponseEntity<List<Flashcard>> getCards() {
  List<Flashcard> all = this.flashcardClient.findAll();

  if(all.isEmpty()) {
    return ResponseEntity.noContent().build();
  }

  return ResponseEntity.ok(all);
}
```

Our microservice application will operate in the same manner as it did with `RestTemplate` except now all of the load balancing logic is abstracted away and handled for us. We also have additional type-safety support through the use of Feign Clients.

## Step 3: Circuit-Breaking

At this point, it is becoming more important that we provide some fallbacks in case some of our services are offline. As it currently stands, if you were to send a request to `http://localhost:8080/quiz/cards` while all of the flashcard-services are down, you get an Internal Server Error in the quiz-service:

```java
com.netflix.client.ClientException: Load balancer does not have available server for client: flashcard
  at com.netflix.loadbalancer.LoadBalancerContext.getServerFromLoadBalancer(LoadBalancerContext.java:483) ~[ribbon-loadbalancer-2.3.0.jar:2.3.0]
  at com.netflix.loadbalancer.reactive.LoadBalancerCommand$1.call(LoadBalancerCommand.java:184) ~[ribbon-loadbalancer-2.3.0.jar:2.3.0]
  at com.netflix.loadbalancer.reactive.LoadBalancerCommand$1.call(LoadBalancerCommand.java:180) ~[ribbon-loadbalancer-2.3.0.jar:2.3.0]
  at rx.Observable.unsafeSubscribe(Observable.java:10327) ~[rxjava-1.3.8.jar:1.3.8]
```

This is a problem. If our flashcard-service is down, our quiz-service should still be able to function properly. We can accomplish this with the Circuit-Breaker design pattern using [Netflix Hystrix](https://spring.io/guides/gs/circuit-breaker/).

Hystrix gives us access to the `@HystrixCommand` annotation to provide a fallback method. If the first method throws an exception (such as the response from a `RestTemplate` or `FeignClient` not being successful), the application will instead invoke the given fallback method.

We'll use this anywhere in our microservice application where one service communicates with another. At the moment, that is only within the quiz-service.

We'll once again, Edit the Spring Starters for the quiz-service to add the `Hystrix [Maintenance]` dependency.

Then we'll add the `@EnableCircuitBreaker` annotation to the `QuizServiceApplication`:

```java
@EnableDiscoveryClient
@EnableFeignClients
@EnableCircuitBreaker
@SpringBootApplication
public class QuizServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QuizServiceApplication.class, args);
  }
}
```

Now all we have to do is to create fallback methods for each method that relies on interservice communication.

Update the `QuizController` according to the below snippet:

```java
@GetMapping("/port")
@HystrixCommand(fallbackMethod = "retrieveUnavailable")
public ResponseEntity<String> retrievePort() {
  String info = flashcardClient.retrievePort();

  return ResponseEntity.ok(info);
}

public ResponseEntity<String> retrieveUnavailable() {
  return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Flashcard Service is currently unavailable");
}

@GetMapping("/cards")
@HystrixCommand(fallbackMethod = "getCardsUnavailable")
public ResponseEntity<List<Flashcard>> getCards() {
  List<Flashcard> all = this.flashcardClient.findAll();

  if(all.isEmpty()) {
    return ResponseEntity.noContent().build();
  }

  return ResponseEntity.ok(all);
}

public ResponseEntity<List<Flashcard>> getCardsUnavailable() {
  return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
}
```

Note: `HttpStatus` is imported from `org.springframework.http`

Now our quiz-service fails gracefully for the endpoints that rely on the flashcard-service. We obtain 503 Service Unavailable responses instead of 500 Internal Server Error. This much more clearly communicates to the end user (or perhaps the frontend) what the current situation is.

## Summary

We have seen how to use Ribbon both explicitly as well as implicitly through OpenFeign. After that we introduced the Circuit-Breaker design pattern by leveraging Netflix Hystrix in order to fail gracefully in case interservice communication breaks down.

Next in [Phase 4](../phase4), we will look at centralizing our configuration that is currently spanning across multiple services. We are starting to find that moving between different services in order to change a configuration is becoming increasingly inconvenient. By storing all of our configuration in a GitHub repository, we can maintain a history of all changes to the confguration for every microservice.
