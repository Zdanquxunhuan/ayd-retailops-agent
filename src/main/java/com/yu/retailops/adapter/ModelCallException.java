package com.yu.retailops.adapter;

/**
 * Contains a locally generated error code, never an upstream body or credentials.
 */
public final class ModelCallException extends RuntimeException {
    private final String code;

    public ModelCallException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
