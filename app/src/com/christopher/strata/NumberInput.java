package com.christopher.strata;

final class NumberInput {
    static float parse(String text) {
        try {
            float value=Float.parseFloat(text.trim());
            if(Float.isNaN(value)||Float.isInfinite(value))throw new NumberFormatException();
            return value;
        } catch(NumberFormatException e){throw new IllegalArgumentException("Enter a finite number.");}
    }
}
