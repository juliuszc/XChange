package org.knowm.xchange.bitmex.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.knowm.xchange.bitmex.BitmexAuthenticated;
import org.knowm.xchange.bitmex.BitmexExchange;
import org.knowm.xchange.bitmex.RateLimitUpdateListener;
import org.knowm.xchange.exceptions.*;
import org.knowm.xchange.service.BaseExchangeService;
import org.knowm.xchange.service.BaseService;
import si.mazi.rescu.HttpResponseAware;
import si.mazi.rescu.ParamsDigest;
import si.mazi.rescu.RestProxyFactory;

@SuppressWarnings({"WeakerAccess", "unused"})
public class BitmexBaseService extends BaseExchangeService<BitmexExchange> implements BaseService {

  protected final BitmexAuthenticated bitmex;
  protected final ParamsDigest signatureCreator;
  protected static Integer rateLimit;
  protected static Integer rateLimitRemaining;
  protected static Long rateLimitReset;

  /**
   * Constructor
   *
   * @param exchange
   */
  public BitmexBaseService(BitmexExchange exchange) {

    super(exchange);
    bitmex =
        RestProxyFactory.createProxy(
            BitmexAuthenticated.class,
            exchange.getExchangeSpecification().getSslUri(),
            getClientConfig());
    signatureCreator =
        BitmexDigest.createInstance(exchange.getExchangeSpecification().getSecretKey());
  }

  protected ExchangeException handleError(Exception exception) {
    if (exception != null && exception.getMessage() != null) {
      if (exception.getMessage().contains("Insufficient")) {
        return new FundsExceededException(exception);
      } else if (exception.getMessage().contains("Rate limit exceeded")) {
        return new RateLimitExceededException(exception);
      } else if (exception.getMessage().contains("Internal server error")) {
        return new InternalServerException(exception);
      } else if (exception.getMessage().contains("The system is currently overloaded")) {
        return new SystemOverloadException(exception);
      } else {
        return new ExchangeException(exception.getMessage(), exception);
      }
    }
    return new ExchangeException(exception);
  }

  protected <T extends HttpResponseAware> T updateRateLimit(Supplier<T> httpResponseAwareSupplier) {
    if (rateLimitReset != null) {
      long waitMillis = rateLimitReset * 1000 - System.currentTimeMillis();
      if (rateLimitRemaining <= 0 && waitMillis > 0) {
        throw new ExchangeException(
            "The request is not executed due to rate limit, please wait for "
                + (waitMillis / 1000)
                + " seconds, limit:"
                + rateLimit
                + ", reset: "
                + new Date(rateLimitReset * 1000));
      }
    }
    T result;
    try {
      result = httpResponseAwareSupplier.get();
    } catch (Exception e) {
      throw handleError(e);
    }
    Map<String, List<String>> responseHeaders = result.getResponseHeaders();
    rateLimit = Integer.valueOf(responseHeaders.get("X-RateLimit-Limit").get(0));
    rateLimitRemaining = Integer.valueOf(responseHeaders.get("X-RateLimit-Remaining").get(0));
    rateLimitReset = Long.valueOf(responseHeaders.get("X-RateLimit-Reset").get(0));

    RateLimitUpdateListener rateLimitUpdateListener = exchange.getRateLimitUpdateListener();
    if (rateLimitUpdateListener != null) {
      rateLimitUpdateListener.rateLimitUpdate(rateLimit, rateLimitRemaining, rateLimitReset);
    }
    return result;
  }

  public int getRateLimit() {
    return rateLimit;
  }

  public int getRateLimitRemaining() {
    return rateLimitRemaining;
  }

  public long getRateLimitReset() {
    return rateLimitReset;
  }
}
