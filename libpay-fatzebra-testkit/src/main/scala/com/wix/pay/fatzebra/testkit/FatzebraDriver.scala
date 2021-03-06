package com.wix.pay.fatzebra.testkit


import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import com.google.api.client.util.Base64
import com.wix.e2e.http.api.StubWebServer
import com.wix.e2e.http.client.extractors.HttpMessageExtractors._
import com.wix.e2e.http.server.WebServerFactory.aStubWebServer
import com.wix.pay.creditcard.CreditCard
import com.wix.pay.fatzebra.model.Conversions._
import com.wix.pay.fatzebra.model._
import com.wix.pay.fatzebra.{CaptureRequestParser, CreatePurchaseRequestParser, PurchaseResponseParser}
import com.wix.pay.model.CurrencyAmount


class FatzebraDriver(server: StubWebServer) {

  def this(port: Int) = this(aStubWebServer.onPort(port).build)

  def start(): Unit = server.start()

  def stop(): Unit = server.stop()

  def reset(): Unit = server.replaceWith()

  def requests: Seq[HttpRequest] = server.recordedRequests

  def lastRequest: HttpRequest = requests.last

  def aCreatePurchaseRequestFor(username: String,
                                password: String,
                                currencyAmount: CurrencyAmount,
                                reference: String,
                                customerIpAddress: String,
                                creditCard: CreditCard,
                                capture: Boolean): CreatePurchaseCtx = {
    new CreatePurchaseCtx(
      username = username,
      password = password,
      currencyAmount = currencyAmount,
      reference = reference,
      customerIpAddress = customerIpAddress,
      creditCard = creditCard,
      capture = capture)
  }

  def anyCreatePurchaseRequest = new {
    def getsAccepted(purchaseId: String = "somePurchaseId",
                     reference: String = "someReference",
                     currency: String = "USD"): Unit = {
      returns(
        statusCode = StatusCodes.Created,
        response = new Response[Purchase](Some(Purchase(
          authorization = Some("55355"),
          id = Some(purchaseId),
          amount = Some(1000),
          decimal_amount = Some(10.0),
          authorized = Some(true),
          message = Some("Approved"),
          reference = Some(reference),
          currency = Some(currency),
          response_code = Some("99"),
          captured = Some(false),
          cvv_match = Some("U")))))
    }

    def getsDeclined(purchaseId: String = "somePurchaseId",
                     reference: String = "someReference",
                     currency: String = "USD"): Unit = {
      returns(
        statusCode = StatusCodes.OK,
        response = new Response[Purchase](Some(Purchase(
          id = Some(purchaseId),
          amount = Some(1000),
          decimal_amount = Some(10.0),
          successful = Some(false),
          authorized = Some(false),
          message = Some("Declined"),
          reference = Some(reference),
          currency = Some(currency),
          response_code = Some("99"),
          captured = Some(false),
          cvv_match = Some("U")))))
    }

    def returns(statusCode: StatusCode, response: Response[Purchase]): Unit = {
      server.appendAll {
        case HttpRequest(
        HttpMethods.POST,
        Path("/purchases"),
        _,
        _,
        _) => HttpResponse(
          status = statusCode,
          entity = HttpEntity(ContentTypes.`application/json`, PurchaseResponseParser.stringify(response)))
      }
    }
  }

  def aCaptureRequestFor(username: String,
                         password: String,
                         purchaseId: String,
                         amount: Double): CaptureCtx = {
    new CaptureCtx(username = username, password = password, purchaseId = purchaseId, amount = amount)
  }

  abstract class Ctx(val resource: String, username: String, password: String) {
    def isStubbedRequestEntity(entity: HttpEntity, headers: Seq[HttpHeader]): Boolean = {
      isAuthorized(headers) && verifyContent(entity)
    }

    private def isAuthorized(headers: Seq[HttpHeader]): Boolean = {
      val expectedValue = s"Basic ${Base64.encodeBase64String(s"$username:$password".getBytes("UTF-8"))}"
      for (header <- headers) {
        if (header.name == "Authorization") {
          return header.value == expectedValue
        }
      }

      false
    }

    protected def verifyContent(entity: HttpEntity): Boolean

    def returns(statusCode: StatusCode, response: Response[Purchase]): Unit = {
      server.appendAll {
        case HttpRequest(
        HttpMethods.POST,
        Path(`resource`),
        headers,
        entity,
        _) if isStubbedRequestEntity(entity, headers) =>
          HttpResponse(
            status = statusCode,
            entity = HttpEntity(ContentTypes.`application/json`, PurchaseResponseParser.stringify(response)))
      }
    }

    def errors(statusCode: StatusCode, response: Response[Purchase]): Unit = {
      server.appendAll {
        case HttpRequest(
        HttpMethods.POST,
        Path(`resource`),
        headers,
        entity,
        _) if isStubbedRequestEntity(entity, headers) =>
          HttpResponse(
            status = statusCode,
            entity = HttpEntity(ContentTypes.`application/json`, PurchaseResponseParser.stringify(response)))
      }
    }

    def failsOnInvalidUsername(): Unit = {
      errors(
        statusCode = StatusCodes.Unauthorized,
        response = new Response[Purchase](errors = List("Incorrect Username or Token"))
      )
    }

    def failsOnInvalidUsernameWithTransactionId(id: String): Unit = {
      val res = PurchaseResponseParser.stringify(Response(response = Some(Purchase(id = Some(id))), errors = Some(List("Incorrect Username or Token"))))
      errors(
        statusCode = StatusCodes.Unauthorized,
        response = Response(response = Some(Purchase(id = Some(id))), errors = Some(List("Incorrect Username or Token"))))
    }
  }

  class CreatePurchaseCtx(username: String,
                          password: String,
                          currencyAmount: CurrencyAmount,
                          reference: String,
                          customerIpAddress: String,
                          creditCard: CreditCard,
                          capture: Boolean) extends Ctx("/purchases", username, password) {
    private val expectedCreatePurchaseRequest = CreatePurchaseRequest(
      card_holder = creditCard.holderName.get,
      card_number = creditCard.number,
      card_expiry = toFatzebraYearMonth(
        year = creditCard.expiration.year,
        month = creditCard.expiration.month),
      cvv = creditCard.csc.orNull,
      amount = toFatzebraAmount(currencyAmount.amount),
      reference = reference,
      customer_ip = customerIpAddress,
      currency = currencyAmount.currency,
      capture = capture)

    override def verifyContent(entity: HttpEntity): Boolean = {
      val createPurchaseRequest = CreatePurchaseRequestParser.parse(entity.extractAsString)
      createPurchaseRequest == expectedCreatePurchaseRequest
    }

    def returns(purchaseId: String): Unit = {
      returns(
        statusCode = StatusCodes.Created,
        response = new Response[Purchase](Some(Purchase(
          authorization = Some("55355"),
          id = Some(purchaseId),
          amount = Some(1000),
          decimal_amount = Some(10.0),
          authorized = Some(true),
          message = Some("Approved"),
          reference = Some(reference),
          currency = Some(currencyAmount.currency),
          response_code = Some("99"),
          captured = Some(false),
          cvv_match = Some("U")))))
    }

    def getsDeclined(purchaseId: String): Unit = {
      returns(
        statusCode = StatusCodes.OK,
        response = new Response[Purchase](Some(Purchase(
          id = Some(purchaseId),
          amount = Some(1000),
          decimal_amount = Some(10.0),
          successful = Some(false),
          authorized = Some(false),
          message = Some("Declined"),
          reference = Some(reference),
          currency = Some(currencyAmount.currency),
          response_code = Some("99"),
          captured = Some(false),
          cvv_match = Some("U")))))
    }
  }

  class CaptureCtx(username: String,
                   password: String,
                   purchaseId: String,
                   amount: Double) extends Ctx(s"/purchases/$purchaseId/capture", username, password) {
    private val expectedCaptureRequest = CaptureRequest(amount = toFatzebraAmount(amount))

    override def verifyContent(entity: HttpEntity): Boolean = {
      val captureRequest = CaptureRequestParser.parse(entity.extractAsString)
      captureRequest == expectedCaptureRequest
    }

    def succeeds(): Unit = {
      returns(
        statusCode = StatusCodes.OK,
        response = new Response[Purchase](Some(Purchase(
          id = Some(purchaseId),
          amount = Some(Conversions.toFatzebraAmount(amount)),
          decimal_amount = Some(amount),
          successful = Some(true),
          authorized = Some(false),
          message = Some("someMessage"),
          reference = Some("someReference"),
          currency = Some("USD"),
          response_code = Some("0"),
          captured = Some(true),
          captured_amount = Some(Conversions.toFatzebraAmount(amount)),
          cvv_match = Some("U")))))
    }
  }

}
