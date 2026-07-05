package com.ludovictemgoua.votee.model;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RationalTest {

    @Test
    void reducesToLowestTermsOnConstruction() {
        Rational sixEighths = new Rational(BigInteger.valueOf(6), BigInteger.valueOf(8));

        assertThat(sixEighths.numerator()).isEqualTo(BigInteger.valueOf(3));
        assertThat(sixEighths.denominator()).isEqualTo(BigInteger.valueOf(4));
    }

    @Test
    void normalizesANegativeDenominatorOntoTheNumerator() {
        Rational negativeThreeQuarters = new Rational(BigInteger.valueOf(3), BigInteger.valueOf(-4));

        assertThat(negativeThreeQuarters.numerator()).isEqualTo(BigInteger.valueOf(-3));
        assertThat(negativeThreeQuarters.denominator()).isEqualTo(BigInteger.valueOf(4));
    }

    @Test
    void rejectsAZeroDenominator() {
        assertThatThrownBy(() -> new Rational(BigInteger.ONE, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAcrossDifferentDenominators() {
        Rational oneHalf = Rational.of(1, 2);
        Rational oneThird = Rational.of(1, 3);

        assertThat(oneHalf.add(oneThird)).isEqualTo(Rational.of(5, 6));
    }

    @Test
    void subtractsAcrossDifferentDenominators() {
        Rational oneHalf = Rational.of(1, 2);
        Rational oneThird = Rational.of(1, 3);

        assertThat(oneHalf.subtract(oneThird)).isEqualTo(Rational.of(1, 6));
    }

    @Test
    void multipliesTwoRationals() {
        Rational twoThirds = Rational.of(2, 3);
        Rational threeQuarters = Rational.of(3, 4);

        assertThat(twoThirds.multiply(threeQuarters)).isEqualTo(Rational.of(1, 2));
    }

    @Test
    void dividesByTheReciprocalOfTheOther() {
        Rational oneHalf = Rational.of(1, 2);
        Rational oneThird = Rational.of(1, 3);

        assertThat(oneHalf.divide(oneThird)).isEqualTo(Rational.of(3, 2));
    }

    @Test
    void rejectsDivisionByZero() {
        Rational oneHalf = Rational.of(1, 2);

        assertThatThrownBy(() -> oneHalf.divide(Rational.ZERO))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(Rational.of(3, 4).negate()).isEqualTo(Rational.of(-3, 4));
    }

    @Test
    void absDropsTheSign() {
        assertThat(Rational.of(-3, 4).abs()).isEqualTo(Rational.of(3, 4));
    }

    @Test
    void comparesByCrossMultiplication() {
        assertThat(Rational.of(1, 2).compareTo(Rational.of(1, 3))).isPositive();
        assertThat(Rational.of(1, 3).compareTo(Rational.of(1, 2))).isNegative();
        assertThat(Rational.of(2, 4).compareTo(Rational.of(1, 2))).isZero();
    }

    @Test
    void wholeBuildsAnIntegerRational() {
        assertThat(Rational.whole(5)).isEqualTo(Rational.of(5, 1));
    }
}
