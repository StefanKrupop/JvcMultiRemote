// Copyright (c) 2016 Petter Wintzell

package com.albroco.barebonesdigest;

import com.albroco.barebonesdigest.DigestChallenge.QualityOfProtection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Represents a HTTP digest challenge response, as sent from the client to the server in a
 * <code>Authorization</code> header.
 * <p>
 * Instances of this class is normally created as a response to an incoming challenge using
 * {@link #responseTo(DigestChallenge)}. To generate the {@code Authorization} header, som
 * additional values must be set:
 * <ul>
 * <li>The {@link #username(String) username} and {@link #password(String) password} for
 * authentication.</li>
 * <li>The {@link #digestUri(String) digest-uri} used in the HTTP request.</li>
 * <li>The {@link #requestMethod(String) request method} of the request, such as "GET" or "POST".
 * </ul>
 * <p>
 * Here is an example of how to create a response:
 * <pre>
 * {@code
 * DigestChallengeResponse response = DigestChallengeResponse.responseTo(challenge)
 *                                                           .username("user")
 *                                                           .password("passwd")
 *                                                           .digestUri("/example")
 *                                                           .requestMethod("GET");
 * }
 * </pre>
 *
 * <h2>Challenge response reuse (optional)</h2>
 *
 * A challenge response from an earlier challenge can be reused in subsequent requests. If the
 * server accepts the reused challenge this will cut down on unnecessary traffic.
 * <p>
 * Each time the challenge response is reused the nonce count must be increased by one, see
 * {@link #incrementNonceCount()}. It is also a good idea to generate a new random client nonce with
 * {@link #randomizeClientNonce()}:
 * <pre>
 * {@code
 * response.incrementNonceCount().randomizeClientNonce(); // Response is now ready for reuse
 * }
 * </pre>
 *
 * <h2>Supporting {@code auth-int} quality of protection (optional, rarely used)</h2>
 *
 * With {@code auth-int} quality of protection the challenge response includes a hash of the
 * request's {@code entity-body}, which provides some protection from man-in-the-middle attacks.
 * Not all requests include an {@code entity-body}, PUT and POST do but GET does not. To support
 * {@code auth-int}, you must set either the digest of the {@code entity-body} (using
 * {@link #entityBodyDigest(byte[])}) or the {@code entity-body} itself (using
 * {@link #entityBody(byte[])}).
 *
 * <h2>Overriding the default client nonce (not recommended)</h2>
 *
 * The client nonce is a random string set by the client that is included in the challenge response.
 * By default, a random string is generated for the client nonce using {@code
 * java.security.SecureRandom}, which should be suitable for most purposes.
 * <p>
 * If you still for some reason need to override the default client nonce you can set it using
 * {@link #clientNonce(String)}. You may also have to call {@link #firstRequestClientNonce(String)},
 * see the documentation of that method for details.
 *
 * <h2>Concurrency</h2>
 *
 * This class is thread safe, read and write operations are synchronized.
 *
 * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">RFC 2617, "HTTP Digest Access
 * Authentication", Section 3.2.2, "The Authorization Request Header"</a>
 */
public final class DigestChallengeResponse {
  /**
   * The name of the HTTP request header ({@value #HTTP_HEADER_AUTHORIZATION}).
   */
  public static final String HTTP_HEADER_AUTHORIZATION = "Authorization";

  private static final int CLIENT_NONCE_BYTE_COUNT = 8;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final byte[] clientNonceByteBuffer = new byte[CLIENT_NONCE_BYTE_COUNT];

  private String algorithm;
  private String username;
  private String password;
  private String clientNonce;
  private String firstRequestClientNonce;
  private String quotedNonce;
  private int nonceCount;
  private String quotedOpaque;
  private final Set<DigestChallenge.QualityOfProtection> supportedQopTypes;
  private String digestUri;
  private String quotedRealm;
  private String requestMethod;
  private byte[] entityBodyDigest;
  private String A1;

  /**
   * Creates an empty challenge response.
   * <p>
   * Consider using {@link #responseTo(DigestChallenge)} when creating a response to a specific
   * challenge.
   */
  public DigestChallengeResponse() {
    supportedQopTypes = EnumSet.noneOf(QualityOfProtection.class);
    nonceCount(1).randomizeClientNonce()
        .firstRequestClientNonce(getClientNonce())
        .entityBody(new byte[0]);
  }

  /**
   * Returns {@code true} if a given challenge is supported and a response to it can be generated
   * (given that all other required values are supplied).
   * <p>
   * For a challenge to be supported, the following requirements must be met:
   * <ul>
   * <li>The digest algorithm must be supported (see {@link #isAlgorithmSupported(String)}).</li>
   * <li>The challenge must specify at least one supported qop (quality of protection), see
   * {@link #supportedQopTypes(Set)}.</li>
   * </ul>
   *
   * @param challenge the challenge
   * @return {@code true} if the challenge is supported
   */
  public static boolean isChallengeSupported(DigestChallenge challenge) {
    return isAlgorithmSupported(challenge.getAlgorithm()) &&
        !challenge.getSupportedQopTypes().isEmpty();
  }

  /**
   * Creates a digest challenge response, setting the values of the {@code realm}, {@code nonce},
   * {@code opaque}, and {@code algorithm} directives and the supported quality of protection
   * types based on a challenge.
   * <p>
   * If the challenge is not supported an exception is thrown. Use
   * {@link #isChallengeSupported(DigestChallenge)} to check if a challenge is supported before
   * calling this method.
   *
   * @param challenge the challenge
   * @return a response to the challenge.
   * @throws IllegalArgumentException if the challenge is not supported
   * @see #isChallengeSupported(DigestChallenge)
   */
  public static DigestChallengeResponse responseTo(DigestChallenge challenge) {
    return new DigestChallengeResponse().challenge(challenge);
  }

  /**
   * Returns {@code true} if a given digest algorithm is supported.
   * <p>
   * Supported values are "MD5", "MD5-sess", "SHA-256", "SHA-256-sess", and {@code null}. {@code
   * null} indicates that the digest is generated using MD5, but no {@code algorithm} directive is
   * included in the response.
   *
   * @param algorithm the algorithm
   * @return {@code true} if the algorithm is supported
   */
  public static boolean isAlgorithmSupported(String algorithm) {
    return algorithm == null || "MD5".equals(algorithm) || "MD5-sess".equals(algorithm) ||
        "SHA-256".equals(algorithm) || "SHA-256-sess".equals(algorithm);
  }

  /**
   * Sets the {@code algorithm} directive, which must be the same as the {@code algorithm} directive
   * of the challenge.
   * <p>
   * Use {@link #isAlgorithmSupported(String)} to check if a particular algorithm is supported.
   * <p>
   * Note: Setting the algorithm will also reset the <code>entity-body</code> to a byte array of
   * zero length, see {@link #entityBody(byte[])}. This is because the <code>entity-body</code> is
   * not stored, only the digest is. If you need to set both <code>entity-body</code> and
   * <code>algorithm</code>, make sure to set <code>algorithm</code> first.
   *
   * @param algorithm the value of the {@code algorithm} directive or {@code null} to not include an
   *                  algorithm in the response
   * @return this object
   * @throws IllegalArgumentException if the algorithm is not supported
   * @see #getAlgorithm()
   * @see #isAlgorithmSupported(String)
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse algorithm(String algorithm) {
    if (!isAlgorithmSupported(algorithm)) {
      throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
    }

    this.algorithm = algorithm;
    entityBody(new byte[0]);
    invalidateA1();
    return this;
  }

  /**
   * Returns the value of the {@code algorithm} directive.
   *
   * @return the value of the {@code algorithm} directive or {@code null} if {@code algorithm} is
   * not set
   * @see #algorithm(String)
   */
  public synchronized String getAlgorithm() {
    return algorithm;
  }

  /**
   * Sets the username to use for authentication.
   *
   * @param username the username
   * @return this object
   * @see #getUsername()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse username(String username) {
    this.username = username;
    invalidateA1();
    return this;
  }

  /**
   * Returns the username to use for authentication.
   *
   * @return the username
   * @see #username(String)
   */
  public synchronized String getUsername() {
    return username;
  }

  /**
   * Sets the password to use for authentication.
   *
   * @param password the password
   * @return this object
   * @see #getPassword()
   */
  public synchronized DigestChallengeResponse password(String password) {
    this.password = password;
    invalidateA1();
    return this;
  }

  /**
   * Returns the password to use for authentication.
   *
   * @return the password
   * @see #password(String)
   */
  public synchronized String getPassword() {
    return password;
  }

  /**
   * Sets the {@code cnonce} directive, which is a random string generated by the client that will
   * be included in the challenge response hash.
   * <p>
   * There is normally no need to manually set the client nonce since it will have a default
   * value of a randomly generated string. If you do, make sure to call
   * {@link #firstRequestClientNonce(String)} if you modify the client nonce for the first request,
   * or some session variants of algorithms (those ending in <code>-sess</code>) may not work.
   *
   * @param clientNonce The unquoted value of the {@code cnonce} directive.
   * @return this object
   * @see #getClientNonce()
   * @see #randomizeClientNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse clientNonce(String clientNonce) {
    this.clientNonce = clientNonce;
    if ("MD5-sess".equals(getAlgorithm())) {
      invalidateA1();
    }
    return this;
  }

  /**
   * Returns the value of the {@code cnonce} directive.
   * <p>
   * Unless overridden by calling {@link #clientNonce(String)}, the {@code cnonce} directive is
   * set to a randomly generated string.
   *
   * @return the {@code cnonce} directive
   * @see #clientNonce(String)
   */
  public synchronized String getClientNonce() {
    return clientNonce;
  }

  /**
   * Sets the {@code cnonce} directive to a random value.
   *
   * @return this object
   * @see #clientNonce(String)
   * @see #getClientNonce()
   */
  public synchronized DigestChallengeResponse randomizeClientNonce() {
    return clientNonce(generateRandomNonce());
  }

  /**
   * Sets the value of client nonce used in the first request.
   * <p>
   * This value is used in session based algorithms (those ending in <code>-sess</code>). If the
   * challenge is reused for multiple request, the original client nonce used when responding to
   * the original challenge is used in subsequent challenge responses, even if the client changes
   * the client nonce for subsequent requests.
   * <p>
   * Normally, there is no need to call this method. The default value of the client nonce is a
   * randomly generated string, and the default value of the first request client nonce is the
   * same string. It is only if you override the default value and supply your own client nonce
   * for the first request that you must make sure to call this method with the same value:
   * <blockquote>
   * {@code
   * response.clientNonce("my own client nonce").firstRequestClientNonce(response.getClientNonce());
   * }
   * </blockquote>
   *
   * @param firstRequestClientNonce the client nonce value used in the first request
   * @return this object
   * @see #getFirstRequestClientNonce()
   * @see #clientNonce(String)
   * @see #getClientNonce()
   * @see #randomizeClientNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2.2">Section 3.2.2.2, A1, of RFC
   * 2617</a>
   */
  public synchronized DigestChallengeResponse firstRequestClientNonce(String
      firstRequestClientNonce) {
    this.firstRequestClientNonce = firstRequestClientNonce;
    if ("MD5-sess".equals(getAlgorithm())) {
      invalidateA1();
    }
    return this;
  }

  /**
   * Returns the value of client nonce used in the first request.
   * <p>
   * This value is used in session based algorithms (those ending in <code>-sess</code>). If the
   * challenge is reused for multiple request, the original client nonce used when responding to
   * the original challenge is used in subsequent challenge responses, even if the client changes
   * the client nonce for subsequent requests.
   *
   * @return the value of client nonce used in the first request.
   * @see #firstRequestClientNonce(String)
   * @see #clientNonce(String)
   * @see #getClientNonce()
   * @see #randomizeClientNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2.2">Section 3.2.2.2, A1, of RFC
   * 2617</a>
   */
  public synchronized String getFirstRequestClientNonce() {
    return firstRequestClientNonce;
  }

  /**
   * Sets the {@code nonce} directive, which must be the same as the nonce directive of the
   * challenge.
   * <p>
   * Setting the {@code nonce} directive resets the nonce count to one.
   *
   * @param quotedNonce the quoted value of the {@code nonce} directive
   * @return this object
   * @see #getQuotedNonce()
   * @see #nonce(String)
   * @see #getNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse quotedNonce(String quotedNonce) {
    this.quotedNonce = quotedNonce;
    resetNonceCount();
    if ("MD5-sess".equals(getAlgorithm())) {
      invalidateA1();
    }
    return this;
  }

  /**
   * Returns the quoted value of the {@code nonce} directive.
   *
   * @return the quoted value of the {@code nonce} directive
   * @see #quotedNonce(String)
   * @see #nonce(String)
   * @see #getNonce()
   */
  public synchronized String getQuotedNonce() {
    return quotedNonce;
  }

  /**
   * Sets the {@code nonce} directive, which must be the same as the {@code nonce} directive of the
   * challenge.
   * <p>
   * Setting the nonce directive resets the nonce count to one.
   *
   * @param unquotedNonce the unquoted value of the {@code nonce} directive
   * @return this object
   * @see #getNonce()
   * @see #quotedNonce(String)
   * @see #getQuotedNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse nonce(String unquotedNonce) {
    if (unquotedNonce == null) {
      return quotedNonce(null);
    }
    return quotedNonce(Rfc2616AbnfParser.quote(unquotedNonce));
  }

  /**
   * Returns the unquoted value of the {@code nonce} directive
   *
   * @return the unquoted value of the {@code nonce} directive
   * @see #nonce(String)
   * @see #quotedNonce(String)
   * @see #getQuotedNonce()
   */
  public synchronized String getNonce() {
    // TODO: Cache since value is used each time a header is written
    if (quotedNonce == null) {
      return null;
    }

    return Rfc2616AbnfParser.unquote(quotedNonce);
  }

  /**
   * Sets the integer representation of the {@code nonce-count} directive, which indicates how many
   * times this a challenge response with this nonce has been used.
   * <p>
   * This is useful when using a challenge response from a previous challenge when sending a
   * request. For each time a challenge response is used, the nonce count should be increased by
   * one.
   *
   * @param nonceCount integer representation of the {@code nonce-count} directive
   * @return this object
   * @see #getNonceCount()
   * @see #resetNonceCount()
   * @see #incrementNonceCount()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse nonceCount(int nonceCount) {
    this.nonceCount = nonceCount;
    return this;
  }

  /**
   * Increments the value of the {@code nonce-count} by one.
   *
   * @return this object
   * @see #nonceCount(int)
   * @see #getNonceCount()
   * @see #resetNonceCount()
   */
  public synchronized DigestChallengeResponse incrementNonceCount() {
    nonceCount(nonceCount + 1);
    return this;
  }

  /**
   * Sets the value of the {@code nonce-count} to one.
   *
   * @return this object
   * @see #nonceCount(int)
   * @see #getNonceCount()
   * @see #incrementNonceCount()
   */
  public synchronized DigestChallengeResponse resetNonceCount() {
    nonceCount(1);
    return this;
  }

  /**
   * Returns the integer representation of the {@code nonce-count} directive.
   *
   * @return the integer representation of the {@code nonce-count} directive
   * @see #nonceCount(int)
   * @see #resetNonceCount()
   * @see #incrementNonceCount()
   */
  public synchronized int getNonceCount() {
    return nonceCount;
  }


  /**
   * Sets the {@code opaque} directive, which must be the same as the {@code opaque} directive of
   * the challenge.
   *
   * @param quotedOpaque the quoted value of the {@code opaque} directive, or {@code null} if no
   *                     opaque directive should be included in the challenge response
   * @return this object
   * @see #getQuotedOpaque()
   * @see #opaque(String)
   * @see #getOpaque()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse quotedOpaque(String quotedOpaque) {
    this.quotedOpaque = quotedOpaque;
    return this;
  }

  /**
   * Returns the the quoted value of the {@code opaque} directive, or {@code null}.
   *
   * @return the the quoted value of the {@code opaque} directive, or {@code null} if the {@code
   * opaque} is not set
   * @see #quotedOpaque(String)
   * @see #opaque(String)
   * @see #getOpaque()
   */
  public synchronized String getQuotedOpaque() {
    return quotedOpaque;
  }

  /**
   * Sets the {@code opaque} directive, which must be the same as the {@code opaque} directive of
   * the challenge.
   * <p>
   * Note: Since the value of the {@code opaque} directive is always received from a challenge
   * quoted it is normally better to use the {@link #quotedOpaque(String)} method to avoid
   * unnecessary quoting/unquoting.
   *
   * @param unquotedOpaque the unquoted value of the {@code opaque} directive, or {@code null} if no
   *                       {@code opaque} directive should be included in the challenge response
   * @return this object
   * @see #getOpaque()
   * @see #quotedOpaque(String)
   * @see #getQuotedOpaque()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse opaque(String unquotedOpaque) {
    if (unquotedOpaque == null) {
      return quotedOpaque(null);
    }
    return quotedOpaque(Rfc2616AbnfParser.quote(unquotedOpaque));
  }

  /**
   * Returns the the unquoted value of the {@code opaque} directive, or {@code null}.
   *
   * @return the the unquoted value of the {@code opaque} directive, or {@code null} if the {@code
   * opaque} is not set
   * @see #opaque(String)
   * @see #quotedOpaque(String)
   * @see #getQuotedOpaque()
   */
  public synchronized String getOpaque() {
    if (quotedOpaque == null) {
      return null;
    }
    return Rfc2616AbnfParser.unquote(quotedOpaque);
  }

  /**
   * Sets the type of "quality of protection" that can be used when responding to the request.
   * <p>
   * Normally, this value is sent by the server in the challenge, but setting it manually can be
   * used to force a particular qop type. To see which quality of protection that will be used in
   * the response, see {@link #getQop()}.
   *
   * @param supportedQopTypes the types of quality of protection that the server supports, must not
   *                          be empty
   * @return this object
   * @throws IllegalArgumentException if supportedQopTypes is empty
   * @see #getSupportedQopTypes()
   * @see #getQop()
   */
  public synchronized DigestChallengeResponse supportedQopTypes(Set<QualityOfProtection>
      supportedQopTypes) {
    if (supportedQopTypes.isEmpty()) {
      throw new IllegalArgumentException("The set of supported qop types cannot be empty");
    }

    this.supportedQopTypes.clear();
    this.supportedQopTypes.addAll(supportedQopTypes);
    return this;
  }

  /**
   * Returns the type of "quality of protection" that can be used when responding to the request.
   *
   * @return the types of quality of protection that the server supports
   * @see #supportedQopTypes(Set)
   * @see #getQop()
   */
  public synchronized Set<QualityOfProtection> getSupportedQopTypes() {
    return Collections.unmodifiableSet(supportedQopTypes);
  }

  /**
   * Returns the "quality of protection" that will be used for the response.
   * <p>
   * This is a derived value, computed from the set of supported "quality of protection" types:
   * <ol>
   * <li>If {@link QualityOfProtection#AUTH} is supported it is used.</li>
   * <li>Otherwise, if {@link QualityOfProtection#AUTH_INT} is supported it is used.</li>
   * <li>Otherwise, if {@link QualityOfProtection#UNSPECIFIED_RFC2069_COMPATIBLE} is supported it
   * is used.</li>
   * <li>Otherwise, there is no way of responding to the challenge without violating the
   * specification. {@code null} will be returned.</li>
   * </ol>
   *
   * @return the "quality of protection" that will be used for the response
   * @see #supportedQopTypes(Set)
   * @see #getSupportedQopTypes()
   * @see #entityBodyDigest(byte[])
   * @see #getEntityBodyDigest()
   * @see #entityBody(byte[])
   */
  public synchronized QualityOfProtection getQop() {
    if (supportedQopTypes.contains(QualityOfProtection.AUTH)) {
      return QualityOfProtection.AUTH;
    }

    if (supportedQopTypes.contains(QualityOfProtection.AUTH_INT)) {
      return QualityOfProtection.AUTH_INT;
    }

    if (supportedQopTypes.contains(QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE)) {
      return QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE;
    }

    return null;
  }

  /**
   * Sets the {@code digest-uri} directive, which must be exactly the same as the
   * {@code Request-URI} of the {@code Request-Line} of the HTTP request.
   * <p>
   * The digest URI is explained in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>,
   * and refers to the explanation of Request-URI found in
   * <a href="https://tools.ietf.org/html/rfc2616#section-5.1.2">Section 5.1.2 of RFC 2616</a>.
   * <p>
   * Examples: If the {@code Request-Line} is
   * <pre>
   * GET http://www.w3.org/pub/WWW/TheProject.html HTTP/1.1
   * </pre>
   * the {@code Request-URI} (and {@code digest-uri}) is "{@code
   * http://www.w3.org/pub/WWW/TheProject.html}". If {@code Request-Line} is
   * <pre>
   * GET /pub/WWW/TheProject.html HTTP/1.1
   * </pre>
   * the {@code Request-URI} is "{@code /pub/WWW/TheProject.html}".
   * <p>
   * This can be problematic since depending on the HTTP stack being used the {@code Request-Line}
   * and {@code Request-URI} values may not be accessible. If in doubt, a sensible guess is to set
   * the {@code digest-uri} to the path part of the URL being requested, for instance using
   * <a href="https://developer.android.com/reference/java/net/URL.html#getPath()">
   * <code>getPath()</code> in the <code>URL</code> class</a>.
   *
   * @param digestUri the value of the digest-uri directive
   * @return this object
   * @see #getDigestUri()
   */
  public synchronized DigestChallengeResponse digestUri(String digestUri) {
    this.digestUri = digestUri;
    return this;
  }

  /**
   * Returns the value of the {@code digest-uri} directive.
   *
   * @return the value of the {@code digest-uri} directive
   * @see #digestUri(String)
   */
  public synchronized String getDigestUri() {
    return digestUri;
  }

  /**
   * Sets the {@code realm} directive, which must be the same as the {@code realm} directive of
   * the challenge.
   *
   * @param quotedRealm the quoted value of the {@code realm} directive
   * @return this object
   * @see #getQuotedRealm()
   * @see #realm(String)
   * @see #getRealm()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse quotedRealm(String quotedRealm) {
    this.quotedRealm = quotedRealm;
    invalidateA1();
    return this;
  }

  /**
   * Returns the quoted value of the {@code realm} directive.
   *
   * @return the quoted value of the {@code realm} directive
   * @see #quotedRealm(String)
   * @see #realm(String)
   * @see #getRealm()
   */
  public synchronized String getQuotedRealm() {
    return quotedRealm;
  }

  /**
   * Sets the {@code realm} directive, which must be the same as the {@code realm} directive of
   * the challenge.
   *
   * @param unquotedRealm the unquoted value of the {@code realm} directive
   * @return this object
   * @see #getRealm()
   * @see #quotedRealm(String)
   * @see #getQuotedRealm()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized DigestChallengeResponse realm(String unquotedRealm) {
    if (unquotedRealm == null) {
      return quotedRealm(null);
    }
    return quotedRealm(Rfc2616AbnfParser.quote(unquotedRealm));
  }

  /**
   * Returns the unquoted value of the {@code realm} directive.
   *
   * @return the unquoted value of the {@code realm} directive
   * @see #realm(String)
   * @see #quotedRealm(String)
   * @see #getQuotedRealm()
   */
  public synchronized String getRealm() {
    // TODO: Cache since value is used each time a header is written
    if (quotedRealm == null) {
      return null;
    }
    return Rfc2616AbnfParser.unquote(quotedRealm);
  }

  /**
   * Sets the HTTP method of the request (GET, POST, etc).
   *
   * @param requestMethod the request method
   * @return this object
   * @see #getRequestMethod()
   * @see <a href="https://tools.ietf.org/html/rfc2616#section-5.1.1">Section 5.1.1 of RFC 2616</a>
   */
  public synchronized DigestChallengeResponse requestMethod(String requestMethod) {
    this.requestMethod = requestMethod;
    return this;
  }

  /**
   * Returns the HTTP method of the request.
   *
   * @return the HTTP method
   * @see #requestMethod(String)
   */
  public synchronized String getRequestMethod() {
    return requestMethod;
  }

  /**
   * Sets the {@code entity-body} of the request, which is only used for the "auth-int" quality of
   * protection.
   * <p>
   * The default value is a byte array of zero length. Note that the <code>entity-body</code> is
   * not stored, to save space only the digest of the <code>entity-body</code> is stored. For this
   * reason, changing the digest algorithm (see {@link #algorithm(String)}) will reset the
   * <code>entity-body</code> to a zero-length array.
   * <p>
   * With "auth-int" quality of protection, the whole {@code entity-body} of the message is
   * hashed and included in the response, providing some protection against tampering.
   * <p>
   * The {@code entity-body} is not the same as the {@code message-body} as explained in
   * <a href="https://tools.ietf.org/html/rfc2616#section-7.2">RFC 2616, Section 7.2:</a>
   * <blockquote>
   * [&hellip;]The entity-body is obtained from the message-body by decoding any
   * Transfer-Encoding that might have been applied to ensure safe and proper transfer of the
   * message.
   * </blockquote>
   * So if, for example, {@code Transfer-Encoding} is {@code gzip}, the {@code entity-body} is the
   * unzipped message and the {@code message-body} is the gzipped message.
   * <p>
   * Not all requests include an {@code entity-body}, as explained in
   * <a href="https://tools.ietf.org/html/rfc2616#section-4.3">RFC 2616, Section 4.3:</a>
   * <blockquote>
   * [&hellip;]The presence of a message-body in a request is signaled by the inclusion of a
   * Content-Length or Transfer-Encoding header field in the request's message-headers.[&hellip;]
   * </blockquote>
   * In particular, PUT and POST requests include an {@code entity-body} (although it may be of
   * zero length), GET requests do not. To be fully conformant with the standards, "auth-int"
   * cannot be used to authenticate requests without an {@code entity-body}. Some servers
   * implementations allow requests without an {@code entity-body} to be authenticated using an
   * {@code entity-body} of zero length.
   *
   * @param entityBody the {@code entity-body}
   * @return this object
   * @see #entityBodyDigest(byte[])
   */
  public synchronized DigestChallengeResponse entityBody(byte[] entityBody) {
    entityBodyDigest = calculateChecksum(entityBody);
    return this;
  }

  /**
   * Sets the digest of the {@code entity-body} of the request, which is only used for the
   * "auth-int" quality of protection.
   * <p>
   * Note that the {@code entity-body} is not the same as the {@code message-body}. See
   * {@link #entityBody(byte[])} for details.
   * <p>
   * Here is an example of how to compute the SHA-256 digest of an entity body:
   * <pre>
   * {@code
   * MessageDigest md = MessageDigest.getInstance("SHA-256");
   * md.update(entityBody);
   * byte[] digest = md.digest();
   * }
   * </pre>
   *
   * @param entityBodyDigest the digest of the {@code entity-body}
   * @return this object
   * @see #entityBody(byte[])
   */
  public synchronized DigestChallengeResponse entityBodyDigest(byte[] entityBodyDigest) {
    this.entityBodyDigest = Arrays.copyOf(entityBodyDigest, entityBodyDigest.length);
    return this;
  }

  /**
   * Returns the digest of the {@code entity-body}.
   *
   * @return the digest of the {@code entity-body}
   */
  public synchronized byte[] getEntityBodyDigest() {
    return Arrays.copyOf(entityBodyDigest, entityBodyDigest.length);
  }

  /**
   * Returns {@code true} if the digest of the {@code entity-body} is required to generate an
   * authorization header for this response.
   * <p>
   * For most challenges the digest of the {@code entity-body} is not used. It is only required
   * if {@link #getQop()} returns {@link QualityOfProtection#AUTH_INT}.
   *
   * @return {@code true} if the digest of the {@code entity-body} must be set
   * @see #entityBody(byte[])
   * @see #entityBodyDigest(byte[])
   * @see #getSupportedQopTypes()
   */
  public synchronized boolean isEntityBodyDigestRequired() {
    return getQop() == QualityOfProtection.AUTH_INT;
  }

  /**
   * Sets the values of the {@code realm}, {@code nonce}, {@code opaque}, and {@code algorithm}
   * directives and the supported quality of protection types based on a challenge.
   * <p>
   * If the challenge is not supported an exception is thrown. Use
   * {@link #isChallengeSupported(DigestChallenge)} to check if a challenge is supported before
   * calling this method.
   *
   * @param challenge the challenge
   * @return this object
   * @throws IllegalArgumentException if the challenge is not supported
   * @see #isChallengeSupported(DigestChallenge)
   */
  public synchronized DigestChallengeResponse challenge(DigestChallenge challenge) {
    return quotedNonce(challenge.getQuotedNonce()).quotedOpaque(challenge.getQuotedOpaque())
        .quotedRealm(challenge.getQuotedRealm())
        .algorithm(challenge.getAlgorithm())
        .supportedQopTypes(challenge.getSupportedQopTypes());
  }

  /**
   * Returns the {@code credentials}, that is, the string to set in the {@code Authorization}
   * HTTP request header.
   * <p>
   * Before calling this method a number of values and directives must be set. The following are the
   * most important:
   * <ul>
   * <li>{@link #username(String)}.</li>
   * <li>{@link #password(String)}.</li>
   * <li>{@link #digestUri(String)}.</li>
   * <li>{@link #requestMethod(String)}.</li>
   * </ul>
   * <p>
   * If the qop type is <code>auth-int</code> the following must also be set unless the default
   * value (zero-length entity body) is applicable:
   * <ul>
   * <li>{@link #entityBody(byte[])} or {@link #entityBodyDigest(byte[])}. See also
   * {@link #isEntityBodyDigestRequired()}.</li>
   * </ul>
   * <p>
   * The following directives must also be set, but are normally parsed from the challenge or have
   * default values, listed here mostly for completions sake:
   * <ul>
   * <li>{@link #quotedRealm(String)}, parsed from the challenge.</li>
   * <li>{@link #quotedNonce(String)}, parsed from the challenge.</li>
   * <li>{@link #supportedQopTypes(Set)}, parsed from the challenge.</li>
   * <li>{@link #clientNonce(String)}}, except if using the
   * {@link QualityOfProtection#UNSPECIFIED_RFC2069_COMPATIBLE} qop (not recommended). Set to a
   * random value by default.</li>
   * <li>{@link #firstRequestClientNonce(String)} if the algorithm is MD-sess. Set to the client
   * nonce by default.</li>
   * </ul>
   *
   * @return the string to set in the {@code Authorization} HTTP request header
   * @throws InsufficientInformationException if any of the mandatory directives and values
   *                                          listed above has not been set
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public synchronized String getHeaderValue() {
    if (username == null) {
      throw new InsufficientInformationException("Mandatory username not set");
    }
    if (password == null) {
      throw new InsufficientInformationException("Mandatory password not set");
    }
    if (quotedRealm == null) {
      throw new InsufficientInformationException("Mandatory realm not set");
    }
    if (quotedNonce == null) {
      throw new InsufficientInformationException("Mandatory nonce not set");
    }
    if (digestUri == null) {
      throw new InsufficientInformationException("Mandatory digest-uri not set");
    }
    if (requestMethod == null) {
      throw new InsufficientInformationException("Mandatory request method not set");
    }
    if (getSupportedQopTypes().isEmpty() || getQop() == null) {
      throw new InsufficientInformationException("Mandatory supported qop types not set");
    }
    if (clientNonce == null && getQop() != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      throw new InsufficientInformationException("Client nonce must be set when qop is set");
    }
    if ("MD5-sess".equals(getAlgorithm()) && getFirstRequestClientNonce() == null) {
      throw new InsufficientInformationException(
          "First request client nonce must be set when algorithm is MD5-sess");
    }

    String response = calculateResponse();

    StringBuilder result = new StringBuilder();
    result.append("Digest ");

    // Username is defined in Section 3.2.2 of RFC 2617
    // username         = "username" "=" username-value
    // username-value   = quoted-string
    result.append("username=");
    result.append(Rfc2616AbnfParser.quote(username));

    // Realm is defined in RFC 2617, Section 1.2
    // realm       = "realm" "=" realm-value
    // realm-value = quoted-string
    result.append(",realm=");
    result.append(quotedRealm);

    // nonce             = "nonce" "=" nonce-value
    // nonce-value       = quoted-string
    result.append(",nonce=");
    result.append(quotedNonce);

    // digest-uri       = "uri" "=" digest-uri-value
    // digest-uri-value = request-uri   ; As specified by HTTP/1.1
    result.append(",uri=");
    result.append(Rfc2616AbnfParser.quote(digestUri));

    // Response is defined in RFC 2617, Section 3.2.2 and 3.2.2.1
    // response         = "response" "=" request-digest
    result.append(",response=");
    result.append(response);

    // cnonce is defined in RFC 2617, Section 3.2.2
    // cnonce           = "cnonce" "=" cnonce-value
    // cnonce-value     = nonce-value
    // Must be present if qop is specified, must not if qop is unspecified
    if (getQop() != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      result.append(",cnonce=");
      result.append(Rfc2616AbnfParser.quote(getClientNonce()));
    }

    // Opaque and algorithm are explained in Section 3.2.2 of RFC 2617:
    // "The values of the opaque and algorithm fields must be those supplied
    // in the WWW-Authenticate response header for the entity being
    // requested."

    if (quotedOpaque != null) {
      result.append(",opaque=");
      result.append(quotedOpaque);
    }

    if (algorithm != null) {
      result.append(",algorithm=");
      result.append(algorithm);
    }

    if (getQop() != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      result.append(",qop=");
      result.append(getQop().getQopValue());
    }

    // Nonce count is defined in RFC 2617, Section 3.2.2
    // nonce-count      = "nc" "=" nc-value
    // nc-value         = 8LHEX (lower case hex)
    // Must be present if qop is specified, must not if qop is unspecified
    if (getQop() != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      result.append(",nc=");
      result.append(String.format("%08x", nonceCount));
    }

    return result.toString();
  }

  private String calculateResponse() {
    MessageDigest digest = createMessageDigestForAlgorithm(algorithm);

    QualityOfProtection qop = getQop();
    String a1 = getA1(digest);
    String a2 = calculateA2(qop);
    String secret = H(digest, a1);
    String data = "";

    switch (qop) {
      case AUTH:
      case AUTH_INT:
        data = joinWithColon(getNonce(),
            String.format("%08x", nonceCount),
            getClientNonce(),
            qop.getQopValue(),
            H(digest, a2));
        break;
      case UNSPECIFIED_RFC2069_COMPATIBLE: {
        data = joinWithColon(getNonce(), H(digest, a2));
        break;
      }
    }

    return "\"" + KD(digest, secret, data) + "\"";
  }

  private static MessageDigest createMessageDigestForAlgorithm(String algorithm) {
    String androidDigestName = getAndroidDigestNameForAlgorithm(algorithm);

    try {
      return MessageDigest.getInstance(androidDigestName);
    } catch (NoSuchAlgorithmException e) {
      // All digest names above must be supported since API level 1, see
      // https://developer.android.com/reference/java/security/MessageDigest.html
      throw new RuntimeException("Mandatory MessageDigest not supported: " + androidDigestName);
    }
  }

  private static String getAndroidDigestNameForAlgorithm(String algorithm) {
    String androidDigestName;

    if (algorithm == null || algorithm.equals("MD5") || algorithm.equals("MD5-sess")) {
      androidDigestName = "MD5";
    } else if (algorithm.equals("SHA-256") || algorithm.equals("SHA-256-sess")) {
      androidDigestName = "SHA-256";
    } else {
      throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
    }
    return androidDigestName;
  }

  private String getA1(MessageDigest digest) {
    if (A1 == null) {
      A1 = calculateA1(digest);
    }
    return A1;
  }

  private String calculateA1(MessageDigest digest) {
    if (getAlgorithm() == null) {
      return joinWithColon(username, getRealm(), password);
    } else if (getAlgorithm().endsWith("-sess")) {
      return joinWithColon(H(digest, joinWithColon(username, getRealm(), password)),
          getNonce(),
          getFirstRequestClientNonce());
    } else {
      return joinWithColon(username, getRealm(), password);
    }
  }

  private void invalidateA1() {
    A1 = null;
  }

  private String calculateA2(QualityOfProtection qop) {
    if (qop == QualityOfProtection.AUTH_INT) {
      return joinWithColon(requestMethod, digestUri, encodeHexString(entityBodyDigest));
    }

    return joinWithColon(requestMethod, digestUri);
  }

  private String joinWithColon(String... parts) {
    StringBuilder result = new StringBuilder();

    for (String part : parts) {
      if (result.length() > 0) {
        result.append(":");
      }
      result.append(part);
    }

    return result.toString();
  }

  /**
   * Calculates the function H for some string, as per the description of algorithm in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 in RFC 2617.</a>
   * <p>
   * <blockquote>
   * For the "MD5" and "MD5-sess" algorithms
   *
   * H(data) = MD5(data)
   * </blockquote>
   *
   * @param string the string
   * @return the value of <em>H(string)</em>
   */
  private String H(MessageDigest digest, String string) {
    // TODO find out which encoding to use
    return encodeHexString(calculateChecksum(digest, string.getBytes()));
  }

  private byte[] calculateChecksum(MessageDigest digest, byte[] data) {
    digest.reset();
    digest.update(data);
    return digest.digest();
  }

  private byte[] calculateChecksum(byte[] data) {
    return calculateChecksum(createMessageDigestForAlgorithm(algorithm), data);
  }

  /**
   * Calculates the function KD for some secret and data, as per the description of algorithm in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 in RFC 2617.</a>
   * <p>
   * For MD5:
   * <blockquote>
   * KD(secret, data) = H(concat(secret, ":", data))
   * </blockquote>
   *
   * @param secret the secret
   * @param data   the data
   * @return the value of <em>KD(secret, data)</em>
   */
  private String KD(MessageDigest digest, String secret, String data) {
    return H(digest, secret + ":" + data);
  }

  private static String encodeHexString(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(Integer.toHexString((b & 0xf0) >> 4));
      result.append(Integer.toHexString((b & 0x0f)));
    }
    return result.toString();
  }

  private static synchronized String generateRandomNonce() {
    RANDOM.nextBytes(clientNonceByteBuffer);
    return encodeHexString(clientNonceByteBuffer);
  }

  @Override
  public synchronized String toString() {
    return "DigestChallengeResponse{" +
        "algorithm=" + algorithm +
        ", realm=" + quotedRealm +
        ", supportedQopTypes=" + supportedQopTypes +
        ", nonce=" + quotedNonce +
        ", nonceCount=" + nonceCount +
        ", clientNonce=" + clientNonce +
        ", firstRequestClientNonce=" + firstRequestClientNonce +
        ", opaque=" + quotedOpaque +
        ", username=" + username +
        ", password=*" +
        ", requestMethod=" + requestMethod +
        ", digestUri=" + digestUri +
        ", entityBodyDigest=" + Arrays.toString(entityBodyDigest) +
        '}';
  }
}
