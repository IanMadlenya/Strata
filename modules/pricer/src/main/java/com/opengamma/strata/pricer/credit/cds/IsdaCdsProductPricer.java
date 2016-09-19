/**
 * Copyright (C) 2016 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.credit.cds;

import static com.opengamma.strata.math.impl.util.Epsilon.epsilon;
import static com.opengamma.strata.math.impl.util.Epsilon.epsilonP;
import static com.opengamma.strata.math.impl.util.Epsilon.epsilonPP;

import java.time.LocalDate;

import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.currency.CurrencyAmount;
import com.opengamma.strata.collect.ArgChecker;
import com.opengamma.strata.collect.tuple.Pair;
import com.opengamma.strata.market.sensitivity.PointSensitivityBuilder;
import com.opengamma.strata.math.impl.util.Epsilon;
import com.opengamma.strata.product.credit.cds.CreditCouponPaymentPeriod;
import com.opengamma.strata.product.credit.cds.ResolvedCds;

/**
 * Pricer for single-name credit default swaps (CDS) based on ISDA standard model. 
 * <p>
 * The implementation is based on the ISDA model versions 1.8.2.
 */
public class IsdaCdsProductPricer {

  /**
   * Default implementation.
   */
  public static final IsdaCdsProductPricer DEFAULT = new IsdaCdsProductPricer(AccrualOnDefaultFormulae.ORIGINAL_ISDA);

  /**
   * The formula
   */
  private final AccrualOnDefaultFormulae formula;

  /**
   * The omega parameter.
   */
  private final double omega;

  /**
   * Constructor specifying the formula to use for the accrued on default calculation.  
   * <p>
   * Options are the formula given in the ISDA model (version 1.8.2 and lower); 
   * the proposed fix by Markit (given as a comment in  version 1.8.2), or the mathematically correct formula. 
   * <p>
   * @param formula  the formula
   */
  public IsdaCdsProductPricer(AccrualOnDefaultFormulae formula) {
    this.formula = ArgChecker.notNull(formula, "formula");
    if (formula == AccrualOnDefaultFormulae.ORIGINAL_ISDA) {
      omega = 1d / 730d;
    } else {
      omega = 0d;
    }
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the present value of the CDS product.
   * <p>
   * The present value of the product is based on {@code referenceDate}.
   * This is typically the valuation date, or cash settlement date if the product is associated with a {@code Trade}. 
   * <p>
   * The price type is clean or dirty. The accrued interest is computed based on the valuation date.
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param priceType  the price type
   * @param refData  the reference data
   * @return the present value
   */
  public CurrencyAmount presentValue(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      PriceType priceType,
      ReferenceData refData) {

    double price = price(cds, ratesProvider, referenceDate, priceType, refData);
    return CurrencyAmount.of(cds.getCurrency(), cds.getBuySell().normalize(cds.getNotional()) * price);
  }

  /**
   * Calculates the price of the CDS product, i.e., the present value per unit notional. 
   * <p>
   * This is coherent with {@link #presentValue(ResolvedCds, CreditRatesProvider, LocalDate, PriceType, ReferenceData)}.
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param priceType  the price type
   * @param refData  the reference data
   * @return the price
   */
  public double price(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      PriceType priceType,
      ReferenceData refData) {

    ArgChecker.notNull(cds, "cds");
    ArgChecker.notNull(ratesProvider, "ratesProvider");
    ArgChecker.notNull(referenceDate, "referenceDate");
    ArgChecker.notNull(refData, "refData");
    if (!cds.getProtectionEndDate().isAfter(ratesProvider.getValuationDate())) { //short cut already expired CDSs
      return 0d;
    }
    LocalDate stepinDate = cds.getStepinDateOffset().adjust(ratesProvider.getValuationDate(), refData);
    LocalDate effectiveStartDate = cds.getEffectiveStartDate(stepinDate);
    double recoveryRate = recoveryRate(cds, ratesProvider);
    Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> rates = reduceDiscountFactors(cds, ratesProvider);
    double protectionLeg =
        protectionLeg(cds, rates.getFirst(), rates.getSecond(), referenceDate, effectiveStartDate, recoveryRate);
    double rpv01 = riskyAnnuity(
        cds, rates.getFirst(), rates.getSecond(), referenceDate, stepinDate, effectiveStartDate, priceType);
    return protectionLeg - rpv01 * cds.getFixedRate();
  }

  /**
   * Calculates the par spread of the CDS product.
   * <p>
   * The par spread is a coupon rate such that the clean PV is 0. 
   * The result is represented in decimal form. 
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param refData  the reference data
   * @return the par spread
   */
  public double parSpread(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      ReferenceData refData) {

    ArgChecker.notNull(cds, "cds");
    ArgChecker.notNull(ratesProvider, "ratesProvider");
    ArgChecker.notNull(referenceDate, "referenceDate");
    ArgChecker.notNull(refData, "refData");
    ArgChecker.isTrue(cds.getProtectionEndDate().isAfter(ratesProvider.getValuationDate()), "CDS already expired");
    LocalDate stepinDate = cds.getStepinDateOffset().adjust(ratesProvider.getValuationDate(), refData);
    LocalDate effectiveStartDate = cds.getEffectiveStartDate(stepinDate);
    double recoveryRate = recoveryRate(cds, ratesProvider);
    Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> rates = reduceDiscountFactors(cds, ratesProvider);
    double protectionLeg =
        protectionLeg(cds, rates.getFirst(), rates.getSecond(), referenceDate, effectiveStartDate, recoveryRate);
    double riskyAnnuity =
        riskyAnnuity(cds, rates.getFirst(), rates.getSecond(), referenceDate, stepinDate, effectiveStartDate, PriceType.CLEAN);
    return protectionLeg / riskyAnnuity;
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the price of the protection leg, i.e., the protection leg present value per unit notional.
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param refData  the reference data
   * @return the protection leg price
   */
  public double protectionLeg(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      ReferenceData refData) {

    ArgChecker.notNull(cds, "cds");
    ArgChecker.notNull(ratesProvider, "ratesProvider");
    ArgChecker.notNull(refData, "refData");
    if (!cds.getProtectionEndDate().isAfter(ratesProvider.getValuationDate())) { //short cut already expired CDSs
      return 0d;
    }
    LocalDate stepinDate = cds.getStepinDateOffset().adjust(ratesProvider.getValuationDate(), refData);
    LocalDate effectiveStartDate = cds.getEffectiveStartDate(stepinDate);
    double recoveryRate = recoveryRate(cds, ratesProvider);
    Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> rates = reduceDiscountFactors(cds, ratesProvider);
    return protectionLeg(cds, rates.getFirst(), rates.getSecond(), referenceDate, effectiveStartDate, recoveryRate);
  }

  /**
   * Calculates the risky annuity, i.e., RPV01 per unit notional.
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param priceType  the price type
   * @param refData  the reference data
   * @return the risky annuity
   */
  public double riskyAnnuity(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      PriceType priceType,
      ReferenceData refData) {

    ArgChecker.notNull(cds, "cds");
    ArgChecker.notNull(ratesProvider, "ratesProvider");
    ArgChecker.notNull(refData, "refData");
    if (!cds.getProtectionEndDate().isAfter(ratesProvider.getValuationDate())) { //short cut already expired CDSs
      return 0d;
    }
    LocalDate stepinDate = cds.getStepinDateOffset().adjust(ratesProvider.getValuationDate(), refData);
    LocalDate effectiveStartDate = cds.getEffectiveStartDate(stepinDate);
    Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> rates = reduceDiscountFactors(cds, ratesProvider);
    return riskyAnnuity(cds, rates.getFirst(), rates.getSecond(), referenceDate, stepinDate, effectiveStartDate, priceType);
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the risky PV01 of the CDS product. 
   * <p>
   * RPV01 is defined as minus of the present value sensitivity to coupon rate.
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param priceType  the price type
   * @param refData  the reference date
   * @return the RPV01
   */
  public CurrencyAmount rpv01(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      PriceType priceType,
      ReferenceData refData) {

    double riskyAnnuity = riskyAnnuity(cds, ratesProvider, referenceDate, priceType, refData);
    return CurrencyAmount.of(cds.getCurrency(), cds.getBuySell().normalize(cds.getNotional()) * riskyAnnuity);
  }

  /**
   * Calculates the recovery01 of the CDS product.
   * <p>
   * The recovery01 is defined as the present value sensitivity to the recovery rate.
   * Since the ISDA standard model requires the recovery rate to be constant throughout the lifetime of the CDS,  
   * one currency amount is returned.
   * 
   * @param cds  the product
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param refData  the reference data
   * @return the recovery01
   */
  public CurrencyAmount recovery01(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      ReferenceData refData) {

    ArgChecker.notNull(cds, "cds");
    ArgChecker.notNull(ratesProvider, "ratesProvider");
    ArgChecker.notNull(referenceDate, "referenceDate");
    ArgChecker.notNull(refData, "refData");
    if (!cds.getProtectionEndDate().isAfter(ratesProvider.getValuationDate())) { //short cut already expired CDSs
      return CurrencyAmount.of(cds.getCurrency(), 0d);
    }
    LocalDate stepinDate = cds.getStepinDateOffset().adjust(ratesProvider.getValuationDate(), refData);
    LocalDate effectiveStartDate = cds.getEffectiveStartDate(stepinDate);
    recoveryRate(cds, ratesProvider); // for validation
    Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> rates = reduceDiscountFactors(cds, ratesProvider);
    double protectionFull =
        protectionFull(cds, rates.getFirst(), rates.getSecond(), referenceDate, effectiveStartDate);

    return CurrencyAmount.of(cds.getCurrency(), -cds.getBuySell().normalize(cds.getNotional()) * protectionFull);
  }

  /**
   * Calculates the present value sensitivity of the product. 
   * <p>
   * The present value sensitivity of the product is the sensitivity of present value to the underlying curves.
   * 
   * @param cds  the product 
   * @param ratesProvider  the rates provider
   * @param referenceDate  the reference date
   * @param refData  the reference data
   * @return the present value sensitivity
   */
  public PointSensitivityBuilder presentValueSensitivity(
      ResolvedCds cds,
      CreditRatesProvider ratesProvider,
      LocalDate referenceDate,
      ReferenceData refData) {

    ArgChecker.notNull(cds, "cds");
    ArgChecker.notNull(ratesProvider, "ratesProvider");
    ArgChecker.notNull(referenceDate, "referenceDate");
    ArgChecker.notNull(refData, "refData");
    if (!cds.getProtectionEndDate().isAfter(ratesProvider.getValuationDate())) { //short cut already expired CDSs
      return PointSensitivityBuilder.none();
    }
    LocalDate stepinDate = cds.getStepinDateOffset().adjust(ratesProvider.getValuationDate(), refData);
    LocalDate effectiveStartDate = cds.getEffectiveStartDate(stepinDate);
    double recoveryRate = recoveryRate(cds, ratesProvider);
    Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> rates = reduceDiscountFactors(cds, ratesProvider);

    double signedNotional = cds.getBuySell().normalize(cds.getNotional());
    PointSensitivityBuilder protectionLegSensi =
        protectionLegSensitivity(cds, rates.getFirst(), rates.getSecond(), referenceDate, effectiveStartDate, recoveryRate)
            .multipliedBy(signedNotional);
    PointSensitivityBuilder riskyAnnuitySensi = riskyAnnuitySensitivity(
        cds, rates.getFirst(), rates.getSecond(), referenceDate, stepinDate, effectiveStartDate)
            .multipliedBy(-cds.getFixedRate() * signedNotional);

    return protectionLegSensi.combinedWith(riskyAnnuitySensi);
  }

  //-------------------------------------------------------------------------
  private double protectionLeg(ResolvedCds cds,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities,
      LocalDate referenceDate,
      LocalDate effectiveStartDate,
      double recoveryRate) {

    double protectionFull = protectionFull(cds, discountFactors, survivalProbabilities, referenceDate, effectiveStartDate);
    return (1d - recoveryRate) * protectionFull;
  }

  private double protectionFull(
      ResolvedCds cds,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities,
      LocalDate referenceDate,
      LocalDate effectiveStartDate) {

    double[] integrationSchedule = DoublesScheduleGenerator.getIntegrationsPoints(
        discountFactors.relativeYearFraction(effectiveStartDate),
        discountFactors.relativeYearFraction(cds.getProtectionEndDate()),
        discountFactors.getParameterKeys(),
        survivalProbabilities.getParameterKeys());

    double pv = 0d;
    double ht0 = survivalProbabilities.zeroRate(integrationSchedule[0]) * integrationSchedule[0];
    double rt0 = discountFactors.zeroRate(integrationSchedule[0]) * integrationSchedule[0];
    double b0 = Math.exp(-ht0 - rt0);
    int n = integrationSchedule.length;
    for (int i = 1; i < n; ++i) {
      double ht1 = survivalProbabilities.zeroRate(integrationSchedule[i]) * integrationSchedule[i];
      double rt1 = discountFactors.zeroRate(integrationSchedule[i]) * integrationSchedule[i];
      double b1 = Math.exp(-ht1 - rt1);
      double dht = ht1 - ht0;
      double drt = rt1 - rt0;
      double dhrt = dht + drt;

      // The formula has been modified from ISDA (but is equivalent) to avoid log(exp(x)) and explicitly
      // calculating the time step - it also handles the limit
      double dPV = 0d;
      if (Math.abs(dhrt) < 1e-5) {
        dPV = dht * b0 * epsilon(-dhrt);
      } else {
        dPV = (b0 - b1) * dht / dhrt;
      }

      pv += dPV;
      ht0 = ht1;
      rt0 = rt1;
      b0 = b1;
    }

    // roll to the cash settle date
    double df = discountFactors.discountFactor(referenceDate);
    pv /= df;

    return pv;
  }

  private double riskyAnnuity(
      ResolvedCds cds,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities,
      LocalDate referenceDate,
      LocalDate stepinDate,
      LocalDate effectiveStartDate,
      PriceType priceType) {

    double pv = 0d;
    for (CreditCouponPaymentPeriod coupon : cds.getPeriodicPayments()) {
      if (stepinDate.isBefore(coupon.getEndDate())) {
        double q = survivalProbabilities.survivalProbability(coupon.getEffectiveEndDate());
        double p = discountFactors.discountFactor(coupon.getPaymentDate());
        pv += coupon.getYearFraction() * p * q;
      }
    }

    if (cds.getPaymentOnDefault().isAccruedInterest()) {
      //This is needed so that the code is consistent with ISDA C when the Markit `fix' is used. For forward starting CDS (accStart > trade-date),
      //and more than one coupon, the C code generates an extra integration point (a node at protection start and one the day before) - normally
      //the second point could be ignored (since is doesn't correspond to a node of the curves, nor is it the start point), but the Markit fix is 
      //mathematically incorrect, so this point affects the result.  
      LocalDate start = cds.getPeriodicPayments().size() == 1 ? effectiveStartDate : cds.getAccrualStartDate();
      double[] integrationSchedule = DoublesScheduleGenerator.getIntegrationsPoints(
          discountFactors.relativeYearFraction(start),
          discountFactors.relativeYearFraction(cds.getProtectionEndDate()),
          discountFactors.getParameterKeys(),
          survivalProbabilities.getParameterKeys());
      for (CreditCouponPaymentPeriod coupon : cds.getPeriodicPayments()) {
        pv += singlePeriodAccrualOnDefault(
            coupon, effectiveStartDate, integrationSchedule, discountFactors, survivalProbabilities);
      }
    }
    // roll to the cash settle date
    double df = discountFactors.discountFactor(referenceDate);
    pv /= df;

    if (priceType.isCleanPrice()) {
      pv -= cds.accruedYearFraction(stepinDate);
    }

    return pv;
  }

  private double singlePeriodAccrualOnDefault(
      CreditCouponPaymentPeriod coupon,
      LocalDate effectiveStartDate,
      double[] integrationSchedule,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities) {

    LocalDate start =
        coupon.getEffectiveStartDate().isBefore(effectiveStartDate) ? effectiveStartDate : coupon.getEffectiveStartDate();
    if (!start.isBefore(coupon.getEffectiveEndDate())) {
      return 0d; //this coupon has already expired 
    }

    double[] knots = DoublesScheduleGenerator.truncateSetInclusive(discountFactors.relativeYearFraction(start),
        discountFactors.relativeYearFraction(coupon.getEffectiveEndDate()), integrationSchedule);

    double t = knots[0];
    double ht0 = survivalProbabilities.zeroRate(t) * t;
    double rt0 = discountFactors.zeroRate(t) * t;
    double b0 = Math.exp(-rt0 - ht0);

    double effStart = discountFactors.relativeYearFraction(coupon.getEffectiveStartDate());
    double t0 = t - effStart + omega;
    double pv = 0d;
    final int nItems = knots.length;
    for (int j = 1; j < nItems; ++j) {
      t = knots[j];
      double ht1 = survivalProbabilities.zeroRate(t) * t;
      double rt1 = discountFactors.zeroRate(t) * t;
      double b1 = Math.exp(-rt1 - ht1);

      double dt = knots[j] - knots[j - 1];

      double dht = ht1 - ht0;
      double drt = rt1 - rt0;
      double dhrt = dht + drt;

      double tPV;
      if (formula == AccrualOnDefaultFormulae.MARKIT_FIX) {
        if (Math.abs(dhrt) < 1e-5) {
          tPV = dht * dt * b0 * Epsilon.epsilonP(-dhrt);
        } else {
          tPV = dht * dt / dhrt * ((b0 - b1) / dhrt - b1);
        }
      } else {
        double t1 = t - effStart + omega;
        if (Math.abs(dhrt) < 1e-5) {
          tPV = dht * b0 * (t0 * epsilon(-dhrt) + dt * Epsilon.epsilonP(-dhrt));
        } else {
          tPV = dht / dhrt * (t0 * b0 - t1 * b1 + dt / dhrt * (b0 - b1));
        }
        t0 = t1;
      }

      pv += tPV;
      ht0 = ht1;
      rt0 = rt1;
      b0 = b1;
    }

    double yearFractionCurve =
        discountFactors.getDayCount().relativeYearFraction(coupon.getStartDate(), coupon.getEndDate());
    return coupon.getYearFraction() * pv / yearFractionCurve;
  }

  //-------------------------------------------------------------------------
  private PointSensitivityBuilder protectionLegSensitivity(
      ResolvedCds cds,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities,
      LocalDate referenceDate,
      LocalDate effectiveStartDate,
      double recoveryRate) {

    double[] integrationSchedule = DoublesScheduleGenerator.getIntegrationsPoints(
        discountFactors.relativeYearFraction(effectiveStartDate),
        discountFactors.relativeYearFraction(cds.getProtectionEndDate()),
        discountFactors.getParameterKeys(),
        survivalProbabilities.getParameterKeys());
    int n = integrationSchedule.length;
    double[] dht = new double[n - 1];
    double[] drt = new double[n - 1];
    double[] dhrt = new double[n - 1];
    double[] p = new double[n];
    double[] q = new double[n];
    // pv
    double pv = 0d;
    double ht0 = survivalProbabilities.zeroRate(integrationSchedule[0]) * integrationSchedule[0];
    double rt0 = discountFactors.zeroRate(integrationSchedule[0]) * integrationSchedule[0];
    p[0] = Math.exp(-rt0);
    q[0] = Math.exp(-ht0);
    double b0 = p[0] * q[0];
    for (int i = 1; i < n; ++i) {
      double ht1 = survivalProbabilities.zeroRate(integrationSchedule[i]) * integrationSchedule[i];
      double rt1 = discountFactors.zeroRate(integrationSchedule[i]) * integrationSchedule[i];
      p[i] = Math.exp(-rt1);
      q[i] = Math.exp(-ht1);
      double b1 = p[i] * q[i];
      dht[i - 1] = ht1 - ht0;
      drt[i - 1] = rt1 - rt0;
      dhrt[i - 1] = dht[i - 1] + drt[i - 1];
      double dPv = 0d;
      if (Math.abs(dhrt[i - 1]) < 1e-5) {
        double eps = epsilon(-dhrt[i - 1]);
        dPv = dht[i - 1] * b0 * eps;
      } else {
        dPv = (b0 - b1) * dht[i - 1] / dhrt[i - 1];
    }
      pv += dPv;
      ht0 = ht1;
      rt0 = rt1;
      b0 = b1;
    }
    double df = discountFactors.discountFactor(referenceDate);
    // pv sensitivity
    double factor = (1d - recoveryRate) / df;
    double eps0 = computeExtendedEpsilon(-dhrt[0], p[1], q[1], p[0], q[0]);
    PointSensitivityBuilder pvSensi = discountFactors.zeroRatePointSensitivity(integrationSchedule[0])
        .multipliedBy(-dht[0] * q[0] * eps0 * factor);
    pvSensi = pvSensi.combinedWith(survivalProbabilities.zeroRatePointSensitivity(integrationSchedule[0])
        .multipliedBy(factor * (drt[0] * p[0] * eps0 + p[0])));
    for (int i = 1; i < n - 1; ++i) {
      double epsp = computeExtendedEpsilon(-dhrt[i], p[i + 1], q[i + 1], p[i], q[i]);
      double epsm = computeExtendedEpsilon(dhrt[i - 1], p[i - 1], q[i - 1], p[i], q[i]);
      PointSensitivityBuilder pSensi = discountFactors.zeroRatePointSensitivity(integrationSchedule[i])
          .multipliedBy(factor * (-dht[i] * q[i] * epsp - dht[i - 1] * q[i] * epsm));
      PointSensitivityBuilder qSensi = survivalProbabilities.zeroRatePointSensitivity(integrationSchedule[i])
          .multipliedBy(factor * (drt[i - 1] * p[i] * epsm + drt[i] * p[i] * epsp));
      pvSensi = pvSensi.combinedWith(pSensi).combinedWith(qSensi);
    }
    if (n > 1) {
      double epsLast = computeExtendedEpsilon(dhrt[n - 2], p[n - 2], q[n - 2], p[n - 1], q[n - 1]);
      pvSensi = pvSensi.combinedWith(discountFactors.zeroRatePointSensitivity(integrationSchedule[n - 1])
          .multipliedBy(-dht[n - 2] * q[n - 1] * epsLast * factor));
      pvSensi = pvSensi.combinedWith(survivalProbabilities.zeroRatePointSensitivity(integrationSchedule[n - 1])
          .multipliedBy(factor * (drt[n - 2] * p[n - 1] * epsLast - p[n - 1])));
    }
    
    PointSensitivityBuilder dfSensi =
        discountFactors.zeroRatePointSensitivity(referenceDate).multipliedBy(-pv * factor / df);
    return dfSensi.combinedWith(pvSensi);
  }

  private double computeExtendedEpsilon(double dhrt, double pn, double qn, double pd, double qd) {
    if (Math.abs(dhrt) < 1e-5) {
      return -0.5 - dhrt / 6d - dhrt * dhrt / 24d;
    }
    return (1d - (pn * qn / (pd * qd) - 1d) / dhrt) / dhrt;
  }

  private PointSensitivityBuilder riskyAnnuitySensitivity(
      ResolvedCds cds,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities,
      LocalDate referenceDate,
      LocalDate stepinDate,
      LocalDate effectiveStartDate) {

    double pv = 0d;
    PointSensitivityBuilder pvSensi = PointSensitivityBuilder.none();
    for (CreditCouponPaymentPeriod coupon : cds.getPeriodicPayments()) {
      if (stepinDate.isBefore(coupon.getEndDate())) {
        double q = survivalProbabilities.survivalProbability(coupon.getEffectiveEndDate());
        PointSensitivityBuilder qSensi = survivalProbabilities.zeroRatePointSensitivity(coupon.getEffectiveEndDate());
        double p = discountFactors.discountFactor(coupon.getPaymentDate());
        PointSensitivityBuilder pSensi = discountFactors.zeroRatePointSensitivity(coupon.getPaymentDate());
        pv += coupon.getYearFraction() * p * q;
        pvSensi = pvSensi.combinedWith(pSensi.multipliedBy(coupon.getYearFraction() * q)
            .combinedWith(qSensi.multipliedBy(coupon.getYearFraction() * p)));
      }
    }

    if (cds.getPaymentOnDefault().isAccruedInterest()) {
      //This is needed so that the code is consistent with ISDA C when the Markit `fix' is used. For forward starting CDS (accStart > trade-date),
      //and more than one coupon, the C code generates an extra integration point (a node at protection start and one the day before) - normally
      //the second point could be ignored (since is doesn't correspond to a node of the curves, nor is it the start point), but the Markit fix is 
      //mathematically incorrect, so this point affects the result.  
      LocalDate start = cds.getPeriodicPayments().size() == 1 ? effectiveStartDate : cds.getAccrualStartDate();
      double[] integrationSchedule = DoublesScheduleGenerator.getIntegrationsPoints(
          discountFactors.relativeYearFraction(start),
          discountFactors.relativeYearFraction(cds.getProtectionEndDate()),
          discountFactors.getParameterKeys(),
          survivalProbabilities.getParameterKeys());
      for (CreditCouponPaymentPeriod coupon : cds.getPeriodicPayments()) {
        Pair<Double, PointSensitivityBuilder> pvAndSensi = singlePeriodAccrualOnDefaultSensitivity(
            coupon, effectiveStartDate, integrationSchedule, discountFactors, survivalProbabilities);
        pv += pvAndSensi.getFirst();
        pvSensi = pvSensi.combinedWith(pvAndSensi.getSecond());
      }
    }

    double df = discountFactors.discountFactor(referenceDate);
    PointSensitivityBuilder dfSensi =
        discountFactors.zeroRatePointSensitivity(referenceDate).multipliedBy(-pv / (df * df));
    pvSensi = pvSensi.multipliedBy(1d / df);

    return dfSensi.combinedWith(pvSensi);
  }

  private  Pair<Double, PointSensitivityBuilder> singlePeriodAccrualOnDefaultSensitivity(
      CreditCouponPaymentPeriod coupon,
      LocalDate effectiveStartDate,
      double[] integrationSchedule,
      CreditDiscountFactors discountFactors,
      LegalEntitySurvivalProbabilities survivalProbabilities) {

    LocalDate start =
        coupon.getEffectiveStartDate().isBefore(effectiveStartDate) ? effectiveStartDate : coupon.getEffectiveStartDate();
    if (!start.isBefore(coupon.getEffectiveEndDate())) {
      return Pair.of(0d, PointSensitivityBuilder.none()) ; //this coupon has already expired 
    }
    double[] knots = DoublesScheduleGenerator.truncateSetInclusive(discountFactors.relativeYearFraction(start),
        discountFactors.relativeYearFraction(coupon.getEffectiveEndDate()), integrationSchedule);
    // pv
    double pv = 0d;
    final int nItems = knots.length;
    double[] dhrtBar = new double[nItems - 1];
    double[] dhtBar = new double[nItems - 1];
    double[] bBar = new double[nItems];
    double[] p = new double[nItems];
    double[] q = new double[nItems];
    double t = knots[0];
    double ht0 = survivalProbabilities.zeroRate(t) * t;
    double rt0 = discountFactors.zeroRate(t) * t;
    q[0] = Math.exp(-ht0);
    p[0] = Math.exp(-rt0);
    double b0 = q[0] * p[0];
    double effStart = discountFactors.relativeYearFraction(coupon.getEffectiveStartDate());
    double t0 = t - effStart + omega;
    for (int i = 1; i < nItems; ++i) {
      t = knots[i];
      double ht1 = survivalProbabilities.zeroRate(t) * t;
      double rt1 = discountFactors.zeroRate(t) * t;
      q[i] = Math.exp(-ht1);
      p[i] = Math.exp(-rt1);
      double b1 = q[i] * p[i];
      double dt = knots[i] - knots[i - 1];
      double dht = ht1 - ht0;
      double drt = rt1 - rt0;
      double dhrt = dht + drt;
      double tPv;
      if (formula == AccrualOnDefaultFormulae.MARKIT_FIX) {
        if (Math.abs(dhrt) < 1e-5) {
          double eps = epsilonP(-dhrt);
          tPv = dht * dt * b0 * eps;
          dhtBar[i - 1] = dt * b0 * eps;
          dhrtBar[i - 1] = -dht * dt * b0 * epsilonPP(-dhrt);
          bBar[i - 1] += dht * eps;
        } else {
          tPv = dht * dt / dhrt * ((b0 - b1) / dhrt - b1);
          dhtBar[i - 1] = dt / dhrt * ((b0 - b1) / dhrt - b1);
          dhrtBar[i - 1] = dht * dt / (dhrt * dhrt) * (b1 - 2d * (b0 - b1) / dhrt);
          bBar[i - 1] += dht * dt / (dhrt * dhrt);
          bBar[i] += -dht * dt / dhrt * (1d + 1d / dhrt);
        }
      } else {
        double t1 = t - effStart + omega;
        if (Math.abs(dhrt) < 1e-5) {
          double eps = epsilon(-dhrt);
          double epsp = epsilonP(-dhrt);
          tPv = dht * b0 * (t0 * eps + dt * epsp);
          dhtBar[i - 1] = b0 * (t0 * eps + dt * epsp);
          dhrtBar[i - 1] = -dht * b0 * (t0 * epsp + dt * epsilonPP(-dhrt));
          bBar[i - 1] += dht * (t0 * eps + dt * epsp);
        } else {
          tPv = dht / dhrt * (t0 * b0 - t1 * b1 + dt / dhrt * (b0 - b1));
          dhtBar[i - 1] = (t0 * b0 - t1 * b1 + dt / dhrt * (b0 - b1)) / dhrt;
          dhrtBar[i - 1] = dht / (dhrt * dhrt) * (-2d * dt / dhrt * (b0 - b1) - t0 * b0 + t1 * b1);
          bBar[i - 1] += dht / dhrt * (t0 + dt / dhrt);
          bBar[i] += dht / dhrt * (-t1 - dt / dhrt);
        }
        t0 = t1;
      }
      pv += tPv;
      ht0 = ht1;
      rt0 = rt1;
      b0 = b1;
    }
    double yfRatio = coupon.getYearFraction() /
        discountFactors.getDayCount().relativeYearFraction(coupon.getStartDate(), coupon.getEndDate());
    // pv sensitivity
    PointSensitivityBuilder qSensiFirst = survivalProbabilities.zeroRatePointSensitivity(knots[0])
        .multipliedBy(yfRatio * ((dhrtBar[0] + dhtBar[0]) / q[0] + bBar[0] * p[0]));
    PointSensitivityBuilder pSensiFirst = discountFactors.zeroRatePointSensitivity(knots[0])
        .multipliedBy(yfRatio * (dhrtBar[0] / p[0] + bBar[0] * q[0]));
    PointSensitivityBuilder pvSensi = pSensiFirst.combinedWith(qSensiFirst);
    for (int i = 1; i < nItems - 1; ++i) {
      PointSensitivityBuilder qSensi = survivalProbabilities.zeroRatePointSensitivity(knots[i]).multipliedBy(
          yfRatio * (-(dhrtBar[i - 1] + dhtBar[i - 1]) / q[i] + (dhrtBar[i] + dhtBar[i]) / q[i] + bBar[i] * p[i]));
      PointSensitivityBuilder pSensi = discountFactors.zeroRatePointSensitivity(knots[i]).multipliedBy(
          yfRatio * (-dhrtBar[i - 1] / p[i] + dhrtBar[i] / p[i] + bBar[i] * q[i]));
      pvSensi = pvSensi.combinedWith(pSensi).combinedWith(qSensi);
    }
    if (nItems > 1) {
      PointSensitivityBuilder qSensiLast = survivalProbabilities.zeroRatePointSensitivity(knots[nItems - 1]).multipliedBy(
          yfRatio * (-(dhrtBar[nItems - 2] + dhtBar[nItems - 2]) / q[nItems - 1] + bBar[nItems - 1] * p[nItems - 1]));
      PointSensitivityBuilder pSensiLast = discountFactors.zeroRatePointSensitivity(knots[nItems - 1]).multipliedBy(
          yfRatio * (-dhrtBar[nItems - 2] / p[nItems - 1] + bBar[nItems - 1] * q[nItems - 1]));
      pvSensi = pvSensi.combinedWith(pSensiLast).combinedWith(qSensiLast);
    }

    return Pair.of(yfRatio * pv, pvSensi);
  }

  //-------------------------------------------------------------------------
  private double recoveryRate(ResolvedCds cds, CreditRatesProvider ratesProvider) {
    RecoveryRates recoveryRates = ratesProvider.recoveryRates(cds.getLegalEntityId());
    ArgChecker.isTrue(recoveryRates instanceof ConstantRecoveryRates, "recoveryRates must be ConstantRecoveryRates");
    return recoveryRates.recoveryRate(cds.getProtectionEndDate());
  }

  private Pair<CreditDiscountFactors, LegalEntitySurvivalProbabilities> reduceDiscountFactors(
      ResolvedCds cds, CreditRatesProvider ratesProvider) {
    Currency currency = cds.getCurrency();
    CreditDiscountFactors discountFactors = ratesProvider.discountFactors(currency);
    ArgChecker.isTrue(discountFactors instanceof IsdaCompliantZeroRateDiscountFactors,
        "discount factors must be IsdaCompliantZeroRateDiscountFactors");
    LegalEntitySurvivalProbabilities survivalProbabilities =
        ratesProvider.survivalProbabilities(cds.getLegalEntityId(), currency);
    ArgChecker.isTrue(survivalProbabilities.getSurvivalProbabilities() instanceof IsdaCompliantZeroRateDiscountFactors,
        "survival probabilities must be IsdaCompliantZeroRateDiscountFactors");
    ArgChecker.isTrue(discountFactors.getDayCount().equals(survivalProbabilities.getSurvivalProbabilities().getDayCount()),
        "day count conventions of discounting curve and credit curve must be the same");
    return Pair.of(discountFactors, survivalProbabilities);
  }

}
