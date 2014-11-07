/**
 * Copyright (C) 2009 - present by OpenGamma Inc. and the OpenGamma group of companies
 * 
 * Please see distribution for license.
 */
package com.opengamma.basics.currency;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.joda.beans.Bean;
import org.joda.beans.BeanBuilder;
import org.joda.beans.BeanDefinition;
import org.joda.beans.ImmutableBean;
import org.joda.beans.ImmutableValidator;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.Property;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectFieldsBeanBuilder;
import org.joda.beans.impl.direct.DirectMetaBean;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.opengamma.collect.ArgChecker;
import com.opengamma.collect.Guavate;

/**
 * A map of currency amounts keyed by currency.
 * <p>
 * This is a container holding multiple {@link CurrencyAmount} instances.
 * The amounts do not necessarily have the same worth or value in each currency.
 * <p>
 * This class is immutable and thread-safe.
 */
@BeanDefinition(builderScope = "private")
public final class MultiCurrencyAmount
    implements ImmutableBean, Serializable {
  // the choice of a set as the internal storage is driven by serialization concerns
  // the ideal storage form would be Map<Currency, CurrencyAmount> but this
  // would duplicate the currency in the serialized form
  // a set was chosen as a suitable middle ground

  /** Serialization version. */
  private static final long serialVersionUID = 1L;

  /**
   * The set of currency amounts.
   * Each currency will occur only once, as per a map keyed by currency.
   */
  @PropertyDefinition(validate = "notNull")
  private final ImmutableSortedSet<CurrencyAmount> amounts;

  //-------------------------------------------------------------------------
  /**
   * Returns a collector that can be used to create an multi-currency amount
   * from a stream of amounts.
   *
   * @return the collecor
   */
  public static Collector<CurrencyAmount, ?, MultiCurrencyAmount> collector() {
    return Collectors.collectingAndThen(
        Guavate.toImmutableSortedSet(),
        MultiCurrencyAmount::new);
  }

  //-------------------------------------------------------------------------
  /**
   * Obtains a {@code MultiCurrencyAmount} from a currency and amount.
   * 
   * @param currency  the currency
   * @param amount  the amount
   * @return the amount
   */
  public static MultiCurrencyAmount of(Currency currency, double amount) {
    ArgChecker.notNull(currency, "currency");
    return new MultiCurrencyAmount(ImmutableSortedSet.of(CurrencyAmount.of(currency, amount)));
  }

  /**
   * Obtains a {@code MultiCurrencyAmount} from an array of {@code CurrencyAmount} objects.
   * <p>
   * It is an error for the input to contain the same currency twice.
   * 
   * @param amounts  the amounts
   * @return the amount
   */
  public static MultiCurrencyAmount of(CurrencyAmount... amounts) {
    ArgChecker.notNull(amounts, "amounts");
    return of(Arrays.asList(amounts));
  }

  /**
   * Obtains a {@code MultiCurrencyAmount} from a list of {@code CurrencyAmount} objects.
   * <p>
   * It is an error for the input to contain the same currency twice.
   * 
   * @param amounts  the amounts
   * @return the amount
   */
  public static MultiCurrencyAmount of(Iterable<CurrencyAmount> amounts) {
    ArgChecker.noNulls(amounts, "amounts");
    Map<Currency, CurrencyAmount> map = new HashMap<Currency, CurrencyAmount>();
    for (CurrencyAmount amount : amounts) {
      if (map.put(amount.getCurrency(), amount) != null) {
        throw new IllegalArgumentException("Currency is duplicated: " + amount.getCurrency());
      }
    }
    return new MultiCurrencyAmount(ImmutableSortedSet.copyOf(map.values()));
  }

  /**
   * Obtains a {@code MultiCurrencyAmount} from a map of currency to amount.
   * 
   * @param map  the map of currency to amount
   * @return the amount
   */
  public static MultiCurrencyAmount of(Map<Currency, Double> map) {
    ArgChecker.noNulls(map, "map");
    return map.entrySet().stream()
        .map(entry -> CurrencyAmount.of(entry.getKey(), entry.getValue()))
        .collect(MultiCurrencyAmount.collector());
  }

  //-------------------------------------------------------------------------
  /**
   * Obtains a {@code MultiCurrencyAmount} from the total of a list of {@code CurrencyAmount} objects.
   * <p>
   * If the input contains the same currency more than once, the amounts are added together.
   * For example, an input of (EUR 100, EUR 200, CAD 100) would result in (EUR 300, CAD 100).
   * 
   * @param amounts  the amounts
   * @return the amount
   */
  public static MultiCurrencyAmount total(Iterable<CurrencyAmount> amounts) {
    ArgChecker.notNull(amounts, "amounts");
    Map<Currency, CurrencyAmount> map = new HashMap<Currency, CurrencyAmount>();
    for (CurrencyAmount currencyAmount : amounts) {
      ArgChecker.notNull(currencyAmount, "amount");
      map.merge(currencyAmount.getCurrency(), currencyAmount, CurrencyAmount::plus);
    }
    return new MultiCurrencyAmount(ImmutableSortedSet.copyOf(map.values()));
  }

  //-------------------------------------------------------------------------
  /**
   * Creates an instance where the input is already validated.
   * 
   * @param amounts  the set of amounts
   */
  private MultiCurrencyAmount(ImmutableSortedSet<CurrencyAmount> amounts) {
    this.amounts = amounts;
  }

  /**
   * Validate against duplicate currencies.
   */
  @ImmutableValidator
  private void validate() {
    long currencyCount = amounts.stream()
        .map(ArgChecker::notNullItem)
        .map(CurrencyAmount::getCurrency)
        .distinct()
        .count();
    if (currencyCount < amounts.size()) {
      throw new IllegalArgumentException("Duplicate currency not allowed: " + amounts);
    }
  }

  //-------------------------------------------------------------------------
  /**
   * Gets the set of stored currencies.
   * 
   * @return the set of currencies in this amount
   */
  public ImmutableSet<Currency> getCurrencies() {
    return amounts.stream()
        .map(CurrencyAmount::getCurrency)
        .collect(Guavate.toImmutableSet());
  }

  /**
   * Gets the number of stored amounts.
   * 
   * @return the number of amounts
   */
  public int size() {
    return amounts.size();
  }

  /**
   * Checks if this multi-amount contains an amount for the specified currency.
   * 
   * @param currency  the currency to find
   * @return true if this amount contains a value for the currency
   */
  public boolean contains(Currency currency) {
    ArgChecker.notNull(currency, "currency");
    return amounts.stream().anyMatch(ca -> ca.getCurrency().equals(currency));
  }

  /**
   * Gets the {@code CurrencyAmount} for the specified currency.
   * 
   * @param currency  the currency to find an amount for
   * @return the amount
   */
  public CurrencyAmount getAmount(Currency currency) {
    ArgChecker.notNull(currency, "currency");
    return amounts.stream()
        .filter(ca -> ca.getCurrency().equals(currency))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown currency " + currency));
  }

  //-------------------------------------------------------------------------
  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with the specified amount added.
   * <p>
   * This adds the specified amount to this monetary amount, returning a new object.
   * If the currency is already present, the amount is added to the existing amount.
   * If the currency is not yet present, the currency-amount is added to the map.
   * The addition uses standard {@code double} arithmetic.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param currency  the currency to add to
   * @param amountToAdd  the amount to add
   * @return an amount based on this with the specified amount added
   */
  public MultiCurrencyAmount plus(Currency currency, double amountToAdd) {
    return plus(CurrencyAmount.of(currency, amountToAdd));
  }

  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with the specified amount added.
   * <p>
   * This adds the specified amount to this monetary amount, returning a new object.
   * If the currency is already present, the amount is added to the existing amount.
   * If the currency is not yet present, the currency-amount is added to the map.
   * The addition uses standard {@code double} arithmetic.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param amountToAdd  the amount to add
   * @return an amount based on this with the specified amount added
   */
  public MultiCurrencyAmount plus(CurrencyAmount amountToAdd) {
    ArgChecker.notNull(amountToAdd, "amountToAdd");
    return MultiCurrencyAmount.total(Iterables.concat(amounts, ImmutableList.of(amountToAdd)));
  }

  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with the specified amount added.
   * <p>
   * This adds the specified amount to this monetary amount, returning a new object.
   * If the currency is already present, the amount is added to the existing amount.
   * If the currency is not yet present, the currency-amount is added to the map.
   * The addition uses standard {@code double} arithmetic.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param amountToAdd  the amount to add
   * @return an amount based on this with the specified amount added
   */
  public MultiCurrencyAmount plus(MultiCurrencyAmount amountToAdd) {
    ArgChecker.notNull(amountToAdd, "amountToAdd");
    return MultiCurrencyAmount.total(Iterables.concat(amounts, amountToAdd.amounts));
  }

  //-------------------------------------------------------------------------
  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with the specified amount subtracted.
   * <p>
   * This subtracts the specified amount from this monetary amount, returning a new object.
   * If the currency is already present, the amount is subtracted from the existing amount.
   * If the currency is not yet present, the negated amount is included.
   * The subtraction uses standard {@code double} arithmetic.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param currency  the currency to subtract from
   * @param amountToAdd  the amount to subtract
   * @return an amount based on this with the specified amount subtracted
   */
  public MultiCurrencyAmount minus(Currency currency, double amountToAdd) {
    return plus(CurrencyAmount.of(currency, -amountToAdd));
  }

  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with the specified amount subtracted.
   * <p>
   * This subtracts the specified amount from this monetary amount, returning a new object.
   * If the currency is already present, the amount is subtracted from the existing amount.
   * If the currency is not yet present, the negated amount is included.
   * The subtraction uses standard {@code double} arithmetic.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param amountToSubtract  the amount to subtract
   * @return an amount based on this with the specified amount subtracted
   */
  public MultiCurrencyAmount minus(CurrencyAmount amountToSubtract) {
    ArgChecker.notNull(amountToSubtract, "amountToSubtract");
    return plus(amountToSubtract.negated());
  }

  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with the specified amount subtracted.
   * <p>
   * This subtracts the specified amount from this monetary amount, returning a new object.
   * If the currency is already present, the amount is subtracted from the existing amount.
   * If the currency is not yet present, the negated amount is included.
   * The subtraction uses standard {@code double} arithmetic.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param amountToSubtract  the amount to subtract
   * @return an amount based on this with the specified amount subtracted
   */
  public MultiCurrencyAmount minus(MultiCurrencyAmount amountToSubtract) {
    ArgChecker.notNull(amountToSubtract, "amountToSubtract");
    return plus(amountToSubtract.negated());
  }

  //-------------------------------------------------------------------------
  /**
   * Returns a copy of this {@code MultiCurrencyAmount} with all the amounts multiplied by the factor.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @param factor The multiplicative factor.
   * @return an amount based on this with all the amounts multiplied by the factor
   */
  public MultiCurrencyAmount multipliedBy(double factor) {
    return mapAmounts(a -> a * factor);
  }

  /**
   * Returns a copy of this {@code CurrencyAmount} with the amount negated.
   * <p>
   * This takes this amount and negates it.
   * <p>
   * This instance is immutable and unaffected by this method. 
   * 
   * @return an amount based on this with the amount negated
   */
  public MultiCurrencyAmount negated() {
    return mapAmounts(a -> -a);
  }

  //-------------------------------------------------------------------------
  /**
   * Returns a stream over the currency amounts.
   * <p>
   * This provides access to the entire set of amounts.
   *
   * @return a stream over the individual amounts
   */
  public Stream<CurrencyAmount> stream() {
    return amounts.stream();
  }

  /**
   * Applies an operation to the amounts.
   * <p>
   * This is generally used to apply a mathematical operation to the amounts.
   * For example, the operator could multiply the amounts by a constant, or take the inverse.
   * <pre>
   *   multiplied = base.mapAmount(value -> value * 3);
   * </pre>
   *
   * @param mapper  the operator to be applied to the amounts
   * @return a copy of this amount with the mapping applied to the original amounts
   */
  public MultiCurrencyAmount mapAmounts(DoubleUnaryOperator mapper) {
    ArgChecker.notNull(mapper, "mapper");
    return amounts.stream()
        .map(ca -> ca.mapAmount(mapper))
        .collect(MultiCurrencyAmount.collector());
  }

  //-------------------------------------------------------------------------
  /**
   * Converts this {@code MultiCurrencyAmount} to a map keyed by currency.
   * 
   * @return the amounts in a map keyed by currency
   */
  public ImmutableSortedMap<Currency, Double> toMap() {
    return amounts.stream()
        .collect(Guavate.toImmutableSortedMap(CurrencyAmount::getCurrency, CurrencyAmount::getAmount));
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the amount as a string.
   * <p>
   * The format includes each currency-amount.
   * 
   * @return the currency amount
   */
  @Override
  public String toString() {
    return amounts.toString();
  }

  //------------------------- AUTOGENERATED START -------------------------
  ///CLOVER:OFF
  /**
   * The meta-bean for {@code MultiCurrencyAmount}.
   * @return the meta-bean, not null
   */
  public static MultiCurrencyAmount.Meta meta() {
    return MultiCurrencyAmount.Meta.INSTANCE;
  }

  static {
    JodaBeanUtils.registerMetaBean(MultiCurrencyAmount.Meta.INSTANCE);
  }

  private MultiCurrencyAmount(
      SortedSet<CurrencyAmount> amounts) {
    JodaBeanUtils.notNull(amounts, "amounts");
    this.amounts = ImmutableSortedSet.copyOfSorted(amounts);
    validate();
  }

  @Override
  public MultiCurrencyAmount.Meta metaBean() {
    return MultiCurrencyAmount.Meta.INSTANCE;
  }

  @Override
  public <R> Property<R> property(String propertyName) {
    return metaBean().<R>metaProperty(propertyName).createProperty(this);
  }

  @Override
  public Set<String> propertyNames() {
    return metaBean().metaPropertyMap().keySet();
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the set of currency amounts.
   * Each currency will occur only once, as per a map keyed by currency.
   * @return the value of the property, not null
   */
  public ImmutableSortedSet<CurrencyAmount> getAmounts() {
    return amounts;
  }

  //-----------------------------------------------------------------------
  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj != null && obj.getClass() == this.getClass()) {
      MultiCurrencyAmount other = (MultiCurrencyAmount) obj;
      return JodaBeanUtils.equal(getAmounts(), other.getAmounts());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int hash = getClass().hashCode();
    hash += hash * 31 + JodaBeanUtils.hashCode(getAmounts());
    return hash;
  }

  //-----------------------------------------------------------------------
  /**
   * The meta-bean for {@code MultiCurrencyAmount}.
   */
  public static final class Meta extends DirectMetaBean {
    /**
     * The singleton instance of the meta-bean.
     */
    static final Meta INSTANCE = new Meta();

    /**
     * The meta-property for the {@code amounts} property.
     */
    @SuppressWarnings({"unchecked", "rawtypes" })
    private final MetaProperty<ImmutableSortedSet<CurrencyAmount>> amounts = DirectMetaProperty.ofImmutable(
        this, "amounts", MultiCurrencyAmount.class, (Class) ImmutableSortedSet.class);
    /**
     * The meta-properties.
     */
    private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(
        this, null,
        "amounts");

    /**
     * Restricted constructor.
     */
    private Meta() {
    }

    @Override
    protected MetaProperty<?> metaPropertyGet(String propertyName) {
      switch (propertyName.hashCode()) {
        case -879772901:  // amounts
          return amounts;
      }
      return super.metaPropertyGet(propertyName);
    }

    @Override
    public BeanBuilder<? extends MultiCurrencyAmount> builder() {
      return new MultiCurrencyAmount.Builder();
    }

    @Override
    public Class<? extends MultiCurrencyAmount> beanType() {
      return MultiCurrencyAmount.class;
    }

    @Override
    public Map<String, MetaProperty<?>> metaPropertyMap() {
      return metaPropertyMap$;
    }

    //-----------------------------------------------------------------------
    /**
     * The meta-property for the {@code amounts} property.
     * @return the meta-property, not null
     */
    public MetaProperty<ImmutableSortedSet<CurrencyAmount>> amounts() {
      return amounts;
    }

    //-----------------------------------------------------------------------
    @Override
    protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
      switch (propertyName.hashCode()) {
        case -879772901:  // amounts
          return ((MultiCurrencyAmount) bean).getAmounts();
      }
      return super.propertyGet(bean, propertyName, quiet);
    }

    @Override
    protected void propertySet(Bean bean, String propertyName, Object newValue, boolean quiet) {
      metaProperty(propertyName);
      if (quiet) {
        return;
      }
      throw new UnsupportedOperationException("Property cannot be written: " + propertyName);
    }

  }

  //-----------------------------------------------------------------------
  /**
   * The bean-builder for {@code MultiCurrencyAmount}.
   */
  private static final class Builder extends DirectFieldsBeanBuilder<MultiCurrencyAmount> {

    private SortedSet<CurrencyAmount> amounts = new TreeSet<CurrencyAmount>();

    /**
     * Restricted constructor.
     */
    private Builder() {
    }

    //-----------------------------------------------------------------------
    @Override
    public Object get(String propertyName) {
      switch (propertyName.hashCode()) {
        case -879772901:  // amounts
          return amounts;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Builder set(String propertyName, Object newValue) {
      switch (propertyName.hashCode()) {
        case -879772901:  // amounts
          this.amounts = (SortedSet<CurrencyAmount>) newValue;
          break;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
      return this;
    }

    @Override
    public Builder set(MetaProperty<?> property, Object value) {
      super.set(property, value);
      return this;
    }

    @Override
    public Builder setString(String propertyName, String value) {
      setString(meta().metaProperty(propertyName), value);
      return this;
    }

    @Override
    public Builder setString(MetaProperty<?> property, String value) {
      super.setString(property, value);
      return this;
    }

    @Override
    public Builder setAll(Map<String, ? extends Object> propertyValueMap) {
      super.setAll(propertyValueMap);
      return this;
    }

    @Override
    public MultiCurrencyAmount build() {
      return new MultiCurrencyAmount(
          amounts);
    }

    //-----------------------------------------------------------------------
    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder(64);
      buf.append("MultiCurrencyAmount.Builder{");
      buf.append("amounts").append('=').append(JodaBeanUtils.toString(amounts));
      buf.append('}');
      return buf.toString();
    }

  }

  ///CLOVER:ON
  //-------------------------- AUTOGENERATED END --------------------------
}
