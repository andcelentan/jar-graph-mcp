package org.thirdparty.library;

/**
 * Stands for a library class in archive tests, Spring or any other: a package outside the application, to check that
 * the package filter leaves it out of the analysis.
 */
public class ThirdPartyClient {

    public String fetch(String key) {
        return key;
    }
}
