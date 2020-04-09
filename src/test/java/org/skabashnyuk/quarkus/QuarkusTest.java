package org.skabashnyuk.quarkus;

import org.skabashnyuk.quarkus.test.testng.QuarkusTestNGListener;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@Listeners(QuarkusTestNGListener.class)
public class QuarkusTest {

  @Test
  public void testHelloEndpoint() {
    given().when().get("app").then().statusCode(200).body(is("hello"));
  }
}
