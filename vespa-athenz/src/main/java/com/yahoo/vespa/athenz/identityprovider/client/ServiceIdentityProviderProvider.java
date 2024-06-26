// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.athenz.identityprovider.client;

import com.yahoo.container.di.componentgraph.Provider;
import com.yahoo.container.jdisc.athenz.AthenzIdentityProvider;
import com.yahoo.vespa.athenz.identity.ServiceIdentityProvider;

import javax.inject.Inject;

/**
 * @author olaa
 */
public class ServiceIdentityProviderProvider implements Provider<ServiceIdentityProvider>  {

    private AthenzIdentityProvider athenzIdentityProvider;

    @Inject
    public ServiceIdentityProviderProvider(AthenzIdentityProvider athenzIdentityProvider) {
        this.athenzIdentityProvider = athenzIdentityProvider;
    }

    @Override
    public ServiceIdentityProvider get() {
        if (athenzIdentityProvider instanceof AthenzIdentityProviderImpl impl) return impl;
        return null;
    }

    @Override
    public void deconstruct() {}

}
