package com.ludovictemgoua.votee.model;

import java.math.BigInteger;

public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {

    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    // Validate the Rational number to ensure the denominator is not zero and reduce it to its simplest form
    public Rational {
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException("Denominator cannot be zero");
        }
        if(denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        if(gcd.signum() != 0 && !gcd.equals(BigInteger.ONE)) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    public static Rational of(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Rational whole(long value) {
        return new Rational(BigInteger.valueOf(value), BigInteger.ONE);
    }
    public Rational add(Rational other) {
        BigInteger newNumerator = this.numerator.multiply(other.denominator).add(other.numerator.multiply(this.denominator));
        BigInteger newDenominator = this.denominator.multiply(other.denominator);
        return new Rational(newNumerator, newDenominator);
    }

    public Rational subtract(Rational other) {
        BigInteger newNumerator = this.numerator.multiply(other.denominator).subtract(other.numerator.multiply(this.denominator));
        BigInteger newDenominator = this.denominator.multiply(other.denominator);
        return new Rational(newNumerator, newDenominator);
    }

    public Rational multiply(Rational other) {
        BigInteger newNumerator = this.numerator.multiply(other.numerator);
        BigInteger newDenominator = this.denominator.multiply(other.denominator);
        return new Rational(newNumerator, newDenominator);
    }

    public Rational divide(Rational other) {
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("Cannot divide by zero");
        }
        BigInteger newNumerator = this.numerator.multiply(other.denominator);
        BigInteger newDenominator = this.denominator.multiply(other.numerator);
        return new Rational(newNumerator, newDenominator);
    }

    public Rational negate() {
        return new Rational(this.numerator.negate(), this.denominator);
    }

    public Rational abs() {
        return new Rational(this.numerator.abs(), this.denominator);
    }

    @Override
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
}
