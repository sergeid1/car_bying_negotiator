package com.dealanalyzer.account;

import java.lang.annotation.*;

/** Resolves the Buyer Account credential from the HttpOnly cookie. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccountSession {}
