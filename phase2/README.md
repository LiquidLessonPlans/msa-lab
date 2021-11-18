# Phase 2: Manage our Services

In this phase we will address the growing maintenence of tracking/managing our services. In the previous phase we found that it will become increasingly difficult to maintain our application as it grows larger with its current architecture. To resolve this, we will create two additional services, and modify our original services to leverage Service Discovery and an API Gateway.

The end result of this phase will be provided for comparison.

## Step 1: Create a Zuul Service

It was a bit tedious before how we had to switch the port in use when we wanted to switch between our services. We can address this with an API Gateway. This will be an independent service that will receive all of our requests first, and then redirect them to the appropriate service. This will make it much more convenient for end users of our application.

Create a new Spring Boot Project named zuul-service with the Starter Dependencies listed below:

* Zuul [Maintenence]
* Spring Boot Actuator
* Spring Boot DevTools

Then add the `@EnableZuulProxy` annotatation to the `ZuulServiceApplication` class like below:

```java
@EnableZuulProxy
@SpringBootApplication
public class ZuulServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ZuulServiceApplication.class, args);
  }
}
```

Finally, add the following to the `application.properties` file of zuul-service:

```properties
server.port=8080
spring.application.name=gateway

zuul.routes.flashcard.url=http://localhost:8089/flashcard
zuul.routes.quiz.url=http://localhost:8090/quiz
```

You might notice that our zuul-service is referencing our flashcard and quiz services on ports 8089 and 8090 respectively. This is because we would prefer to have our zuul-service on port 8080, and so we will have to change where are other 2 services are.

## Step 2: Update Other Services

We must now update the `application.properties` files for both our flashcard-service and quiz-service to be on ports 8089 and 8090 respectively. Update them according to the below snippet:

flashcard-service:
```properties
server.port=8089
```

quiz-service:
```properties
server.port=8090
```

Additionally, we must update our RestTemplate to use our new zuul-service.

Update the `getCards` method in the `QuizController` according to the below snippet:

```java
  @GetMapping("/cards")
  public ResponseEntity<List<Flashcard>> getCards() {
    List<Flashcard> all = this.restTemplate.getForObject("http://localhost:8080/flashcard", List.class);

    if(all.isEmpty()) {
      return ResponseEntity.noContent().build();
    }

    return ResponseEntity.ok(all);
  }
```

Now we can send a GET request to `localhost:8080/quiz/cards` or `localhost:8080/flashcard` and they both provide the same response, as expected.

But we still have the issue of needing to update our zuul-service every time each of our services change location. Additionally, it's a bit inconvenient that our RestTemplate has to send a request first to the gateway, which will then be redirected back to the flashcard-service. It would be much more efficient if our services could dynamically discover each other and directly communicate as needed.

This is where Eureka comes in.

## Step 3: Create a Eureka Service

Create a new Spring Boot Project named discovery-service with the Starter Dependencies listed below:

* Eureka Server
* Spring Boot Actuator
* Spring Boot DevTools

Then add the `@EnableEurekaServer` annotatation to the `DiscoveryServiceApplication` class like below:

```java
@EnableEurekaServer
@SpringBootApplication
public class DiscoveryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(DiscoveryServiceApplication.class, args);
  }
}
```

Finally, add the following to the `application.properties` file of discovery-service:

```properties
server.port=8761

eureka.client.register-with-eureka=false
eureka.client.fetch-registry=false
```

Port 8761 is the default port for Eureka Servers. And since there can theoretically be multiple Eureka Servers, it can register itself with another instance of Eureka, but we aren't doing that here, so we set those to false.

## Step 4: Update Other Services (again)

Now that we have created a Eureka Server, we will need to go back to our other Services and update them to register themselves with Eureka. They can also discover the location of other services by fetching Eureka's registry, which we will do for quiz-service.

Perform the following for each of:

* quiz-service
* flashcard-service
* zuul-service

1. Right click on the project and choose `Spring > Edit Starters`. This will allow you to modify the Spring Starter Dependencies that are in use.
    * Add the `Eureka Discovery Client` dependency.

2. Add the `@EnableDiscoveryClient` annotation to the class that has the `@SpringBootApplication` annotation, such as the below snippet:

```java
@EnableDiscoveryClient
@SpringBootApplication
public class QuizServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QuizServiceApplication.class, args);
  }
}
```

3. Add the following to the end of the `application.properties` file:

```properties
# Configure Eureka Server url
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
```

At this point, we can remove the following configuration in the `application.properties` file of zuul-service, since Zuul will dynamically obtain the location of our services.

```properties
zuul.routes.flashcard.url=http://localhost:8089/flashcard
zuul.routes.quiz.url=http://localhost:8090/quiz
```

The only caveat is that Eureka will use the `spring.application.name` property to identify the services. This means that our urls will be of the form `localhost:8080/quiz-service/quiz`, which isn't ideal. To resolve this, we can modify our services' configuration and controllers to return to the url scheme we had before.

Update the `spring.application.name` properties in quiz-service and flashcard-service to the following:

quiz-service:
```properties
spring.application.name=quiz
```

flashcard-service:
```properties
spring.application.name=flashcard
```

We can also remove the `@RequestMapping` annotation at the top of our `QuizController` and `FlashcardController` classes since now Zuul will be handling the routing based on URI.

Finally, the last step would be to modify the url that we have in our RestTemplate in `QuizController` to dynamically discover the location of the flashcard-service. Change the `QuizController` according to the following snippet:

```java
  @Bean
  @LoadBalanced
  RestTemplate restTemplate() {
    return new RestTemplate();
  }

  @GetMapping("/cards")
  public ResponseEntity<List<Flashcard>> getCards() {
    List<Flashcard> all = this.restTemplate.getForObject("http://flashcard", List.class);

    if(all.isEmpty()) {
      return ResponseEntity.noContent().build();
    }

    return ResponseEntity.ok(all);
  }
```

Note that we must add the `@LoadBalanaced` annotation to the RestTemplate because it needs some configuration for how to choose a specific instance of a service if Eureka informs that there are multiple. The `@LoadBalanaced` annotation will use a client-side Load Balancer named [Ribbon](https://spring.io/guides/gs/client-side-load-balancing/) under the hood.

Finally we can now send requests to `localhost:8080/quiz/cards` and obtain the same response as sending it to `localhost:8080/flashcard`. We are also able to update the ports of each service dynamically without needing to modify the Controllers further.

## Summary

We have added in an API Gateway with Netflix Zuul and Service Discovery with Netflix Eureka to ease further expansion of our application. From here we will be able to horizontally scale each of our services independently without impacting the codebases. Additionally, adding extra services to our microservices application is as simple as registering that new service with Eureka with a one-line configuration.

Next in [Phase 3](../phase3), we will look at addressing a lack of fallbacks in case one of our services is unavailable. As of now, if flashcard-service is unavailable, then our quiz-service will throw an exception when a request is sent to `localhost:8080/quiz/cards`. We would prefer to allow our quiz-service to gracefully respond in the event of another service's failure.
