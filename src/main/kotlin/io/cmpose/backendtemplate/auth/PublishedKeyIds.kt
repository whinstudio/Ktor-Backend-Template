package io.cmpose.backendtemplate.auth

/** Google's whole published key set, as kids only: lets the verifier learn every new key in one fetch. */
fun interface PublishedKeyIds {
    /**
     * Every `kid` Google currently publishes. May block on the network.
     *
     * @throws com.auth0.jwk.JwkException (or its subclass `NetworkException`) when the set cannot be fetched.
     */
    fun fetch(): Set<String>
}
